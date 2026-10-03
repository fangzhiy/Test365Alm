import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { isOwnedCiRun, seedProjectAccessScope } from './ownedR03'

type Page = import('@playwright/test').Page
type APIResponse = import('@playwright/test').APIResponse

type UiStep = {
  step: string
  startedAt: string
  durationMs: number
  pathname: string
  httpPaths?: string[]
  status?: number | number[]
  panelState?: string
  tenantId?: string
  projectId?: string
  refreshButton?: { label: string, disabled: boolean }
  errorSummary?: string
  consoleErrors?: string[]
  pageErrors?: string[]
  error?: string
}

type UiDiagnosticsDocument = {
  schemaVersion: 1
  test: string
  createdAt: string
  events: UiStep[]
  businessError?: string
  cleanupError?: string
  outputError?: string
}

function redactError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  return message
    .replace(/https?:\/\/[^\s)]+/gi, '[url]')
    .replace(/(authorization|cookie|password|token|code)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
    .slice(0, 500)
}

class UiDiagnostics {
  private static readonly snapshotTimeoutMs = 1_000
  private readonly events: UiStep[] = []
  private statuses: number[] = []
  private httpPaths: string[] = []

  recordStatus(status: number): void {
    if (Number.isInteger(status) && status >= 100 && status <= 599) this.statuses.push(status)
  }

  async step<T>(page: Page, step: string, action: () => Promise<T>): Promise<T> {
    const started = Date.now()
    this.statuses = []
    this.httpPaths = []
    let failure: string | undefined
    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    const responseListener = (response: import('@playwright/test').Response): void => {
      this.recordStatus(response.status())
      try {
        const path = new URL(response.url()).pathname
        if (path.startsWith('/api/v1/') && !this.httpPaths.includes(path)) this.httpPaths.push(path)
      } catch { /* response URL was not a valid URL */ }
    }
    const consoleListener = (message: import('@playwright/test').ConsoleMessage): void => {
      if ((message.type() === 'error' || message.type() === 'warning') && consoleErrors.length < 20) consoleErrors.push(redactError(message.text()))
    }
    const pageErrorListener = (error: Error): void => {
      if (pageErrors.length < 20) pageErrors.push(redactError(error))
    }
    page.on('response', responseListener)
    page.on('console', consoleListener)
    page.on('pageerror', pageErrorListener)
    try {
      return await test.step(step, action, { timeout: 30_000 })
    } catch (error) {
      failure = redactError(error)
      throw error
    } finally {
      page.off('response', responseListener)
      page.off('console', consoleListener)
      page.off('pageerror', pageErrorListener)
      this.events.push({
        step,
        startedAt: new Date(started).toISOString(),
        durationMs: Date.now() - started,
        ...await this.snapshot(page),
        ...(this.httpPaths.length > 0 ? { httpPaths: this.httpPaths } : {}),
        ...(consoleErrors.length > 0 ? { consoleErrors } : {}),
        ...(pageErrors.length > 0 ? { pageErrors } : {}),
        ...(this.statuses.length === 1 ? { status: this.statuses[0] } : this.statuses.length > 1 ? { status: this.statuses } : {}),
        ...(failure ? { error: failure } : {}),
      })
    }
  }

