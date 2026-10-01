import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { isOwnedCiRun, seedProjectAccessScope } from './ownedR03'

type Page = import('@playwright/test').Page

async function login(page: Page, username: string, password: string, displayName: string): Promise<string> {
  await page.goto('/')
  await page.getByRole('link', { name: '登录 Test365Alm' }).click()
  const usernameInput = page.locator('#username')
  await expect(usernameInput).toBeVisible({ timeout: 15_000 })
  await usernameInput.fill(username)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await expect(page.getByText(displayName)).toBeVisible()
  const response = await page.request.get('/api/v1/me')
  expect(response.status()).toBe(200)
  const body = await response.json() as { id: string }
  expect(body.id).toMatch(/^[0-9a-f-]{36}$/)
  return body.id
}

async function write(page: Page, method: string, path: string, data?: unknown): Promise<import('@playwright/test').APIResponse> {
  const csrfResponse = await page.request.get('/api/v1/csrf')
  expect(csrfResponse.status()).toBe(200)
  const csrf = await csrfResponse.json() as { headerName: string, token: string }
  return page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() },
    data,
  })
}

test('real Keycloak dual-user project access grants viewer read, rejects write, and revokes the live session', async ({ page, browser }) => {
  test.skip(!isOwnedCiRun(), 'destructive project access flow requires the current CI-owned R03 stack')
  test.setTimeout(90_000)
  const adminUser = process.env.R03_TEST_USER
  const adminPassword = process.env.R03_TEST_USER_PASSWORD
  const viewerUser = process.env.R03_VIEWER_USER
  const viewerPassword = process.env.R03_VIEWER_PASSWORD
  if (!adminUser || !adminPassword || !viewerUser || !viewerPassword) {
    throw new Error('Both isolated R03 test credentials are required')
  }

  const adminId = await login(page, adminUser, adminPassword, 'R03 Tester')
  const viewerContext = await browser.newContext()
  try {
    const viewerPage = await viewerContext.newPage()
    const viewerId = await login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer')
    const { tenantId, domainId } = seedProjectAccessScope(adminId, viewerId)

    const projectCode = `R03-E2E-${Date.now()}`
    const create = await write(page, 'POST', '/api/v1/projects', {
      tenantId,
      domainId,
      code: projectCode,
      name: 'R03 dual-user project',
    })
    expect(create.status()).toBe(201)
    const project = await create.json() as { id: string, tenantId: string, domainId: string }
    expect(project).toMatchObject({ tenantId, domainId })

    const grant = await write(page, 'PUT', `/api/v1/projects/${project.id}/members/${viewerId}`, {
      roles: ['PROJECT_VIEWER'],
      authorizationVersion: 0,
    })
    expect(grant.status()).toBe(200)
    const granted = await grant.json() as { principalId: string, roles: string[], state: string, authorizationVersion: number }
    expect(granted).toMatchObject({ principalId: viewerId, roles: ['PROJECT_VIEWER'], state: 'ACTIVE' })
    expect(granted.authorizationVersion).toBeGreaterThanOrEqual(1)

    const read = await viewerPage.request.get(`/api/v1/projects/${project.id}`)
    expect(read.status()).toBe(200)
    expect(await read.json()).toMatchObject({ id: project.id, code: projectCode })
    const permissions = await viewerPage.request.get(`/api/v1/me/permissions?projectId=${project.id}`)
    expect(permissions.status()).toBe(200)
    expect(await permissions.json()).toMatchObject({ projectId: project.id, roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })

    const writeAttempt = await write(viewerPage, 'PATCH', `/api/v1/projects/${project.id}`, {
      name: 'viewer must not update',
      rowVersion: 0,
    })
    expect(writeAttempt.status()).toBe(403)
    expect(await writeAttempt.json()).toMatchObject({ code: 'FORBIDDEN' })

    const revoke = await write(page, 'DELETE', `/api/v1/projects/${project.id}/members/${viewerId}`, {
      authorizationVersion: granted.authorizationVersion,
    })
    expect(revoke.status()).toBe(200)
    expect(await revoke.json()).toMatchObject({ principalId: viewerId, state: 'REVOKED' })

    const staleSession = await viewerPage.request.get(`/api/v1/me/permissions?projectId=${project.id}`)
    expect([403, 404]).toContain(staleSession.status())
    expect(await staleSession.json()).toMatchObject({ code: expect.stringMatching(/^(FORBIDDEN|NOT_FOUND)$/) })
  } finally {
    await viewerContext.close()
  }
})

