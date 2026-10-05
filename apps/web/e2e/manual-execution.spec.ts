import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { isOwnedCiRun, seedProjectAccessScope } from './ownedR03'

type Page = import('@playwright/test').Page

async function login(page: Page, username: string, password: string, displayName: string): Promise<string> {
  await page.goto('/')
  await page.getByRole('link', { name: '登录 Test365Alm' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await expect(page.getByText(displayName)).toBeVisible()
  const response = await page.request.get('/api/v1/me')
  expect(response.status()).toBe(200)
  return (await response.json() as { id: string }).id
}

async function write(page: Page, method: string, path: string, data: unknown): Promise<import('@playwright/test').APIResponse> {
  const csrfResponse = await page.request.get('/api/v1/csrf')
  expect(csrfResponse.status()).toBe(200)
  const csrf = await csrfResponse.json() as { headerName: string, token: string }
  return page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() },
    data,
  })
}

async function selectProject(page: Page, tenantId: string, projectId: string): Promise<void> {
  const region = page.getByRole('region', { name: '租户、项目与成员' })
  await region.getByRole('button', { name: '加载访问范围' }).click()
  await region.getByRole('combobox').first().selectOption(tenantId)
  const project = region.locator('.access-selects select').nth(1)
  await expect(project.locator(`option[value="${projectId}"]`)).toHaveCount(1)
  await project.selectOption(projectId)
}

