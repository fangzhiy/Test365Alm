import { afterEach, describe, expect, it, vi } from 'vitest'
import { requirementsApi } from './requirements'
import { requestJson } from './projectAccess'

const requirement = {
  id: 'req-1', projectId: 'project-1', displayNumber: 'REQ-1', title: 'Login', body: 'Body',
  priority: 'MEDIUM' as const, revisionNumber: 1, rowVersion: 1, createdAt: '2026-10-01T00:00:00Z',
  createdBy: 'principal-1', currentRevisionId: 'rev-1',
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('requirements API transport contracts', () => {
  it('preserves HTTP status together with the structured business error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: 'FORBIDDEN', message: 'not a project member' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } },
    )))
    await expect(requestJson('/api/v1/projects/project-1/requirements')).rejects.toMatchObject({
      code: 'FORBIDDEN',
      status: 403,
    })
  })

  it('keeps a status-only HTTP rejection distinct from a business code', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>denied</html>', { status: 503 })))
    await expect(requestJson('/api/v1/projects/project-1/requirements')).rejects.toMatchObject({
      code: 'HTTP_503',
      status: 503,
    })
  })

  it('reuses the same idempotency key when the first response is lost', async () => {
    const calls: Array<{ url: string; headers: Headers }> = []
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      const headers = new Headers(init?.headers ?? (typeof input === 'object' && 'headers' in input ? input.headers : undefined))
      calls.push({ url, headers })
      if (url.endsWith('/api/v1/csrf')) return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'test-token' }), { status: 200 })
      if (calls.filter((item) => item.url.endsWith('/requirements')).length === 1) throw new TypeError('response lost')
      return new Response(JSON.stringify(requirement), { status: 201, headers: { ETag: '"1"' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    await expect(requirementsApi.create('project-1', { title: 'Login', body: 'Body' })).rejects.toThrow('response lost')
    await expect(requirementsApi.create('project-1', { title: 'Login', body: 'Body' })).resolves.toMatchObject({ id: 'req-1', etag: '"1"' })
    const requestKeys = calls.filter((item) => item.url.endsWith('/requirements')).map((item) => item.headers.get('Idempotency-Key'))
    expect(requestKeys).toHaveLength(2)
    expect(requestKeys[0]).toBeTruthy()
    expect(requestKeys[0]).toBe(requestKeys[1])
  })

  it('requires the server ETag before an update', async () => {
    await expect(requirementsApi.update('project-1', { ...requirement, etag: undefined }, { title: 'Next', body: 'Body' })).rejects.toMatchObject({ code: 'MISSING_ETAG' })
  })
})