  async write(testName: string, businessError?: unknown, cleanupError?: unknown, artifactPrefix = 'project-ui-context'): Promise<void> {
    const document: UiDiagnosticsDocument = {
      schemaVersion: 1,
      test: testName,
      createdAt: new Date().toISOString(),
      events: this.events,
      ...(businessError ? { businessError: redactError(businessError) } : {}),
      ...(cleanupError ? { cleanupError: redactError(cleanupError) } : {}),
    }
    const label = (process.env.R03_BROWSER_RUN_LABEL || 'full').replace(/[^a-zA-Z0-9_-]/g, '_')
    const safePrefix = artifactPrefix.replace(/[^a-zA-Z0-9_-]/g, '_')
    const output = resolve(process.cwd(), `../../local-evidence/r03/${safePrefix}-${label}.json`)
    try {
      await mkdir(resolve(output, '..'), { recursive: true })
      await writeFile(output, `${JSON.stringify(document, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
    } catch (error) {
      // Diagnostics must never replace the original assertion failure. Keep a
      // bounded, redacted value in the test result if the artifact is unavailable.
      document.outputError = redactError(error)
      if (businessError) return
      throw error
    }
  }

  private async snapshot(page: Page): Promise<Omit<UiStep, 'step' | 'startedAt' | 'durationMs' | 'status' | 'error' | 'httpPaths'>> {
    let pathname = '[unavailable]'
    try { pathname = new URL(page.url()).pathname } catch { /* page may be closing */ }
    const tenantId = await this.inputValue(page, '租户')
    const projectId = await this.inputValue(page, '项目')
    const refreshButton = await page.getByRole('button', { name: /加载访问范围|加载中/ }).first().evaluate((element) => ({
      label: element.textContent?.trim() || '',
      disabled: (element as HTMLButtonElement).disabled,
    }), undefined, { timeout: UiDiagnostics.snapshotTimeoutMs }).catch(() => undefined)
    const alertText = await page.getByRole('alert').first().textContent({ timeout: UiDiagnostics.snapshotTimeoutMs }).catch(() => null)
    const panelState = refreshButton?.label === '加载中…' ? 'loading' : alertText ? 'error' : refreshButton ? 'ready' : 'unknown'
    return { pathname, panelState, tenantId, projectId, refreshButton, ...(alertText ? { errorSummary: redactError(alertText) } : {}) }
  }

  private async inputValue(page: Page, label: string): Promise<string | undefined> {
    return page.getByLabel(label).first().inputValue({ timeout: UiDiagnostics.snapshotTimeoutMs }).catch(() => undefined)
  }
}

async function login(page: Page, username: string, password: string, displayName: string, diagnostics?: UiDiagnostics): Promise<string> {
  await page.goto('/')
  await page.getByRole('link', { name: '登录 Test365Alm' }).click()
  const usernameInput = page.locator('#username')
  await expect(usernameInput).toBeVisible({ timeout: 15_000 })
  await usernameInput.fill(username)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await expect(page.getByText(displayName)).toBeVisible()
  const response = await page.request.get('/api/v1/me')
  diagnostics?.recordStatus(response.status())
  expect(response.status()).toBe(200)
  const body = await response.json() as { id: string }
  expect(body.id).toMatch(/^[0-9a-f-]{36}$/)
  return body.id
}

async function write(page: Page, method: string, path: string, data?: unknown, diagnostics?: UiDiagnostics, extraHeaders: Record<string, string> = {}): Promise<APIResponse> {
  const csrfResponse = await page.request.get('/api/v1/csrf')
  diagnostics?.recordStatus(csrfResponse.status())
  expect(csrfResponse.status()).toBe(200)
  const csrf = await csrfResponse.json() as { headerName: string, token: string }
  const response = await page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID(), ...extraHeaders },
    data,
  })
  diagnostics?.recordStatus(response.status())
  return response
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
    expect(await permissions.json()).toMatchObject({ projectId: project.id, roles: ['PROJECT_VIEWER'], permissions: expect.arrayContaining(['project:read']) })

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
  const diagnostics = new UiDiagnostics()
  if (!adminUser || !adminPassword || !viewerUser || !viewerPassword) {
    const error = new Error('Both isolated R03 test credentials are required')
    await diagnostics.write(test.info().title, error)
    throw error
  }

  let businessError: unknown
  let cleanupError: unknown
  let viewerContext: import('@playwright/test').BrowserContext | undefined
  try {
    const adminId = await diagnostics.step(page, 'login (admin)', () => login(page, adminUser, adminPassword, 'R03 Tester', diagnostics))
    const context = await browser.newContext()
    viewerContext = context
    const viewerPage = await context.newPage()
    const viewerId = await diagnostics.step(viewerPage, 'login (viewer)', () => login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer', diagnostics))
    const { tenantId } = seedProjectAccessScope(adminId, viewerId)
    const projectCode = `R03-UI-${Date.now()}`
    const accessRegion = page.getByRole('region', { name: '租户、项目与成员' })
    const viewerAccessRegion = viewerPage.getByRole('region', { name: '租户、项目与成员' })
    const projectSelect = accessRegion.locator('.access-selects select').nth(1)
    const viewerProjectSelect = viewerAccessRegion.locator('.access-selects select').nth(1)

    await diagnostics.step(page, 'load scope (admin)', async () => {
      await page.getByRole('button', { name: '加载访问范围' }).click()
      await accessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(page.getByLabel('项目域')).toBeVisible()
    })
    await page.getByLabel('项目代码').fill(projectCode)
    await page.getByLabel('显示名称').fill('R03 UI project')
    await diagnostics.step(page, 'create', async () => {
      await page.getByRole('button', { name: '创建项目' }).click()
      await expect(projectSelect.locator('option').filter({ hasText: `R03 UI project（${projectCode}）` })).toHaveCount(1)
      await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 UI project')
    })

    // Exercise the browser edit path, then force a concurrent update so the
    // stale row-version response is observed through the real UI. The direct
    // request is only the second writer used to create the conflict; the
    // operation under test is the DOM form submission below.
    const uiProjectOption = projectSelect.locator('option').filter({ hasText: `R03 UI project（${projectCode}）` })
    const uiProjectId = await uiProjectOption.getAttribute('value')
    expect(uiProjectId).toBeTruthy()
    await diagnostics.step(page, 'edit', async () => {
      await page.getByLabel('项目名称').fill('R03 UI edited project')
      await page.getByRole('button', { name: '保存项目' }).click()
      await expect(page.getByText('项目已更新')).toBeVisible()
      await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 UI edited project')
    })
    const currentProjectResponse = await page.request.get(`/api/v1/projects/${uiProjectId}`)
    expect(currentProjectResponse.status()).toBe(200)
    const currentProject = await currentProjectResponse.json() as { rowVersion: number }
    await diagnostics.step(page, 'version conflict', async () => {
      const concurrentUpdate = await write(page, 'PATCH', `/api/v1/projects/${uiProjectId}`, {
        name: 'R03 concurrent update',
        rowVersion: currentProject.rowVersion,
      }, diagnostics)
      expect(concurrentUpdate.status()).toBe(200)
      await page.getByLabel('项目名称').fill('R03 stale browser edit')
      await page.getByRole('button', { name: '保存项目' }).click()
      await expect(page.getByText(/项目已被其他请求更新|版本冲突|changed|stale|version|modified|concurrent/i)).toBeVisible()
      await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')
    })

    await diagnostics.step(page, 'authorization', async () => {
      const candidate = page.getByLabel('同租户候选主体')
      await candidate.selectOption(viewerId)
      await page.getByLabel('固定角色').selectOption('PROJECT_VIEWER')
      await page.getByRole('button', { name: '保存成员' }).click()
      await expect(page.getByText('成员授权已保存')).toBeVisible()
    })

    const ungrantedCode = `${projectCode}-PRIVATE`
    await page.getByLabel('项目代码').fill(ungrantedCode)
    await page.getByLabel('显示名称').fill('R03 UI private project')
    await page.getByRole('button', { name: '创建项目' }).click()
    await expect(projectSelect.locator('option').filter({ hasText: `R03 UI private project（${ungrantedCode}）` })).toHaveCount(1)
    if (!uiProjectId) throw new Error('Created project option did not expose an ID')
    await projectSelect.selectOption(uiProjectId)
    await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')

    await diagnostics.step(viewerPage, 'viewer read', async () => {
      await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
      // Select the tenant returned by the isolated seed explicitly.  A user may
      // belong to more than one tenant and the first list entry is not a stable
      // project-access scope for this scenario.
      await viewerAccessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(viewerProjectSelect).toContainText(`R03 concurrent update`)
      await expect(viewerProjectSelect).not.toContainText('R03 UI private project')
      await expect(viewerPage.getByRole('region', { name: '项目详情' })).toContainText('R03 concurrent update')
      await expect(viewerPage.getByRole('button', { name: '创建项目' })).not.toBeVisible()
      await expect(viewerPage.getByRole('button', { name: '保存成员' })).not.toBeVisible()
    })

    // The same tenant can contain projects the viewer is not a member of.
    // The UI must only render the scoped project list, not the tenant-wide set.
    const revokeRow = page.locator('.member-row').filter({ hasText: 'R03 Viewer' })
    await expect(revokeRow).toBeVisible()
    await diagnostics.step(page, 'revoke', async () => {
      await revokeRow.getByRole('button', { name: '撤销访问' }).click()
      await expect(page.getByText('R03 Viewer 已撤销项目访问')).toBeVisible()
    })

    await diagnostics.step(viewerPage, 'logout', async () => {
      await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
      await viewerAccessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(viewerProjectSelect).toHaveValue('')
      await expect(viewerPage.getByRole('region', { name: '项目详情' })).not.toBeVisible()
      await viewerPage.getByRole('button', { name: '退出登录' }).click()
      await expect(viewerPage.getByText('未登录或会话已过期')).toBeVisible()
      await expect(viewerPage.getByText('登录后加载你有权查看的租户和项目。')).toBeVisible()
    })
  } catch (error) {
    businessError = error
    throw error
  } finally {
    try {
      await viewerContext?.close()
    } catch (error) {
      cleanupError = error
    }
    await diagnostics.write(test.info().title, businessError, cleanupError)
  }
  // Keep cleanup failures distinct in the diagnostic artifact, but fail the
  // test when cleanup is the only failure. A business assertion thrown above
  // already controls the test result and must not be replaced here.
  if (!businessError && cleanupError) throw cleanupError
})

test('real Keycloak UI requirement flow creates edits and keeps viewer read-only', async ({ page, browser }) => {
  test.skip(!isOwnedCiRun(), 'destructive requirement flow requires the current CI-owned R03 stack')
  test.setTimeout(150_000)
  const adminUser = process.env.R03_TEST_USER
  const adminPassword = process.env.R03_TEST_USER_PASSWORD
  const viewerUser = process.env.R03_VIEWER_USER
  const viewerPassword = process.env.R03_VIEWER_PASSWORD
  const diagnostics = new UiDiagnostics()
  if (!adminUser || !adminPassword || !viewerUser || !viewerPassword) {
    const error = new Error('Both isolated R03 test credentials are required')
    await diagnostics.write(test.info().title, error, undefined, 'project-requirement-context')
    throw error
  }

  let businessError: unknown
  let cleanupError: unknown
  let viewerContext: import('@playwright/test').BrowserContext | undefined
  try {
    const adminId = await diagnostics.step(page, 'requirement login (admin)', () => login(page, adminUser, adminPassword, 'R03 Tester', diagnostics))
    const context = await browser.newContext()
    viewerContext = context
    const viewerPage = await context.newPage()
    const viewerId = await diagnostics.step(viewerPage, 'requirement login (viewer)', () => login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer', diagnostics))
    const { tenantId } = seedProjectAccessScope(adminId, viewerId)
    const projectCode = `R04-E2E-${Date.now()}`
    const requirementTitle = `R04 login requirement ${Date.now()}`
    const updatedRequirementTitle = `${requirementTitle} updated`
    const accessRegion = page.getByRole('region', { name: '租户、项目与成员' })
    const viewerAccessRegion = viewerPage.getByRole('region', { name: '租户、项目与成员' })
    const projectSelect = accessRegion.locator('.access-selects select').nth(1)
    const viewerProjectSelect = viewerAccessRegion.locator('.access-selects select').nth(1)

    await diagnostics.step(page, 'requirement load scope', async () => {
      await page.getByRole('button', { name: '加载访问范围' }).click()
      await accessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(page.getByLabel('项目域')).toBeVisible()
    })
    await page.getByLabel('项目代码').fill(projectCode)
    await page.getByLabel('显示名称').fill('R04 requirement project')
    await diagnostics.step(page, 'requirement create project', async () => {
      await page.getByRole('button', { name: '创建项目' }).click()
      await expect(projectSelect.locator('option').filter({ hasText: `R04 requirement project（${projectCode}）` })).toHaveCount(1)
      await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R04 requirement project')
    })
    const projectId = await projectSelect.locator('option').filter({ hasText: `R04 requirement project（${projectCode}）` }).getAttribute('value')
    expect(projectId).toMatch(/^[0-9a-f-]{36}$/)

    const requirementsPanel = page.locator('section.requirements-panel')
    await diagnostics.step(page, 'requirement create', async () => {
      await expect(requirementsPanel.getByLabel('需求标题')).toBeVisible()
      await requirementsPanel.getByLabel('需求标题').fill(requirementTitle)
      await requirementsPanel.getByLabel('需求正文').fill('The first requirement is persisted with an immutable revision.')
      await requirementsPanel.getByRole('button', { name: '创建需求' }).click()
      await expect(requirementsPanel).toContainText('需求已创建')
      await expect(requirementsPanel.locator('.requirement-row').first()).toContainText(requirementTitle)
      await expect(requirementsPanel.locator('.revision-row')).toHaveCount(1)
      await expect(requirementsPanel.locator('.revision-row').first()).toContainText('修订 1')
    })
    const listResponse = await page.request.get(`/api/v1/projects/${projectId}/requirements`)
    diagnostics.recordStatus(listResponse.status())
    expect(listResponse.status()).toBe(200)
    const listBody = await listResponse.json() as { items: Array<{ id: string, title: string }> }
    const requirementId = listBody.items.find((item) => item.title === requirementTitle)?.id
    expect(requirementId).toMatch(/^[0-9a-f-]{36}$/)

    await diagnostics.step(page, 'requirement edit', async () => {
      await requirementsPanel.locator('.requirement-row').first().click()
      await expect(requirementsPanel.getByLabel('编辑需求标题')).toHaveValue(requirementTitle)
      await requirementsPanel.getByLabel('编辑需求标题').fill(updatedRequirementTitle)
      await requirementsPanel.getByLabel('编辑需求正文').fill('The edited body is revision two.')
      await requirementsPanel.getByRole('button', { name: '保存需求' }).click()
      await expect(requirementsPanel).toContainText('需求已保存')
      await expect(requirementsPanel).toContainText('2 个修订')
      await expect(requirementsPanel.locator('.revision-row').filter({ hasText: '修订 1' })).toBeVisible()
      await requirementsPanel.locator('.revision-row').filter({ hasText: '修订 1' }).click()
      await expect(requirementsPanel.locator('.revision-readonly')).toContainText(requirementTitle)
    })

    await diagnostics.step(page, 'requirement authorization', async () => {
      const candidate = page.getByLabel('同租户候选主体')
      await candidate.selectOption(viewerId)
      await page.getByLabel('固定角色').selectOption('PROJECT_VIEWER')
      await page.getByRole('button', { name: '保存成员' }).click()
      await expect(page.getByText('成员授权已保存')).toBeVisible()
    })

    const viewerRequirements = viewerPage.locator('section.requirements-panel')
    await diagnostics.step(viewerPage, 'requirement viewer read', async () => {
      await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
      await viewerAccessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(viewerProjectSelect.locator('option').filter({ hasText: projectCode })).toHaveCount(1)
      await viewerProjectSelect.selectOption(projectId as string)
      await expect(viewerRequirements.locator('.requirement-row').first()).toContainText(updatedRequirementTitle)
      await viewerRequirements.locator('.requirement-row').first().click()
      await expect(viewerRequirements).toContainText('只读用户可以查看需求和历史，但不能创建或保存。')
      await expect(viewerRequirements.locator('.revision-row')).toHaveCount(2)
      await expect(viewerRequirements.getByRole('button', { name: '创建需求' })).not.toBeVisible()
      await expect(viewerRequirements.getByRole('button', { name: '保存需求' })).not.toBeVisible()
    })

    await diagnostics.step(viewerPage, 'requirement viewer write denied', async () => {
      const viewerCreate = await write(viewerPage, 'POST', `/api/v1/projects/${projectId}/requirements`, {
        title: 'R04 viewer must not create', body: 'forbidden',
      }, diagnostics)
      expect(viewerCreate.status()).toBe(403)
      const viewerEdit = await write(viewerPage, 'PATCH', `/api/v1/projects/${projectId}/requirements/${requirementId}`, {
        title: 'R04 viewer must not edit', body: 'forbidden',
      }, diagnostics, { 'If-Match': '"2"' })
      expect([403, 404]).toContain(viewerEdit.status())
    })

    const revokeRow = page.locator('.member-row').filter({ hasText: 'R03 Viewer' })
    await expect(revokeRow).toBeVisible()
    await diagnostics.step(page, 'requirement revoke', async () => {
      await revokeRow.getByRole('button', { name: '撤销访问' }).click()
      await expect(page.getByText('R03 Viewer 已撤销项目访问')).toBeVisible()
    })
    await diagnostics.step(viewerPage, 'requirement access denied after revoke', async () => {
      const afterRevoke = await viewerPage.request.get(`/api/v1/projects/${projectId}/requirements`)
      diagnostics.recordStatus(afterRevoke.status())
      expect([403, 404]).toContain(afterRevoke.status())
      await expect(afterRevoke.json()).resolves.toMatchObject({ code: expect.stringMatching(/^(FORBIDDEN|NOT_FOUND)$/) })
      await viewerPage.getByRole('button', { name: '刷新需求' }).click()
      await expect(viewerPage.getByRole('alert')).toContainText(/权限|访问|forbidden|authorized|not found/i)
      await expect(viewerRequirements.locator('.requirement-row')).toHaveCount(0)
    })
  } catch (error) {
    businessError = error
    throw error
  } finally {
    try {
      await viewerContext?.close()
    } catch (error) {
      cleanupError = error
    }
    await diagnostics.write(test.info().title, businessError, cleanupError, 'project-requirement-context')
  }
  if (!businessError && cleanupError) throw cleanupError
})

test('real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only', async ({ page, browser }) => {
  test.skip(!isOwnedCiRun(), 'destructive manual test case flow requires the current CI-owned R03 stack')
  test.setTimeout(180_000)
  const adminUser = process.env.R03_TEST_USER
  const adminPassword = process.env.R03_TEST_USER_PASSWORD
  const memberUser = process.env.R03_MEMBER_USER
  const memberPassword = process.env.R03_MEMBER_PASSWORD
  const viewerUser = process.env.R03_VIEWER_USER
  const viewerPassword = process.env.R03_VIEWER_PASSWORD
  const diagnostics = new UiDiagnostics()
  if (!adminUser || !adminPassword || !memberUser || !memberPassword || !viewerUser || !viewerPassword) {
    const error = new Error('Isolated R03 admin, member, and viewer credentials are required')
    await diagnostics.write(test.info().title, error, undefined, 'manual-test-case-context')
    throw error
  }

  let businessError: unknown
  let cleanupError: unknown
  let memberContext: import('@playwright/test').BrowserContext | undefined
  let viewerContext: import('@playwright/test').BrowserContext | undefined
  try {
    const adminId = await diagnostics.step(page, 'manual test login (admin)', () => login(page, adminUser, adminPassword, 'R03 Tester', diagnostics))
    memberContext = await browser.newContext()
    const memberPage = await memberContext.newPage()
    const memberId = await diagnostics.step(memberPage, 'manual test login (project member)', () => login(memberPage, memberUser, memberPassword, 'R03 Member', diagnostics))
    viewerContext = await browser.newContext()
    const viewerPage = await viewerContext.newPage()
    const viewerId = await diagnostics.step(viewerPage, 'manual test login (viewer)', () => login(viewerPage, viewerUser, viewerPassword, 'R03 Viewer', diagnostics))
    const { tenantId } = seedProjectAccessScope(adminId, viewerId, memberId)
    const projectCode = `R05-E2E-${Date.now()}`
    const accessRegion = page.getByRole('region', { name: '租户、项目与成员' })
    const viewerAccessRegion = viewerPage.getByRole('region', { name: '租户、项目与成员' })
    const projectSelect = accessRegion.locator('.access-selects select').nth(1)
    const viewerProjectSelect = viewerAccessRegion.locator('.access-selects select').nth(1)

    await diagnostics.step(page, 'manual test load scope', async () => {
      await page.getByRole('button', { name: '加载访问范围' }).click()
      await accessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(page.getByLabel('项目域')).toBeVisible()
    })
    await page.getByLabel('项目代码').fill(projectCode)
    await page.getByLabel('显示名称').fill('R05 manual test project')
    await diagnostics.step(page, 'manual test create project', async () => {
      await page.getByRole('button', { name: '创建项目' }).click()
      await expect(projectSelect.locator('option').filter({ hasText: `R05 manual test project（${projectCode}）` })).toHaveCount(1)
      await expect(page.getByRole('region', { name: '项目详情' })).toContainText('R05 manual test project')
    })
    const projectId = await projectSelect.locator('option').filter({ hasText: `R05 manual test project（${projectCode}）` }).getAttribute('value')
    expect(projectId).toMatch(/^[0-9a-f-]{36}$/)
    await diagnostics.step(page, 'manual test authorize project member', async () => {
      const candidate = page.getByLabel('同租户候选主体')
      await candidate.selectOption(memberId)
      await page.getByLabel('固定角色').selectOption('PROJECT_MEMBER')
      await page.getByRole('button', { name: '保存成员' }).click()
      await expect(page.getByText('成员授权已保存')).toBeVisible()
    })
    await diagnostics.step(page, 'manual test authorize viewer', async () => {
      const candidate = page.getByLabel('同租户候选主体')
      await candidate.selectOption(viewerId)
      await page.getByLabel('固定角色').selectOption('PROJECT_VIEWER')
      await page.getByRole('button', { name: '保存成员' }).click()
      await expect(page.getByText('成员授权已保存')).toBeVisible()
    })
    const memberAccessRegion = memberPage.getByRole('region', { name: '租户、项目与成员' })
    const memberProjectSelect = memberAccessRegion.locator('.access-selects select').nth(1)
    const testCasesPanel = memberPage.locator('section.test-cases-panel')
    const testTitle = `R05 member manual test ${Date.now()}`

    await diagnostics.step(memberPage, 'manual test member load scope', async () => {
      await memberPage.getByRole('button', { name: '加载访问范围' }).click()
      await memberAccessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(memberProjectSelect.locator('option').filter({ hasText: `R05 manual test project（${projectCode}）` })).toHaveCount(1)
      await memberProjectSelect.selectOption(projectId as string)
      await expect(memberPage.getByText('R03 Member')).toBeVisible()
      await expect(memberPage.getByRole('button', { name: '保存成员' })).not.toBeVisible()
      const memberPermissions = await memberPage.request.get(`/api/v1/me/permissions?projectId=${projectId}`)
      expect(memberPermissions.status()).toBe(200)
      const memberPermissionBody = await memberPermissions.json() as { roles: string[], permissions: string[] }
      expect(memberPermissionBody.roles).toContain('PROJECT_MEMBER')
      expect(memberPermissionBody.roles).not.toContain('PROJECT_ADMIN')
      expect(memberPermissionBody.permissions).toEqual(expect.arrayContaining(['test:read', 'test:create', 'test:update']))
    })

    await diagnostics.step(memberPage, 'manual test create', async () => {
      await expect(testCasesPanel.getByLabel('测试用例标题')).toBeVisible()
      await testCasesPanel.getByLabel('测试用例标题').fill(testTitle)
      await testCasesPanel.getByLabel('测试用例说明').fill('A browser-driven manual test definition.')
      await testCasesPanel.getByLabel('测试用例前置条件').fill('A disposable account exists.')
      await testCasesPanel.getByRole('button', { name: '新增步骤' }).click()
      await testCasesPanel.getByLabel('步骤 1 操作').fill('Open the login page')
      await testCasesPanel.getByLabel('步骤 1 预期结果').fill('The login form is visible')
      await testCasesPanel.getByRole('button', { name: '新增步骤' }).click()
      await testCasesPanel.getByLabel('步骤 2 操作').fill('Submit valid credentials')
      await testCasesPanel.getByLabel('步骤 2 预期结果').fill('The workbench is visible')
      await testCasesPanel.getByRole('button', { name: '创建用例' }).click()
      await expect(testCasesPanel).toContainText('测试用例已创建')
      await expect(testCasesPanel.locator('.test-case-list .requirement-row').first()).toContainText(testTitle)
      await expect(testCasesPanel.locator('.revision-row')).toHaveCount(1)
      await expect(testCasesPanel.getByRole('button', { name: '保存为新修订' })).toBeVisible()
    })
    const initialStepKeys = await testCasesPanel.locator('.test-step code').allTextContents()
    expect(initialStepKeys).toHaveLength(2)

    await diagnostics.step(memberPage, 'manual test reload and read', async () => {
      await memberPage.reload()
      await expect(memberPage.getByText('R03 Member')).toBeVisible()
      const reloadedAccess = memberPage.getByRole('region', { name: '租户、项目与成员' })
      await reloadedAccess.getByRole('button', { name: /加载访问范围|加载中/ }).click()
      await reloadedAccess.getByRole('combobox').first().selectOption(tenantId)
      await expect(reloadedAccess.locator('.access-selects select').nth(1).locator('option').filter({ hasText: `R05 manual test project（${projectCode}）` })).toHaveCount(1)
      await reloadedAccess.locator('.access-selects select').nth(1).selectOption(projectId as string)
      const reloadedPanel = memberPage.locator('section.test-cases-panel')
      await expect(reloadedPanel.locator('.test-case-list .requirement-row').first()).toContainText(testTitle)
      await reloadedPanel.locator('.test-case-list .requirement-row').first().click()
      await expect(reloadedPanel.getByLabel('编辑测试用例标题')).toHaveValue(testTitle)
      await expect(reloadedPanel.getByLabel('步骤 1 操作')).toHaveValue('Open the login page')
      await expect(reloadedPanel.getByRole('button', { name: '保存为新修订' })).toBeVisible()
    })

    await diagnostics.step(memberPage, 'manual test edit and reorder', async () => {
      const panel = memberPage.locator('section.test-cases-panel')
      const beforeReorder = await panel.locator('.test-step code').allTextContents()
      expect(beforeReorder).toEqual(initialStepKeys)
      await panel.getByRole('button', { name: '下移' }).first().click()
      const afterReorder = await panel.locator('.test-step code').allTextContents()
      expect(afterReorder).toEqual([initialStepKeys[1], initialStepKeys[0]])
      expect(new Set(afterReorder)).toEqual(new Set(initialStepKeys))
      await panel.getByLabel('编辑测试用例说明').fill('The second revision preserves stable step keys.')
      await panel.getByRole('button', { name: '保存为新修订' }).click()
      await expect(panel).toContainText('测试用例已保存为新修订')
      await expect(panel).toContainText('2 个修订')
      await expect(panel.locator('.test-step code')).toHaveText([initialStepKeys[1], initialStepKeys[0]])
      await expect(panel.getByRole('region', { name: '测试用例修订历史' }).getByRole('button', { name: /修订 1/ })).toBeVisible()
      await panel.getByRole('region', { name: '测试用例修订历史' }).getByRole('button', { name: /修订 1/ }).click()
      const oldRevision = panel.locator('.revision-readonly')
      await expect(oldRevision.locator('.test-step code')).toHaveText(initialStepKeys)
      await expect(oldRevision.locator('.test-step').nth(0)).toContainText('Open the login page')
      await expect(oldRevision.locator('.test-step').nth(0)).toContainText('The login form is visible')
      await expect(oldRevision.locator('.test-step').nth(1)).toContainText('Submit valid credentials')
      await expect(oldRevision.locator('.test-step').nth(1)).toContainText('The workbench is visible')
    })

    await diagnostics.step(viewerPage, 'manual test viewer read', async () => {
      await viewerPage.getByRole('button', { name: '加载访问范围' }).click()
      await viewerAccessRegion.getByRole('combobox').first().selectOption(tenantId)
      await expect(viewerProjectSelect.locator('option').filter({ hasText: `R05 manual test project（${projectCode}）` })).toHaveCount(1)
      await viewerProjectSelect.selectOption(projectId as string)
      const viewerPanel = viewerPage.locator('section.test-cases-panel')
      await expect(viewerPanel.locator('.test-case-list .requirement-row').first()).toContainText(testTitle)
      await viewerPanel.locator('.test-case-list .requirement-row').first().click()
      await expect(viewerPanel).toContainText('只读用户可以查看用例和历史，但不能创建或保存。')
      await expect(viewerPanel.getByRole('button', { name: '创建用例' })).not.toBeVisible()
      await expect(viewerPanel.getByRole('button', { name: '保存为新修订' })).not.toBeVisible()
      await expect(viewerPanel.getByRole('button', { name: '新增步骤' })).not.toBeVisible()
      await expect(viewerPanel.locator('.revision-row')).toHaveCount(2)
    })

    await diagnostics.step(viewerPage, 'manual test viewer write denied', async () => {
      const viewerCreate = await write(viewerPage, 'POST', `/api/v1/projects/${projectId}/tests`, {
        title: 'R05 viewer must not create', description: '', preconditions: '', testType: 'MANUAL', steps: [],
      }, diagnostics)
      expect(viewerCreate.status()).toBe(403)
      await expect(viewerCreate.json()).resolves.toMatchObject({ code: 'FORBIDDEN' })
    })
  } catch (error) {
    businessError = error
    throw error
  } finally {
    try {
      await memberContext?.close()
      await viewerContext?.close()
    } catch (error) {
      cleanupError = error
    }
    await diagnostics.write(test.info().title, businessError, cleanupError, 'manual-test-case-context')
  }
  if (!businessError && cleanupError) throw cleanupError
})
