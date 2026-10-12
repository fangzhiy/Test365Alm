import { expect, request, test } from '@playwright/test'
import { isOwnedCiRun, setPrincipalDisabled, startKeycloak, stopKeycloak } from './ownedR03'

async function login(page: import('@playwright/test').Page): Promise<string> {
  const password = process.env.R03_TEST_USER_PASSWORD
  if (!password) throw new Error('Isolated R03 test credential is required')
  await page.goto('/')
  await page.getByRole('link', { name: '登录 Test365Alm' }).click()
  const username = page.locator('#username')
  const loggedIn = page.getByText('R03 Tester')
  await expect(username.or(loggedIn).first()).toBeVisible({ timeout: 15_000 })
  if (await username.isVisible()) {
    await username.fill('r03-user')
    await page.locator('#password').fill(password)
    await page.locator('#kc-login').click()
  }
  await expect(loggedIn).toBeVisible()
  const me = await page.request.get('/api/v1/me')
  expect(me.status()).toBe(200)
  return (await me.json()).id as string
}

test('real Keycloak authorization code login, current user and local logout', async ({ page }) => {
  const password = process.env.R03_TEST_USER_PASSWORD
  if (!password) throw new Error('Isolated R03 test credential is required')

  await page.goto('/')
  const forged = await page.request.get('/api/v1/me', { headers: { 'X-User-Id': 'admin', 'X-Tenant-Id': 'admin' } })
  expect(forged.status()).toBe(401)
  expect(forged.headers()['content-type']).toContain('application/json')
  await page.getByRole('link', { name: '登录 Test365Alm' }).click()
  await expect(page).toHaveURL(/127\.0\.0\.1:18090\/realms\/test365alm/)
  const authorization = new URL(page.url())
  expect(authorization.searchParams.get('code_challenge_method')).toBe('S256')
  expect(authorization.searchParams.get('state')).toBeTruthy()
  expect(authorization.searchParams.get('nonce')).toBeTruthy()
  const beforeLogin = (await page.context().cookies()).find((cookie) => cookie.name === 'JSESSIONID')
  await page.locator('#username').fill('r03-user')
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  console.log('post-login path:', new URL(page.url()).origin + new URL(page.url()).pathname)

  await expect(page.getByText('R03 Tester')).toBeVisible()
  const me = await page.request.get('/api/v1/me')
  expect(me.status()).toBe(200)
  const body = await me.json()
  expect(body).toMatchObject({ displayName: 'R03 Tester', issuer: 'http://127.0.0.1:18090/realms/test365alm' })
  expect(body.id).toMatch(/^[0-9a-f-]{36}$/)
  expect(body).not.toHaveProperty('access_token')
  expect(body).not.toHaveProperty('id_token')
  const credentialKeys = await page.evaluate(() => [...Object.keys(localStorage), ...Object.keys(sessionStorage)]
    .filter((key) => /token|credential|auth/i.test(key)))
  expect(credentialKeys).toEqual([])

  const cookies = await page.context().cookies()
  const session = cookies.find((cookie) => cookie.name === 'JSESSIONID')
  expect(session?.httpOnly).toBe(true)
  expect(session?.sameSite).toBe('Lax')
  expect(session?.value).not.toBe(beforeLogin?.value)

  const invalidLogout = await page.request.post('/api/v1/auth/logout')
  expect(invalidLogout.status()).toBe(403)
  expect(await invalidLogout.json()).toEqual({ code: 'CSRF_REJECTED' })
  expect((await page.request.get('/api/v1/me')).status()).toBe(200)

  const csrf = await (await page.request.get('/api/v1/csrf')).json() as { headerName: string, token: string }
  const wrongCsrf = await page.request.post('/api/v1/auth/logout', { headers: { [csrf.headerName]: 'invalid-token' } })
  expect(wrongCsrf.status()).toBe(403)
  expect((await page.request.get('/api/v1/me')).status()).toBe(200)

  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByText('未登录或会话已过期')).toBeVisible()
  const after = await page.request.get('/api/v1/me')
  expect(after.status()).toBe(401)
  const replay = await request.newContext({ baseURL: 'http://127.0.0.1:5173',
    extraHTTPHeaders: { Cookie: `JSESSIONID=${session?.value}` } })
  try { expect((await replay.get('/api/v1/me')).status()).toBe(401) }
  finally { await replay.dispose() }
})

