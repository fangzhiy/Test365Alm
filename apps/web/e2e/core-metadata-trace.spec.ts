import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { isOwnedCiRun, seedProjectAccessScope } from './ownedR03'

async function login(page: Page, user: string, password: string) {
  await page.goto('/'); await page.getByRole('link',{name:'登录 Test365Alm'}).click()
  await page.locator('#username').fill(user); await page.locator('#password').fill(password); await page.locator('#kc-login').click()
  await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible()
  const me=await page.request.get('/api/v1/me');expect(me.status()).toBe(200);return (await me.json()).id as string
}
async function write(page: Page,path: string,data: unknown,method='POST') {
  const csrfResponse=await page.request.get('/api/v1/csrf');expect(csrfResponse.status()).toBe(200)
  const csrf=await csrfResponse.json();return page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID()},data})
}
async function select(page: Page,tenant: string,project: string) {
  const region=page.getByRole('region',{name:'租户、项目与成员'})
  await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant)
  const input=region.locator('.access-selects select').nth(1);await expect(input.locator(`option[value="${project}"]`)).toHaveCount(1);await input.selectOption(project)
}
test('real Keycloak UI field metadata validates and member trace coverage changes only after PASS',async({page,browser})=>{
  test.skip(!isOwnedCiRun(),'Requires the current CI-owned identity and database resources')
  test.setTimeout(180_000)
  const credentials=['R03_TEST_USER','R03_TEST_USER_PASSWORD','R03_MEMBER_USER','R03_MEMBER_PASSWORD','R03_VIEWER_USER','R03_VIEWER_PASSWORD'].map(k=>process.env[k])
  if(credentials.some(v=>!v))throw new Error('Owned ephemeral test credentials are required')
  const [au,ap,mu,mp,vu,vp]=credentials as string[]
  const memberContext=await browser.newContext(),viewerContext=await browser.newContext()
  const member=await memberContext.newPage(),viewer=await viewerContext.newPage()
  let businessError: unknown,cleanupError: unknown
  try {
    const adminId=await login(page,au,ap),memberId=await login(member,mu,mp),viewerId=await login(viewer,vu,vp)
    const {tenantId,domainId}=seedProjectAccessScope(adminId,viewerId,memberId)
    const projectResponse=await write(page,'/api/v1/projects',{tenantId,domainId,code:`R08-${randomUUID()}`,name:'R08 core project'});expect(projectResponse.status()).toBe(201)
    const project=(await projectResponse.json()).id as string,root=`/api/v1/projects/${project}`
    for(const [id,role]of[[memberId,'PROJECT_MEMBER'],[viewerId,'PROJECT_VIEWER']])expect((await write(page,`${root}/members/${id}`,{roles:[role],authorizationVersion:0},'PUT')).status()).toBe(200)
    await select(page,tenantId,project)
    await test.step('Admin creates persisted field and server rejects invalid value',async()=>{
      const fields=page.getByRole('region',{name:'字段元数据'})
      await fields.getByLabel('字段键').fill('estimate');await fields.getByLabel('字段类型').selectOption('NUMBER')
      await fields.getByLabel('数字精度').fill('5');await fields.getByLabel('数字小数位').fill('2');await fields.getByLabel('数字上界').fill('100.00')
      await fields.getByRole('button',{name:'创建字段',exact:true}).click();await expect(fields).toContainText('字段修订已保存')
      await fields.getByLabel('待验证值').fill('12.34');await fields.getByRole('button',{name:'验证值'}).click();await expect(fields).toContainText('值验证通过')
      await fields.getByLabel('待验证值').fill('100.01');await fields.getByRole('button',{name:'验证值'}).click();await expect(fields).toContainText('Above maximum')
    },{timeout:30_000})
    await select(member,tenantId,project)
    const permissions=await member.request.get(`/api/v1/me/permissions?projectId=${project}`);expect((await permissions.json()).roles).toEqual(['PROJECT_MEMBER'])
    const req=member.locator('section.requirements-panel'),cases=member.locator('section.test-cases-panel'),title=`R08 requirement ${randomUUID()}`,caseTitle=`R08 test ${randomUUID()}`
    await test.step('Ordinary member creates requirement and manual test through DOM',async()=>{
      await req.getByLabel('需求标题').fill(title);await req.getByLabel('需求正文').fill('R08 text');await req.getByRole('button',{name:'创建需求'}).click();await expect(req).toContainText('需求已创建')
      await cases.getByLabel('测试用例标题').fill(caseTitle);await cases.getByRole('button',{name:'新增步骤'}).click();await cases.getByLabel('步骤 1 操作').fill('Check');await cases.getByLabel('步骤 1 预期结果').fill('Correct');await cases.getByRole('button',{name:'创建用例'}).click();await expect(cases).toContainText('测试用例已创建')
      await req.getByRole('button',{name:new RegExp(title)}).click()
    },{timeout:30_000})
    const trace=member.getByRole('region',{name:'需求测试追踪'})
    await test.step('Persist link and prove unexecuted association is not PASS',async()=>{
      await trace.getByLabel('关联测试用例').selectOption({label:caseTitle});await trace.getByRole('button',{name:'建立追踪关联'}).click();await expect(trace).toContainText('关联已保存')
      await trace.getByRole('button',{name:'刷新覆盖统计'}).click();await expect(trace).toContainText('PASS 覆盖 0/1')
    },{timeout:30_000})
    const testResponse=await member.request.get(`${root}/tests?q=${encodeURIComponent(caseTitle)}`);expect(testResponse.status()).toBe(200);const tc=(await testResponse.json()).items[0]
    const execution=member.locator('section.execution-panel'),set=`R08 set ${randomUUID()}`
    await test.step('Complete actual manual execution and observe synchronous coverage',async()=>{
      await execution.getByLabel('测试集名称').fill(set);await execution.getByRole('button',{name:'创建测试集'}).click();await expect(execution).toContainText('测试集已创建')
      await execution.getByRole('button',{name:new RegExp(set)}).click();await execution.getByRole('button',{name:'加载已保存用例'}).click();await execution.getByLabel('已保存用例').selectOption(tc.id);await execution.getByLabel('已保存修订').selectOption(tc.currentRevisionId)
      await execution.getByRole('button',{name:'加入实例'}).click();await execution.getByRole('button',{name:'启动手工运行'}).click()
      await execution.getByLabel('步骤 1 实际结果').fill('Confirmed correct');await execution.getByLabel('步骤 1 结论').selectOption('PASS');await execution.getByRole('button',{name:'保存步骤结果'}).click();await expect(execution).toContainText('步骤结果已保存')
      await execution.getByRole('button',{name:'完成运行'}).click();await expect(execution).toContainText('已完成 · PASS')
      await trace.getByRole('button',{name:'刷新覆盖统计'}).click();await expect(trace).toContainText('PASS 覆盖 1/1')
    },{timeout:30_000})
    await select(viewer,tenantId,project)
    await viewer.locator('section.requirements-panel').getByRole('button',{name:new RegExp(title)}).click()
    await expect(viewer.getByRole('region',{name:'需求测试追踪'})).toContainText('PASS 覆盖 1/1')
    await expect(viewer.getByRole('button',{name:'建立追踪关联'})).toHaveCount(0);await expect(viewer.getByRole('button',{name:'创建字段',exact:true})).toHaveCount(0)
    expect((await write(viewer,`${root}/field-definitions`,{key:'forbidden',type:'TEXT',required:false,maxLength:10})).status()).toBe(403)
    const linksResponse=await member.request.get(`${root}/trace-links?requirementId=${(await (await member.request.get(`${root}/requirements?q=${encodeURIComponent(title)}`)).json()).items[0].id}`)
    expect(linksResponse.status()).toBe(200);const link=(await linksResponse.json()).items[0]
    expect((await write(viewer,`${root}/trace-links`,{requirementId:link.requirementId,testCaseId:link.testCaseId})).status()).toBe(403)
    await trace.getByRole('button',{name:'失效关联'}).click();await expect(trace).toContainText('INACTIVE');await trace.getByRole('button',{name:'刷新覆盖统计'}).click();await expect(trace).toContainText('PASS 覆盖 0/0')
  } catch(error){businessError=error}
  finally {
    for(const context of[memberContext,viewerContext])try{await context.close()}catch(error){cleanupError??=error}
  }
  if(businessError)throw businessError
  if(cleanupError)throw cleanupError
})
