import { afterEach, expect, it, vi } from 'vitest'
import { requestJson } from './projectAccess'
import type { AccessError } from './projectAccess'
import { reportScopeDenied } from './reportsApi'

afterEach(() => vi.unstubAllGlobals())

it.each([
  [401, { code: 'UNAUTHENTICATED' }, true],
  [403, { code: 'FORBIDDEN' }, true],
  [403, { code: 'CSRF_REJECTED' }, false],
  [422, { code: 'REPORT_LIMIT_EXCEEDED' }, false],
  [400, { code: 'INVALID_REQUEST' }, false],
  [500, { code: 'INTERNAL_ERROR' }, false],
] as const)('preserves code-only %s %j through the real request boundary', async (status, body, denied) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(body, { status })))
  const error = await requestJson('/api/v1/report').catch(e => e) as AccessError
  expect(error).toMatchObject({ code: body.code, status })
  expect(error.message).toEqual(expect.any(String))
  expect(error.message.length).toBeGreaterThan(0)
  expect(reportScopeDenied(error)).toBe(denied)
})

it.each([null, [], { code: 12 }, { message: 'untrusted upstream text' }, '<html>upstream failure</html>'])('handles malformed 401 without losing the unauthenticated status: %j', async body => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(typeof body === 'string' ? body : JSON.stringify(body), { status: 401 })))
  const error = await requestJson('/api/v1/report').catch(e => e) as AccessError
  expect(error.status).toBe(401)
  expect(error.message).not.toContain('upstream')
  expect(reportScopeDenied(error)).toBe(true)
})

it.each([403, 500])('keeps unknown non-JSON %s separate from confirmed scope denial', async status => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('upstream failure', { status })))
  const error = await requestJson('/api/v1/report').catch(e => e) as AccessError
  expect(error).toMatchObject({ code: `HTTP_${status}`, status })
  expect(reportScopeDenied(error)).toBe(false)
})

it('keeps structured messages and transport errors unchanged', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(Response.json({ code: 'VERSION_CONFLICT', message: '版本冲突' }, { status: 409 }))
  const offline = new TypeError('offline')
  fetch.mockRejectedValueOnce(offline); vi.stubGlobal('fetch', fetch)
  await expect(requestJson('/api')).rejects.toMatchObject({ code: 'VERSION_CONFLICT', message: '版本冲突', status: 409 })
  await expect(requestJson('/api')).rejects.toBe(offline)
  expect(reportScopeDenied(offline)).toBe(false)
})