test('real Keycloak UI project flow shows scoped viewer controls and clears revoked access', async ({ page, browser }) => {
  test.skip(!isOwnedCiRun(), 'destructive project access flow requires the current CI-owned R03 stack')
  test.setTimeout(120_000)
  const adminUser = process.env.R03_TEST_USER
  const adminPassword = process.env.R03_TEST_USER_PASSWORD
  const viewerUser = process.env.R03_VIEWER_USER
  const viewerPassword = process.env.R03_VIEWER_PASSWORD
  if (!adminUser || !adminPassword || !viewerUser || !viewerPassword) throw new Error('Both isolated R03 test credentials are required')

  const adminId = await login(page, adminUser, adminPassword, 'R03 Tester')
  const viewerContext = await browser.newContext()
  try {
    const viewerPage = await viewerContext.newPage()
    const viewerId = await login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer')
    const { tenantId } = seedProjectAccessScope(adminId, viewerId)
    const projectCode = `R03-UI-${Date.now()}`

    await page.getByRole('button', { name: '加载访问范围' }).click()
    await page.getByRole('region', { name: '租户、项目与成员' }).getByRole('combobox').first().selectOption(tenantId)
    await expect(page.getByLabel('项目域')).toBeVisible()
    await page.getByLabel('项目代码').fill(projectCode)
    await page.getByLabel('显示名称').fill('R03 UI project')
    await page.getByRole('button', { name: '创建项目' }).click()
    await expect(page.getByText(`R03 UI project（${projectCode}）`)).toBeVisible()
    await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 UI project')

    // Exercise the browser edit path, then force a concurrent update so the
    // stale row-version response is observed through the real UI. The direct
    // request is only the second writer used to create the conflict; the
    // operation under test is the DOM form submission below.
    const uiProjectOption = page.getByLabel('项目').locator('option').filter({ hasText: `R03 UI project（${projectCode}）` })
    const uiProjectId = await uiProjectOption.getAttribute('value')
    expect(uiProjectId).toBeTruthy()
    await page.getByLabel('项目名称').fill('R03 UI edited project')
    await page.getByRole('button', { name: '保存项目' }).click()
    await expect(page.getByText('项目已更新')).toBeVisible()
    await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 UI edited project')
    const currentProjectResponse = await page.request.get(`/api/v1/projects/${uiProjectId}`)
    expect(currentProjectResponse.status()).toBe(200)
    const currentProject = await currentProjectResponse.json() as { rowVersion: number }
    const concurrentUpdate = await write(page, 'PATCH', `/api/v1/projects/${uiProjectId}`, {
      name: 'R03 concurrent update',
      rowVersion: currentProject.rowVersion,
    })
    expect(concurrentUpdate.status()).toBe(200)
    await page.getByLabel('项目名称').fill('R03 stale browser edit')
    await page.getByRole('button', { name: '保存项目' }).click()
    await expect(page.getByText(/项目已被其他请求更新|版本冲突|changed|stale|version|modified|concurrent/i)).toBeVisible()
    await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')

    const candidate = page.getByLabel('同租户候选主体')
    await candidate.selectOption(viewerId)
    await page.getByLabel('固定角色').selectOption('PROJECT_VIEWER')
    await page.getByRole('button', { name: '保存成员' }).click()
    await expect(page.getByText('成员授权已保存')).toBeVisible()

    const ungrantedCode = `${projectCode}-PRIVATE`
    await page.getByLabel('项目代码').fill(ungrantedCode)
    await page.getByLabel('显示名称').fill('R03 UI private project')
    await page.getByRole('button', { name: '创建项目' }).click()
    await expect(page.getByText(`R03 UI private project（${ungrantedCode}）`)).toBeVisible()
    const grantedOption = page.getByLabel('项目').locator('option').filter({ hasText: `R03 concurrent update（${projectCode}）` })
    await page.getByLabel('项目').selectOption((await grantedOption.getAttribute('value')) ?? '')
    await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')

    await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
    // Select the tenant returned by the isolated seed explicitly.  A user may
    // belong to more than one tenant and the first list entry is not a stable
    // project-access scope for this scenario.
    await viewerPage.getByRole('region', { name: '租户、项目与成员' }).getByRole('combobox').first().selectOption(tenantId)
    await expect(viewerPage.getByLabel('项目')).toContainText(`R03 concurrent update`)
    await expect(viewerPage.getByLabel('项目')).not.toContainText('R03 UI private project')
    await expect(viewerPage.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')
    await expect(viewerPage.getByRole('button', { name: '创建项目' })).not.toBeVisible()
    await expect(viewerPage.getByRole('button', { name: '保存成员' })).not.toBeVisible()

    // The same tenant can contain projects the viewer is not a member of.
    // The UI must only render the scoped project list, not the tenant-wide set.
    const revokeRow = page.locator('.member-row').filter({ hasText: 'R03 Viewer' })
    await expect(revokeRow).toBeVisible()
    await revokeRow.getByRole('button', { name: '撤销访问' }).click()
    await expect(page.getByText('R03 Viewer 已撤销项目访问')).toBeVisible()

    await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
    await viewerPage.getByRole('region', { name: '租户、项目与成员' }).getByRole('combobox').first().selectOption(tenantId)
    await expect(viewerPage.getByLabel('项目')).toHaveValue('')
    await expect(viewerPage.getByRole('region', { name: '项目详情' })).not.toBeVisible()
    await viewerPage.getByRole('button', { name: '退出登录' }).click()
    await expect(viewerPage.getByText('未登录或会话已过期')).toBeVisible()
    await expect(viewerPage.getByText('登录后加载你有权查看的租户和项目。')).toBeVisible()
  } finally {
    await viewerContext.close()
  }
})
