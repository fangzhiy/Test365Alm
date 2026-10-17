import {expect,test} from '@playwright/test'
import type {Locator,Page} from '@playwright/test'
import {createHash,randomUUID} from 'node:crypto'
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {isOwnedCiRun,seedProjectAccessScope} from './ownedR03'

async function login(page:Page,user:string,password:string){
 await page.goto('/');await page.getByRole('link',{name:'登录 Test365Alm'}).click();await page.locator('#username').fill(user);await page.locator('#password').fill(password);await page.locator('#kc-login').click();await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();const me=await page.request.get('/api/v1/me',{timeout:10_000});expect(me.status()).toBe(200);return (await me.json()).id as string
}
async function write(page:Page,path:string,data:unknown,method='POST'){
 const response=await page.request.get('/api/v1/csrf',{timeout:10_000});expect(response.status()).toBe(200);const csrf=await response.json();return page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID()},data,timeout:10_000})
}
async function select(page:Page,tenant:string,project:string){
 const region=page.getByRole('region',{name:'租户、项目与成员'});await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant);const projects=region.locator('.access-selects select').nth(1);await expect(projects.locator(`option[value="${project}"]`)).toHaveCount(1);await projects.selectOption(project)
}
async function downloaded(page:Page,button:Locator){
 const result=page.waitForEvent('download',{timeout:10_000});await button.click();const download=await result;expect(await download.failure()).toBeNull();const stream=await download.createReadStream();if(!stream)throw new Error('Controlled download has no readable bytes');const chunks:Buffer[]=[];for await(const chunk of stream)chunks.push(Buffer.from(chunk));return Buffer.concat(chunks).toString('utf8')
}

