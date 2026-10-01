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
    })
    expect(grant.status()).toBe(200)
    expect(await grant.json()).toMatchObject({ principalId: viewerId, roles: ['PROJECT_VIEWER'], state: 'ACTIVE' })

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

    const revoke = await write(page, 'DELETE', `/api/v1/projects/${project.id}/members/${viewerId}`)
    expect(revoke.status()).toBe(200)
    expect(await revoke.json()).toMatchObject({ principalId: viewerId, state: 'REVOKED' })

    const staleSession = await viewerPage.request.get(`/api/v1/me/permissions?projectId=${project.id}`)
    expect([403, 404]).toContain(staleSession.status())
    expect(await staleSession.json()).toMatchObject({ code: expect.stringMatching(/^(FORBIDDEN|NOT_FOUND)$/) })
  } finally {
    await viewerContext.close()
  }
})