test('real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run', async ({ page, browser }) => {
  test.skip(!isOwnedCiRun(), 'destructive M09 page flow requires the current CI-owned R03 stack')
  test.setTimeout(180_000)
  const adminUser = process.env.R03_TEST_USER
  const adminPassword = process.env.R03_TEST_USER_PASSWORD
  const memberUser = process.env.R03_MEMBER_USER
  const memberPassword = process.env.R03_MEMBER_PASSWORD
  const viewerUser = process.env.R03_VIEWER_USER
  const viewerPassword = process.env.R03_VIEWER_PASSWORD
  if (!adminUser || !adminPassword || !memberUser || !memberPassword || !viewerUser || !viewerPassword) {
    throw new Error('Isolated R03 admin, member and viewer credentials are required')
  }

  const adminId = await login(page, adminUser, adminPassword, 'R03 Tester')
  const memberContext = await browser.newContext()
  const memberPage = await memberContext.newPage()
  const viewerContext = await browser.newContext()
  const viewerPage = await viewerContext.newPage()
  try {
    const memberId = await login(memberPage, memberUser, memberPassword, 'R03 Member')
    const viewerId = await login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer')
    const { tenantId, domainId } = seedProjectAccessScope(adminId, viewerId, memberId)
    const projectCode = `R06-M09-${Date.now()}`
    const createdProject = await write(page, 'POST', '/api/v1/projects', {
      tenantId, domainId, code: projectCode, name: 'M09 execution project',
    })
    expect(createdProject.status()).toBe(201)
    const projectId = (await createdProject.json() as { id: string }).id
    const memberGrant = await write(page, 'PUT', `/api/v1/projects/${projectId}/members/${memberId}`, {
      roles: ['PROJECT_MEMBER'], authorizationVersion: 0,
    })
    expect(memberGrant.status()).toBe(200)
    const viewerGrant = await write(page, 'PUT', `/api/v1/projects/${projectId}/members/${viewerId}`, {
      roles: ['PROJECT_VIEWER'], authorizationVersion: 0,
    })
    expect(viewerGrant.status()).toBe(200)

    await selectProject(memberPage, tenantId, projectId)
    const permissions = await memberPage.request.get(`/api/v1/me/permissions?projectId=${projectId}`)
    expect(permissions.status()).toBe(200)
    expect(await permissions.json()).toMatchObject({ roles: ['PROJECT_MEMBER'], permissions: expect.arrayContaining(['test:read', 'test:create', 'test:update', 'test:run']) })

    const testCases = memberPage.locator('section.test-cases-panel')
    const title = `M09 manual ${Date.now()}`
    await testCases.getByLabel('测试用例标题').fill(title)
    await testCases.getByLabel('测试用例说明').fill('Two-step execution fixture')
    await testCases.getByRole('button', { name: '新增步骤' }).click()
    await testCases.getByLabel('步骤 1 操作').fill('Open the login page')
    await testCases.getByLabel('步骤 1 预期结果').fill('Login form is visible')
    await testCases.getByRole('button', { name: '新增步骤' }).click()
    await testCases.getByLabel('步骤 2 操作').fill('Submit credentials')
    await testCases.getByLabel('步骤 2 预期结果').fill('Workbench is visible')
    await testCases.getByRole('button', { name: '创建用例' }).click()
    await expect(testCases).toContainText('测试用例已创建')
    const listed = await memberPage.request.get(`/api/v1/projects/${projectId}/tests?limit=100`)
    expect(listed.status()).toBe(200)
    const testItem = ((await listed.json()) as { items: Array<{ id: string, title: string, currentRevisionId: string }> }).items.find((item) => item.title === title)
    expect(testItem).toBeTruthy()

    const execution = memberPage.locator('section.execution-panel')
    const setName = `M09 set ${Date.now()}`
    await execution.getByLabel('测试集名称').fill(setName)
    await execution.getByLabel('测试集说明').fill('Two-step execution')
    await execution.getByRole('button', { name: '创建测试集' }).click()
    await expect(execution).toContainText('测试集已创建')
    await execution.getByRole('button', { name: new RegExp(setName) }).click()
    await execution.getByRole('button', { name: '加载已保存用例' }).click()
    await execution.getByLabel('已保存用例').selectOption(testItem!.id)
    await execution.getByLabel('已保存修订').selectOption(testItem!.currentRevisionId)
    await execution.getByRole('button', { name: '加入实例' }).click()
    await expect(execution).toContainText(title)
    await execution.getByRole('button', { name: '启动手工运行' }).click()
    await expect(execution).toContainText('运行中')

    await execution.getByLabel('步骤 1 实际结果').fill('Login form is visible')
    await execution.getByLabel('步骤 1 结论').selectOption('PASS')
    await execution.getByRole('button', { name: '保存步骤结果' }).first().click()
    await expect(execution).toContainText('步骤结果已保存')
    await execution.getByLabel('步骤 2 实际结果').fill('Server rejected credentials')
    await execution.getByLabel('步骤 2 结论').selectOption('FAIL')
    await execution.getByRole('button', { name: '保存步骤结果' }).last().click()
    await expect(execution).toContainText('步骤结果已保存')
    await execution.getByRole('button', { name: '暂停' }).click()
    await expect(execution).toContainText('已暂停')
    await execution.getByRole('button', { name: '继续' }).click()
    await expect(execution).toContainText('运行中')
    await execution.getByRole('button', { name: '刷新测试集' }).click()
    await expect(execution.getByRole('button', { name: '完成运行' })).toBeVisible()
    await execution.getByRole('button', { name: '完成运行' }).click()
    await expect(execution).toContainText('已完成 · FAIL')
    await execution.getByRole('button', { name: '重新运行' }).click()
    await expect(execution).toContainText('运行详情 · 尝试 2')
    await execution.getByLabel('步骤 1 实际结果').fill('Login form is visible again')
    await execution.getByLabel('步骤 1 结论').selectOption('PASS')
    await execution.getByRole('button', { name: '保存步骤结果' }).first().click()
    await execution.getByLabel('步骤 2 实际结果').fill('Workbench is visible')
    await execution.getByLabel('步骤 2 结论').selectOption('PASS')
    await execution.getByRole('button', { name: '保存步骤结果' }).last().click()
    await execution.getByRole('button', { name: '完成运行' }).click()
    await expect(execution).toContainText('已完成 · PASS')
    await expect(execution.getByRole('button', { name: '尝试 1 · FAIL' })).toBeVisible()
    await expect(execution.getByRole('button', { name: '尝试 2 · PASS' })).toBeVisible()

    await selectProject(viewerPage, tenantId, projectId)
    const viewerExecution = viewerPage.locator('section.execution-panel')
    await expect(viewerExecution).toContainText(setName)
    await expect(viewerExecution.getByRole('button', { name: '启动手工运行' })).not.toBeVisible()
    const denied = await write(viewerPage, 'POST', `/api/v1/projects/${projectId}/test-sets`, { name: 'viewer forbidden', description: '' })
    expect(denied.status()).toBe(403)
    await expect(denied.json()).resolves.toMatchObject({ code: 'FORBIDDEN' })
  } finally {
    await memberContext.close()
    await viewerContext.close()
  }
})