test('real session expires; a locally disabled principal cannot resume or log in again', async ({ page }) => {
  test.skip(!isOwnedCiRun(), 'destructive identity test requires the current CI-owned R03 stack')
  test.setTimeout(160_000)
  expect(process.env.TEST365ALM_SESSION_TIMEOUT).toBe('1m')
  const id = await login(page)
  // Close the page so its health polling cannot refresh the browser session.
  const originalContext = page.context()
  await page.close()
  await new Promise((resolve) => setTimeout(resolve, 75_000))
  expect((await originalContext.request.get('/api/v1/me')).status()).toBe(401)
  const expiredPage = await originalContext.newPage()
  await expiredPage.goto('/')
  await expect(expiredPage.getByText('未登录或会话已过期')).toBeVisible()

  // A fresh browser context avoids relying on the expired local cookie.
  const another = await originalContext.browser()!.newContext()
  try {
    const secondPage = await another.newPage()
    await login(secondPage)
    setPrincipalDisabled(id, true)
    try {
      const blocked = await secondPage.request.get('/api/v1/me')
      expect(blocked.status()).toBe(403)
      expect(await blocked.json()).toEqual({ code: 'IDENTITY_DISABLED' })
      expect((await secondPage.request.get('/api/v1/me')).status()).toBe(401)
      await secondPage.goto('/')
      await secondPage.getByRole('link', { name: '登录 Test365Alm' }).click()
      await expect(secondPage.getByText('登录失败，请检查身份提供方后重试')).toBeVisible()
      expect((await secondPage.request.get('/api/v1/me')).status()).toBe(401)
    } finally { setPrincipalDisabled(id, false) }
  } finally { await another.close() }
})

test('IdP outage blocks new login but local session and logout remain available', async ({ page }) => {
  test.skip(!isOwnedCiRun(), 'IdP outage test requires the current CI-owned R03 stack')
  test.setTimeout(120_000)
  await login(page)
  const oldCookie = (await page.context().cookies()).find((cookie) => cookie.name === 'JSESSIONID')?.value
  expect(oldCookie).toBeTruthy()
  stopKeycloak()
  try {
    expect((await page.request.get('/health/live')).status()).toBe(200)
    expect((await page.request.get('/api/v1/me')).status()).toBe(200)
    const fresh = await request.newContext({ baseURL: 'http://127.0.0.1:5173' })
    try {
      const auth = await fresh.get('/oauth2/authorization/test365alm', { maxRedirects: 0 })
      expect(auth.status()).toBe(302)
      const location = auth.headers().location
      expect(location).toContain('127.0.0.1:18090')
      await expect(fresh.get(location, { timeout: 3_000 })).rejects.toThrow()
      expect((await fresh.get('/api/v1/me')).status()).toBe(401)
    } finally { await fresh.dispose() }
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page.getByText('未登录或会话已过期')).toBeVisible()
    expect((await page.request.get('/api/v1/me')).status()).toBe(401)
    const replay = await request.newContext({ baseURL: 'http://127.0.0.1:5173',
      extraHTTPHeaders: { Cookie: `JSESSIONID=${oldCookie}` } })
    try { expect((await replay.get('/api/v1/me')).status()).toBe(401) }
    finally { await replay.dispose() }
  } finally {
    startKeycloak()
    await expect.poll(async () => {
      try { return (await page.request.get('http://127.0.0.1:18090/realms/test365alm/.well-known/openid-configuration',
        { timeout: 2_000 })).status() } catch { return 0 }
    }, { timeout: 60_000, intervals: [1_000] }).toBe(200)
  }
  await login(page)
})

test('invalid OAuth state cannot establish a session', async ({ page }) => {
  await page.goto('/login/oauth2/code/test365alm?code=invalid&state=invalid')
  await expect(page.getByText('登录失败，请检查身份提供方后重试')).toBeVisible()
  expect((await page.request.get('/api/v1/me')).status()).toBe(401)
})

test('Keycloak rejects a callback outside the registered allowlist', async ({ request }) => {
  const url = new URL('http://127.0.0.1:18090/realms/test365alm/protocol/openid-connect/auth')
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', 'test365alm-web')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('redirect_uri', 'http://example.invalid/callback')
  url.searchParams.set('state', 'synthetic-state')
  url.searchParams.set('code_challenge_method', 'S256')
  url.searchParams.set('code_challenge', 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA')
  const response = await request.get(url.toString(), { maxRedirects: 0 })
  expect(response.status()).toBe(400)
})
