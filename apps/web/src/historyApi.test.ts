import { afterEach, expect, it, vi } from 'vitest'
import { historyApi } from './historyApi'

afterEach(() => vi.unstubAllGlobals())
const entry = { id: 'event', at: '2026-11-10T08:00:00Z', actor: { id: 'member', displayName: null }, action: 'defect.revised', revision: 2, changes: [{ field: 'title', before: 'Old', after: 'New' }] }

it('reads validated history and encodes the bounded opaque cursor and path', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [entry], nextCursor: 'next' })))
  vi.stubGlobal('fetch', fetch)
  expect(await historyApi.list('p/a', 'defect', 'd/b', 'opaque+/=')).toEqual({ items: [entry], nextCursor: 'next' })
  expect(String(fetch.mock.calls[0][0])).toBe('/api/v1/projects/p%2Fa/history/defect/d%2Fb?limit=25&cursor=opaque%2B%2F%3D')
})

it.each([
  {}, { items: [], nextCursor: 3 },
  { items: [{ ...entry, at: 'invalid' }], nextCursor: null },
  { items: [{ ...entry, actor: { id: '' } }], nextCursor: null },
  { items: [{ ...entry, revision: -1 }], nextCursor: null },
  { items: [{ ...entry, changes: [{ field: 'title', before: {}, after: 'New' }] }], nextCursor: null },
])('rejects malformed history response %j', async body => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
  await expect(historyApi.list('p', 'run', 'r', null)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})

it('preserves the real forbidden status', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'Denied' }), { status: 403 })))
  await expect(historyApi.list('p', 'test_case', 't', null)).rejects.toMatchObject({ code: 'FORBIDDEN', status: 403 })
})