test('real Keycloak UI member uploads scans attaches and preserves old asset revision',async({page,browser})=>{
 test.skip(!isOwnedCiRun(),'Requires current CI-owned identity database storage and scanner');test.setTimeout(180_000)
 const credentials=['R03_TEST_USER','R03_TEST_USER_PASSWORD','R03_MEMBER_USER','R03_MEMBER_PASSWORD','R03_VIEWER_USER','R03_VIEWER_PASSWORD'].map(k=>process.env[k]);if(credentials.some(v=>!v))throw new Error('Ephemeral credentials missing')
 const [au,ap,mu,mp,vu,vp]=credentials as string[],memberContext=await browser.newContext(),viewerContext=await browser.newContext(),member=await memberContext.newPage(),viewer=await viewerContext.newPage()
 const timeline:object[]=[],http:object[]=[];let businessError:unknown,cleanupError:unknown,evidenceError:unknown
 for(const p of [member,viewer])p.on('response',r=>{const pathname=new URL(r.url()).pathname;if(pathname.startsWith('/api/v1/'))http.push({pathname,status:r.status()})})
 async function step(name:string,action:()=>Promise<void>){const start=Date.now();let failed=false;try{await test.step(name,action,{timeout:30_000})}catch(e){failed=true;throw e}finally{timeline.push({name,failed,durationMs:Date.now()-start,pathname:new URL(member.url()).pathname,http:http.splice(0)})}}
 try{
  const adminId=await login(page,au,ap),memberId=await login(member,mu,mp),viewerId=await login(viewer,vu,vp),{tenantId,domainId}=seedProjectAccessScope(adminId,viewerId,memberId)
  const created=await write(page,'/api/v1/projects',{tenantId,domainId,code:`R11-${randomUUID()}`,name:'R11 attachment project'});expect(created.status()).toBe(201);const project=(await created.json()).id as string,root=`/api/v1/projects/${project}`
  for(const [principal,role]of[[memberId,'PROJECT_MEMBER'],[viewerId,'PROJECT_VIEWER']])expect((await write(page,`${root}/members/${principal}`,{roles:[role],authorizationVersion:0},'PUT')).status()).toBe(200)
  await select(member,tenantId,project);const permissions=await member.request.get(`/api/v1/me/permissions?projectId=${project}`,{timeout:10_000});expect(permissions.status()).toBe(200);expect((await permissions.json()).roles).toEqual(['PROJECT_MEMBER'])
  const title=`R11 requirement ${randomUUID()}`,original='R11 harmless controlled original text\n',updated='R11 harmless controlled revised text\n',filename='r11-original.txt'
  const req=member.locator('section.requirements-panel'),attachments=req.getByRole('region',{name:'附件与资产'})
  let requirementId='',uploadId='',assetId='',revisionId='',attachmentId=''
  await step('Ordinary MEMBER creates requirement and uploads benign bytes through actual attachment DOM',async()=>{
   await req.getByLabel('需求标题').fill(title);const createdRequirement=member.waitForResponse(r=>new URL(r.url()).pathname===`${root}/requirements`&&r.request().method()==='POST');await req.getByRole('button',{name:'创建需求'}).click();const response=await createdRequirement;expect(response.status()).toBe(201);requirementId=(await response.json()).id;await expect(req).toContainText('需求已创建')
   await attachments.getByLabel('选择附件文件').setInputFiles({name:filename,mimeType:'text/plain',buffer:Buffer.from(original)})
   const session=member.waitForResponse(r=>new URL(r.url()).pathname===`${root}/uploads`&&r.request().method()==='POST');await attachments.getByRole('button',{name:'创建上传会话'}).click();const createdUpload=await session;expect(createdUpload.status()).toBe(201);uploadId=(await createdUpload.json()).id;await expect(attachments.getByText('未上传',{exact:true})).toBeVisible()
   await attachments.getByRole('button',{name:'上传文件内容'}).click();await expect(attachments.getByText('已上传，等待扫描',{exact:true})).toBeVisible()
  })
  await step('Real scanner publishes CLEAN revision and MEMBER freezes attachment through DOM',async()=>{
   const complete=member.waitForResponse(r=>new URL(r.url()).pathname===`${root}/uploads/${uploadId}/complete`&&r.request().method()==='POST');await attachments.getByRole('button',{name:'扫描并发布'}).click();const published=await complete;expect(published.status()).toBe(200);const value=await published.json();expect(value.state).toBe('PUBLISHED');assetId=value.assetId;revisionId=value.revisionId;await expect(attachments.getByText('发布已确认',{exact:true})).toBeVisible()
   await attachments.getByRole('button',{name:'读取已发布版本'}).click();const detail=attachments.getByRole('region',{name:'资产详情'});await expect(detail.getByRole('article',{name:'所选资产修订'})).toContainText('CLEAN');const link=member.waitForResponse(r=>new URL(r.url()).pathname===`${root}/attachments`&&r.request().method()==='POST');await detail.getByRole('button',{name:'关联所选冻结版本'}).click();const linked=await link;expect(linked.status()).toBe(201);const frozen=await linked.json();attachmentId=frozen.id;expect(frozen.assetRevisionId).toBe(revisionId);await expect(attachments.getByRole('list',{name:'已关联附件'})).toContainText('冻结修订 1')
  })
  await step('Reload reconstructs attachment from server and downloads exact original bytes',async()=>{
   await member.reload();await expect(member.getByRole('button',{name:'退出登录'})).toBeVisible();await select(member,tenantId,project);await req.getByRole('button',{name:new RegExp(title)}).click();await expect(attachments.getByRole('list',{name:'已关联附件'})).toContainText(filename)
   expect(await downloaded(member,attachments.getByRole('button',{name:`下载冻结附件 ${filename} 修订 1`}))).toBe(original)
  })
  await step('MEMBER adds asset revision two while old attached revision and downloaded bytes remain one',async()=>{
   await attachments.getByRole('button',{name:`查看资产 ${filename}`}).click();const detail=attachments.getByRole('region',{name:'资产详情'});await expect(detail).toContainText('当前资产修订 1');const version=detail.getByRole('region',{name:'上传资产新版本'})
   await version.getByLabel('选择新版本文件').setInputFiles({name:'r11-revised.txt',mimeType:'text/plain',buffer:Buffer.from(updated)})
   const next=member.waitForResponse(r=>new URL(r.url()).pathname===`${root}/uploads`&&r.request().method()==='POST');await version.getByRole('button',{name:'创建上传会话'}).click();const session=await next;expect(session.status()).toBe(201);expect((await session.json()).assetId).toBe(assetId)
   await version.getByRole('button',{name:'上传文件内容'}).click();await expect(version.getByText('已上传，等待扫描',{exact:true})).toBeVisible();await version.getByRole('button',{name:'扫描并发布'}).click();await expect(version.getByText('发布已确认',{exact:true})).toBeVisible();await version.getByRole('button',{name:'读取已发布版本'}).click();await expect(detail).toContainText('当前资产修订 2')
   expect(await downloaded(member,detail.getByRole('button',{name:'下载所选资产修订'}))).toBe(updated);expect(await downloaded(member,attachments.getByRole('button',{name:`下载冻结附件 ${filename} 修订 1`}))).toBe(original)
   const links=await member.request.get(`${root}/attachments?kind=REQUIREMENT&requirementId=${requirementId}`,{timeout:10_000});expect(links.status()).toBe(200);expect((await links.json()).items.find((l:{id:string})=>l.id===attachmentId)).toMatchObject({assetRevisionId:revisionId,revision:{revisionNumber:1,sha256:createHash('sha256').update(original).digest('hex')}})
  })
  await step('Independent VIEWER reads and downloads frozen version but valid-CSRF mutations are forbidden',async()=>{
   await select(viewer,tenantId,project);const vreq=viewer.locator('section.requirements-panel');await vreq.getByRole('button',{name:new RegExp(title)}).click();const panel=vreq.getByRole('region',{name:'附件与资产'});await expect(panel.getByRole('list',{name:'已关联附件'})).toContainText('冻结修订 1');await expect(panel.getByLabel('选择附件文件')).toHaveCount(0);expect(await downloaded(viewer,panel.getByRole('button',{name:`下载冻结附件 ${filename} 修订 1`}))).toBe(original)
   await panel.getByRole('button',{name:`查看资产 ${filename}`}).click();await expect(panel).toContainText('当前资产修订 2');await expect(panel.getByLabel('选择新版本文件')).toHaveCount(0);await expect(panel.getByRole('button',{name:'关联所选冻结版本'})).toHaveCount(0)
   for(const [path,data]of[[`${root}/uploads`,{filename:'forbidden.txt',size:Buffer.byteLength(original),sha256:createHash('sha256').update(original).digest('hex'),contentType:'text/plain'}],[`${root}/uploads/${uploadId}/complete`,{}],[`${root}/attachments`,{kind:'REQUIREMENT',requirementId,assetId,assetRevisionId:revisionId}]] as const){const denied=await write(viewer,path,data);expect(denied.status()).toBe(403);expect(await denied.json()).toMatchObject({code:'FORBIDDEN'})}
  })
 }catch(e){businessError=e}finally{
  for(const [name,context]of[['member',memberContext],['viewer',viewerContext]] as const){try{await context.close();timeline.push({cleanup:name,failed:false})}catch(e){cleanupError??=e;timeline.push({cleanup:name,failed:true})}}
  try{const directory=resolve(process.cwd(),'../../local-evidence/r03');await mkdir(directory,{recursive:true});await writeFile(resolve(directory,'asset-ui-context-full.json'),JSON.stringify({schemaVersion:1,timeline},null,2))}catch(e){evidenceError=e}
 }
 if(businessError)throw businessError;if(evidenceError)throw evidenceError;if(cleanupError)throw cleanupError
})
