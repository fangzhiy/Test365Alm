import { describe, expect, it } from 'vitest'
import { safeHttpEvent, safeLocation } from '../e2e/safeHttpDiagnostics'

describe('safe browser HTTP diagnostics', () => {
  const observedAt = '2026-10-16T18:25:21.000Z'

  it('pairs OAuth path status and time without authorization parameters', () => {
    for (const pathname of ['/oauth2/authorization/test365alm', '/login/oauth2/code/test365alm']) {
      const event = safeHttpEvent(`http://127.0.0.1:5173${pathname}?code=synthetic-code&state=synthetic-state&token=synthetic-token`, 302, observedAt)
      expect(event).toEqual({ pathname, status: 302, observedAt, loginFailed: false })
      expect(JSON.stringify(event)).not.toMatch(/synthetic-|\?|token|state/)
    }
  })

  it('captures only the explicit login failure boolean from navigation query', () => {
    expect(safeLocation('http://127.0.0.1:5173/?login=failed&code=synthetic-code#synthetic-token')).toEqual({ pathname: '/', loginFailed: true })
    expect(safeLocation('http://127.0.0.1:5173/?login=success&cookie=synthetic-cookie')).toEqual({ pathname: '/', loginFailed: false })
  })

  it('keeps API statuses but rejects unrelated URLs and invalid statuses', () => {
    expect(safeHttpEvent('http://127.0.0.1:5173/api/v1/me?token=synthetic-token', 401, observedAt)).toEqual({ pathname: '/api/v1/me', status: 401, observedAt, loginFailed: false })
    expect(safeHttpEvent('http://127.0.0.1:18090/realms/test365alm/login-actions/authenticate?session_code=synthetic-code', 302, observedAt)).toBeNull()
    expect(safeHttpEvent('not-a-url', 200, observedAt)).toBeNull()
    expect(safeHttpEvent('http://127.0.0.1:5173/api/v1/me', 0, observedAt)).toBeNull()
  })
})
