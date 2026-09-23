import { expect, test } from '@playwright/test'

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

  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByText('未登录或会话已过期')).toBeVisible()
  const after = await page.request.get('/api/v1/me')
  expect(after.status()).toBe(401)
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
