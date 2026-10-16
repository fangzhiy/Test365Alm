import { expect,test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdir,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { isOwnedCiRun,seedProjectAccessScope } from './ownedR03'

async function login(page:Page,user:string,password:string){
 await page.goto('/');await page.getByRole('link',{name:'登录 Test365Alm'}).click()
 await page.locator('#username').fill(user);await page.locator('#password').fill(password);await page.locator('#kc-login').click()
 await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();const me=await page.request.get('/api/v1/me');expect(me.status()).toBe(200);return (await me.json()).id as string
}
async function write(page:Page,path:string,data:unknown,method='POST',etag?:string){
 const response=await page.request.get('/api/v1/csrf');expect(response.status()).toBe(200);const csrf=await response.json()
 return page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID(),...(etag?{'If-Match':etag}:{})},data,timeout:10_000})
}
async function select(page:Page,tenant:string,project:string){
 const region=page.getByRole('region',{name:'租户、项目与成员'});await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant)
 const projects=region.locator('.access-selects select').nth(1);await expect(projects.locator(`option[value="${project}"]`)).toHaveCount(1);await projects.selectOption(project)
}

test('real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history',async({page,browser})=>{
 test.skip(!isOwnedCiRun(),'Requires current CI-owned identity and database resources');test.setTimeout(180_000)
 const credentials=['R03_TEST_USER','R03_TEST_USER_PASSWORD','R03_MEMBER_USER','R03_MEMBER_PASSWORD','R03_VIEWER_USER','R03_VIEWER_PASSWORD'].map(k=>process.env[k]);if(credentials.some(v=>!v))throw new Error('Ephemeral credentials missing')
 const [au,ap,mu,mp,vu,vp]=credentials as string[],memberContext=await browser.newContext(),viewerContext=await browser.newContext(),member=await memberContext.newPage(),viewer=await viewerContext.newPage()
 const timeline:object[]=[],http:object[]=[];let businessError:unknown,cleanupError:unknown,evidenceError:unknown
 member.on('response',r=>{const pathname=new URL(r.url()).pathname;if(pathname.startsWith('/api/v1/'))http.push({pathname,status:r.status()})})
 async function step(name:string,action:()=>Promise<void>){const start=Date.now();let failed=false;try{await test.step(name,action,{timeout:30_000})}catch(e){failed=true;throw e}finally{timeline.push({name,failed,durationMs:Date.now()-start,pathname:new URL(member.url()).pathname,http:http.splice(0)})}}
 try{
  const adminId=await login(page,au,ap),memberId=await login(member,mu,mp),viewerId=await login(viewer,vu,vp),{tenantId,domainId}=seedProjectAccessScope(adminId,viewerId,memberId)
  const created=await write(page,'/api/v1/projects',{tenantId,domainId,code:`R09-${randomUUID()}`,name:'R09 defect project'});expect(created.status()).toBe(201);const project=(await created.json()).id as string,root=`/api/v1/projects/${project}`
  for(const [id,role]of[[memberId,'PROJECT_MEMBER'],[viewerId,'PROJECT_VIEWER']])expect((await write(page,`${root}/members/${id}`,{roles:[role],authorizationVersion:0},'PUT')).status()).toBe(200)
  await select(member,tenantId,project);const permission=await member.request.get(`/api/v1/me/permissions?projectId=${project}`);expect((await permission.json()).roles).toEqual(['PROJECT_MEMBER'])
  const req=member.locator('section.requirements-panel'),cases=member.locator('section.test-cases-panel'),execution=member.locator('section.execution-panel'),bugs=member.getByRole('region',{name:'缺陷工作台'})
  const requirementTitle=`R09 requirement ${randomUUID()}`,caseTitle=`R09 test ${randomUUID()}`,setName=`R09 set ${randomUUID()}`,bugTitle=`R09 defect ${randomUUID()}`
  await step('MEMBER creates requirement test and typed trace through DOM',async()=>{
   await req.getByLabel('需求标题').fill(requirementTitle);await req.getByRole('button',{name:'创建需求'}).click();await expect(req).toContainText('需求已创建')
   await cases.getByLabel('测试用例标题').fill(caseTitle);await cases.getByRole('button',{name:'新增步骤'}).click();await cases.getByLabel('步骤 1 操作').fill('Check defect behavior');await cases.getByLabel('步骤 1 预期结果').fill('Expected correct behavior');await cases.getByRole('button',{name:'创建用例'}).click();await expect(cases).toContainText('测试用例已创建')
   await req.getByRole('button',{name:new RegExp(requirementTitle)}).click();const trace=member.getByRole('region',{name:'需求测试追踪'});await trace.getByLabel('关联测试用例').selectOption({label:caseTitle});await trace.getByRole('button',{name:'建立追踪关联'}).click();await expect(trace).toContainText('关联已保存')
  })
  const tcResponse=await member.request.get(`${root}/tests?q=${encodeURIComponent(caseTitle)}`);expect(tcResponse.status()).toBe(200);const tc=(await tcResponse.json()).items[0]
  await step('MEMBER produces real FAIL and creates defect from failed step DOM',async()=>{
   await execution.getByLabel('测试集名称').fill(setName);await execution.getByRole('button',{name:'创建测试集'}).click();await expect(execution).toContainText('测试集已创建');await execution.getByRole('button',{name:new RegExp(setName)}).click()
   await execution.getByRole('button',{name:'加载已保存用例'}).click();await execution.getByLabel('已保存用例').selectOption(tc.id);await execution.getByLabel('已保存修订').selectOption(tc.currentRevisionId);await execution.getByRole('button',{name:'加入实例'}).click();await execution.getByRole('button',{name:'启动手工运行'}).click()
   await execution.getByLabel('步骤 1 实际结果').fill('Original failure remains');await execution.getByLabel('步骤 1 结论').selectOption('FAIL');await execution.getByRole('button',{name:'保存步骤结果'}).click();await expect(execution).toContainText('步骤结果已保存');await execution.getByRole('button',{name:'完成运行'}).click();await expect(execution).toContainText('已完成 · FAIL')
   await execution.getByRole('button',{name:'从失败步骤创建缺陷'}).click();const form=execution.getByRole('region',{name:'从失败步骤创建缺陷'});await form.getByLabel('缺陷标题').fill(bugTitle);await form.getByLabel('缺陷描述').fill('Reproduce original failure');await form.getByRole('button',{name:'创建缺陷'}).click();await expect(form).toContainText('缺陷已创建')
  })
  await step('Open edit IN_PROGRESS RESOLVED and reject closing without PASS',async()=>{
   await bugs.getByRole('button',{name:'刷新缺陷'}).click();await bugs.getByRole('button',{name:new RegExp(bugTitle)}).click();const detail=bugs.getByRole('region',{name:'缺陷详情'});await expect(detail).toContainText('原实际：Original failure remains');await expect(detail).toContainText('关联需求')
   await detail.getByLabel('编辑缺陷描述').fill('Reproduced and investigated');await detail.getByRole('button',{name:'保存缺陷修订'}).click();await expect(detail).toContainText('v2')
   await detail.getByRole('button',{name:'转为 IN_PROGRESS'}).click();await expect(detail).toContainText('v3');await detail.getByLabel('修复/流转说明').fill('Repair applied');await detail.getByRole('button',{name:'转为 RESOLVED'}).click();await expect(detail).toContainText('v4')
   await detail.getByRole('button',{name:'转为 CLOSED'}).click();await expect(detail).toContainText('A successful related retest in this resolution cycle is required')
  })
  await step('Start related retest through defect UI save PASS finish and close',async()=>{
   const detail=bugs.getByRole('region',{name:'缺陷详情'});await detail.getByRole('button',{name:'开始复测'}).click();const attempt=detail.getByRole('region',{name:'缺陷关联尝试'});await expect(attempt).toContainText('RUNNING')
   await attempt.getByLabel('复测步骤 1 实际结果').fill('Repair verified PASS');await attempt.getByLabel('复测步骤 1 结论').selectOption('PASS');await attempt.getByRole('button',{name:'保存复测步骤 1'}).click();await expect(attempt).toContainText('复测步骤已保存');await attempt.getByRole('button',{name:'完成复测'}).click();await expect(attempt).toContainText('FINISHED · PASS')
   await detail.getByRole('button',{name:'刷新缺陷详情'}).click();await expect(detail).toContainText('可用相关 PASS');await detail.getByRole('button',{name:'转为 CLOSED'}).click();await expect(detail).toContainText('CLOSED · v5')
  })
  await step('Real reload preserves source FAIL retest PASS and immutable status history',async()=>{
   await member.reload();await expect(member.getByRole('button',{name:'退出登录'})).toBeVisible();await select(member,tenantId,project);await bugs.getByRole('button',{name:new RegExp(bugTitle)}).click();const detail=bugs.getByRole('region',{name:'缺陷详情'});await expect(detail).toContainText('CLOSED · v5');await expect(detail).toContainText('原实际：Original failure remains')
   await detail.getByRole('button',{name:'查看原失败尝试'}).click();let attempt=detail.getByRole('region',{name:'缺陷关联尝试'});await expect(attempt).toContainText('FINISHED · FAIL');await expect(attempt.getByLabel('复测步骤 1 实际结果')).toHaveValue('Original failure remains')
   await detail.getByRole('button',{name:'打开复测尝试 2 · PASS'}).click();attempt=detail.getByRole('region',{name:'缺陷关联尝试'});await expect(attempt).toContainText('FINISHED · PASS');await expect(attempt.getByLabel('复测步骤 1 实际结果')).toHaveValue('Repair verified PASS')
   await detail.getByRole('button',{name:'修订 1 · OPEN',exact:true}).click();await expect(detail.getByRole('article',{name:'缺陷历史快照'})).toContainText('历史 v1 · OPEN');await expect(detail.getByRole('article',{name:'缺陷历史快照'})).toContainText('Reproduce original failure')
   await detail.getByRole('button',{name:'修订 4 · RESOLVED',exact:true}).click();await expect(detail.getByRole('article',{name:'缺陷历史快照'})).toContainText('Repair applied')
  })
  await step('Independent VIEWER reads evidence and valid-CSRF direct writes remain forbidden',async()=>{
   await select(viewer,tenantId,project);const panel=viewer.getByRole('region',{name:'缺陷工作台'});await panel.getByRole('button',{name:new RegExp(bugTitle)}).click();const detail=panel.getByRole('region',{name:'缺陷详情'});await expect(detail).toContainText('原实际：Original failure remains');await expect(panel.getByRole('button',{name:'创建缺陷'})).toHaveCount(0);await expect(detail.getByRole('button',{name:'保存缺陷修订'})).toHaveCount(0);await expect(detail.getByLabel('编辑缺陷标题')).toBeDisabled()
   await detail.getByRole('button',{name:'打开复测尝试 2 · PASS'}).click();await expect(detail.getByRole('region',{name:'缺陷关联尝试'})).toContainText('FINISHED · PASS');await expect(detail.getByRole('button',{name:'开始复测'})).toHaveCount(0)
   const list=await viewer.request.get(`${root}/defects?q=${encodeURIComponent(bugTitle)}`);expect(list.status()).toBe(200);const defect=(await list.json()).items[0],content={title:'Forbidden',description:'',severity:'HIGH',priority:'HIGH'}
   for(const [path,body,method]of[[`${root}/defects`,content,'POST'],[`${root}/defects/${defect.id}`,content,'PATCH'],[`${root}/defects/${defect.id}/transitions`,{status:'REOPENED',reason:'forbidden'},'POST']] as const){const denied=await write(viewer,path,body,method,`"${defect.rowVersion}"`);expect(denied.status()).toBe(403);expect(await denied.json()).toMatchObject({code:'FORBIDDEN'})}
  })
 }catch(e){businessError=e}finally{
  for(const [name,context]of[['member',memberContext],['viewer',viewerContext]] as const)try{await context.close();timeline.push({cleanup:name,failed:false})}catch(e){cleanupError??=e;timeline.push({cleanup:name,failed:true})}
  try{const directory=resolve(process.cwd(),'../../local-evidence/r03');await mkdir(directory,{recursive:true});await writeFile(resolve(directory,'defect-ui-context-full.json'),JSON.stringify({schemaVersion:1,timeline},null,2))}catch(e){evidenceError=e}
 }
 if(businessError)throw businessError;if(evidenceError)throw evidenceError;if(cleanupError)throw cleanupError
})
