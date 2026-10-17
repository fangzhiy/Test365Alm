import {expect,test} from '@playwright/test'
import type {Page} from '@playwright/test'
import {randomUUID} from 'node:crypto'
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {isOwnedCiRun,seedProjectAccessScope} from './ownedR03'

async function login(page:Page,user:string,password:string){
 await page.goto('/');await page.getByRole('link',{name:'登录 Test365Alm'}).click();await page.locator('#username').fill(user);await page.locator('#password').fill(password);await page.locator('#kc-login').click();await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();const me=await page.request.get('/api/v1/me');expect(me.status()).toBe(200);return (await me.json()).id as string
}
async function write(page:Page,path:string,data:unknown,method='POST',etag?:string){
 const response=await page.request.get('/api/v1/csrf');expect(response.status()).toBe(200);const csrf=await response.json();return page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID(),...(etag?{'If-Match':etag}:{})},data,timeout:10_000})
}
async function select(page:Page,tenant:string,project:string){
 const region=page.getByRole('region',{name:'租户、项目与成员'});await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant);const projects=region.locator('.access-selects select').nth(1);await expect(projects.locator(`option[value="${project}"]`)).toHaveCount(1);await projects.selectOption(project)
}

test('real Keycloak UI admin plans release cycle scope and freezes quality snapshots',async({page,browser})=>{
 test.skip(!isOwnedCiRun(),'Requires current CI-owned identity and database resources');test.setTimeout(180_000)
 const credentials=['R03_TEST_USER','R03_TEST_USER_PASSWORD','R03_VIEWER_USER','R03_VIEWER_PASSWORD'].map(k=>process.env[k]);if(credentials.some(v=>!v))throw new Error('Ephemeral credentials missing')
 const [au,ap,vu,vp]=credentials as string[],viewerContext=await browser.newContext(),viewer=await viewerContext.newPage(),timeline:object[]=[],http:object[]=[]
 let businessError:unknown,cleanupError:unknown,evidenceError:unknown
 page.on('response',r=>{const pathname=new URL(r.url()).pathname;if(pathname.startsWith('/api/v1/'))http.push({pathname,status:r.status()})})
 async function step(name:string,action:()=>Promise<void>){const start=Date.now();let failed=false;try{await test.step(name,action,{timeout:30_000})}catch(e){failed=true;throw e}finally{timeline.push({name,failed,durationMs:Date.now()-start,pathname:new URL(page.url()).pathname,http:http.splice(0)})}}
 try{
  const adminId=await login(page,au,ap),viewerId=await login(viewer,vu,vp),{tenantId,domainId}=seedProjectAccessScope(adminId,viewerId)
  const created=await write(page,'/api/v1/projects',{tenantId,domainId,code:`R10-${randomUUID()}`,name:'R10 release project'});expect(created.status()).toBe(201);const project=(await created.json()).id as string,root=`/api/v1/projects/${project}`
  expect((await write(page,`${root}/members/${viewerId}`,{roles:['PROJECT_VIEWER'],authorizationVersion:0},'PUT')).status()).toBe(200)
  await select(page,tenantId,project);const permissions=await page.request.get(`/api/v1/me/permissions?projectId=${project}`);expect((await permissions.json()).roles).toEqual(['PROJECT_ADMIN'])
  const requirementTitle=`R10 requirement ${randomUUID()}`,caseTitle=`R10 test ${randomUUID()}`,releaseName=`R10 Release ${randomUUID()}`,cycleName='R10 Cycle',setName=`R10 execution ${randomUUID()}`
  const req=page.locator('section.requirements-panel'),cases=page.locator('section.test-cases-panel'),execution=page.locator('section.execution-panel'),planning=page.getByRole('region',{name:'发布与周期工作台'})
  await step('ADMIN creates real requirement test and current trace through DOM',async()=>{
   await req.getByLabel('需求标题').fill(requirementTitle);await req.getByRole('button',{name:'创建需求'}).click();await expect(req).toContainText('需求已创建')
   await cases.getByLabel('测试用例标题').fill(caseTitle);await cases.getByRole('button',{name:'新增步骤'}).click();await cases.getByLabel('步骤 1 操作').fill('Verify release behavior');await cases.getByLabel('步骤 1 预期结果').fill('Expected planning behavior');await cases.getByRole('button',{name:'创建用例'}).click();await expect(cases).toContainText('测试用例已创建')
   await req.getByRole('button',{name:new RegExp(requirementTitle)}).click();const trace=page.getByRole('region',{name:'需求测试追踪'});await trace.getByLabel('关联测试用例').selectOption({label:caseTitle});await trace.getByRole('button',{name:'建立追踪关联'}).click();await expect(trace).toContainText('关联已保存')
  })
  let releaseId='',snapshotAId='',snapshotBId='',frozenA:unknown
  await step('ADMIN creates release cycle and typed frozen scope through DOM',async()=>{
   const form=planning.getByRole('region',{name:'新建发布'});await form.getByLabel('发布名称').fill(releaseName);await form.getByLabel('开始日期').fill('2026-01-01');await form.getByLabel('结束日期').fill('2026-12-31');await form.getByRole('button',{name:'创建发布'}).click();await expect(form).toContainText('发布写入已确认')
   await planning.getByRole('button',{name:new RegExp(`REL-.*${releaseName}`)}).click();const detail=planning.getByRole('region',{name:'发布详情'});await expect(detail.getByRole('heading',{name:new RegExp(`发布详情.*${releaseName}`)})).toBeVisible()
   const cycle=detail.getByRole('region',{name:'新建周期'});await cycle.getByLabel('周期名称').fill(cycleName);await cycle.getByLabel('开始日期').fill('2026-02-01');await cycle.getByLabel('结束日期').fill('2026-02-28');await cycle.getByRole('button',{name:'创建周期'}).click();await expect(cycle).toContainText('周期写入已确认')
   await detail.getByLabel('范围类型').selectOption('REQUIREMENT');await detail.getByRole('button',{name:'加载范围候选'}).click();await detail.getByLabel('范围对象').selectOption({label:requirementTitle});await detail.getByLabel('所属周期').selectOption({label:cycleName});await detail.getByRole('button',{name:'加入发布范围'}).click();await expect(detail).toContainText('范围写入已确认')
   await detail.getByLabel('范围类型').selectOption('TEST_CASE');await detail.getByRole('button',{name:'加载范围候选'}).click();await detail.getByLabel('范围对象').selectOption({label:caseTitle});await detail.getByRole('button',{name:'加入发布范围'}).click();await expect(detail.locator('li').filter({hasText:'TEST_CASE'})).toContainText('冻结修订')
   const releases=await page.request.get(`${root}/releases`);expect(releases.status()).toBe(200);releaseId=(await releases.json()).items.find((r:{name:string})=>r.name===releaseName).id
  })
  await step('Evaluate snapshot A from scope and confirm precise zero execution numerator',async()=>{
   const detail=planning.getByRole('region',{name:'发布详情'});const response=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/releases/${releaseId}/milestones/evaluate`&&r.request().method()==='POST');await detail.getByRole('button',{name:'生成质量快照'}).click();const result=await response;expect(result.status()).toBe(201);frozenA=await result.json();snapshotAId=(frozenA as {id:string}).id
   const snapshot=detail.getByRole('article',{name:'质量快照详情'});await expect(snapshot).toContainText('这是历史冻结结果');await expect(snapshot.getByTestId('metric-coverage')).toContainText('1 / 1');await expect(snapshot.getByTestId('metric-plannedTests')).toContainText('1 / 1');await expect(snapshot.getByTestId('metric-completed')).toContainText('0 / 1');await expect(snapshot.getByTestId('metric-effectivePass')).toContainText('0 / 1')
  })
  const tests=await page.request.get(`${root}/tests?q=${encodeURIComponent(caseTitle)}`);expect(tests.status()).toBe(200);const tc=(await tests.json()).items[0]
  await step('ADMIN uses existing manual execution DOM to finish scoped frozen test PASS',async()=>{
   await execution.getByLabel('测试集名称').fill(setName);await execution.getByRole('button',{name:'创建测试集'}).click();await expect(execution).toContainText('测试集已创建');await execution.getByRole('button',{name:new RegExp(setName)}).click();await execution.getByRole('button',{name:'加载已保存用例'}).click();await execution.getByLabel('已保存用例').selectOption(tc.id);await execution.getByLabel('已保存修订').selectOption(tc.currentRevisionId);await execution.getByRole('button',{name:'加入实例'}).click();await execution.getByRole('button',{name:'启动手工运行'}).click();await execution.getByLabel('步骤 1 实际结果').fill('Release execution confirmed');await execution.getByLabel('步骤 1 结论').selectOption('PASS');await execution.getByRole('button',{name:'保存步骤结果'}).click();await expect(execution).toContainText('步骤结果已保存');await execution.getByRole('button',{name:'完成运行'}).click();await expect(execution).toContainText('已完成 · PASS')
  })
  await step('Evaluate B reflects real PASS while exact historical snapshot A is unchanged',async()=>{
   const detail=planning.getByRole('region',{name:'发布详情'});const response=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/releases/${releaseId}/milestones/evaluate`&&r.request().method()==='POST');await detail.getByRole('button',{name:'生成质量快照'}).click();const result=await response;expect(result.status()).toBe(201);snapshotBId=(await result.json()).id;expect(snapshotBId).not.toBe(snapshotAId)
   await expect(detail.getByTestId('metric-completed')).toContainText('1 / 1');await expect(detail.getByTestId('metric-effectivePass')).toContainText('1 / 1')
   await detail.getByRole('button',{name:new RegExp(`查看快照 ${snapshotAId}`)}).click();await expect(detail.getByTestId('metric-completed')).toContainText('0 / 1');await expect(detail.getByTestId('metric-effectivePass')).toContainText('0 / 1')
   const original=await page.request.get(`${root}/releases/${releaseId}/snapshots/${snapshotAId}`);expect(original.status()).toBe(200);expect(await original.json()).toEqual(frozenA)
   await page.reload();await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();await select(page,tenantId,project);await planning.getByRole('button',{name:new RegExp(releaseName)}).click();await planning.getByRole('button',{name:new RegExp(`查看快照 ${snapshotBId}`)}).click();await expect(planning.getByTestId('metric-effectivePass')).toContainText('1 / 1')
  })
  await step('Independent VIEWER reads cycle scope frozen metrics and valid-CSRF writes are forbidden',async()=>{
   await select(viewer,tenantId,project);const panel=viewer.getByRole('region',{name:'发布与周期工作台'});await panel.getByRole('button',{name:new RegExp(releaseName)}).click();await panel.getByRole('button',{name:new RegExp(`查看快照 ${snapshotAId}`)}).click();await expect(panel.getByTestId('metric-effectivePass')).toContainText('0 / 1');await expect(panel).toContainText(cycleName);await expect(panel).toContainText('冻结修订');await expect(panel.getByRole('button',{name:'创建发布'})).toHaveCount(0);await expect(panel.getByRole('button',{name:'生成质量快照'})).toHaveCount(0);await expect(panel.getByRole('button',{name:'保存发布'})).toHaveCount(0);await expect(panel.getByLabel('发布名称')).toBeDisabled()
   for(const [path,data]of[[`${root}/releases`,{name:'Forbidden',startDate:'2026-01-01',endDate:'2026-12-31'}],[`${root}/releases/${releaseId}/cycles`,{name:'Forbidden',startDate:'2026-02-01',endDate:'2026-02-28'}],[`${root}/releases/${releaseId}/milestones/evaluate`,{}]] as const){const denied=await write(viewer,path,data);expect(denied.status()).toBe(403);expect(await denied.json()).toMatchObject({code:'FORBIDDEN'})}
  })
 }catch(e){businessError=e}finally{
  try{await viewerContext.close();timeline.push({cleanup:'viewer',failed:false})}catch(e){cleanupError=e;timeline.push({cleanup:'viewer',failed:true})}
  try{const directory=resolve(process.cwd(),'../../local-evidence/r03');await mkdir(directory,{recursive:true});await writeFile(resolve(directory,'release-ui-context-full.json'),JSON.stringify({schemaVersion:1,timeline},null,2))}catch(e){evidenceError=e}
 }
 if(businessError)throw businessError;if(evidenceError)throw evidenceError;if(cleanupError)throw cleanupError
})
