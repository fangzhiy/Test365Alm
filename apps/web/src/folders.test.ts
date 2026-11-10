import { afterEach, describe, expect, it, vi } from 'vitest'
import { foldersApi, type Folder } from './folders'
import { testsApi } from './tests'
import { executionApi } from './testExecution'

const folder: Folder = { id: 'f', kind: 'TEST_PLAN', parentId: null, name: 'OQ', sortOrder: 2, rowVersion: 3 }
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'synthetic-test-token' }
const respond = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
afterEach(() => vi.unstubAllGlobals())
describe('foldersApi', () => {
  it('reads a typed tree and rejects malformed responses', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(respond([folder])).mockResolvedValueOnce(respond([{ ...folder, rowVersion: 0 }]))
    vi.stubGlobal('fetch', fetch)
    expect(await foldersApi.tree('p', 'TEST_PLAN')).toEqual([folder])
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/projects/p/folders?kind=TEST_PLAN')
    await expect(foldersApi.tree('p', 'TEST_PLAN')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('uses CSRF, a creation key, and the exact folder ETag on writes', async () => {
    const fetch = vi.fn().mockImplementation((url: string) => Promise.resolve(respond(url.endsWith('/csrf') ? csrf : folder)))
    vi.stubGlobal('fetch', fetch)
    const input = { parentId: null, name: 'OQ', sortOrder: 2 }
    await foldersApi.create('p', 'TEST_PLAN', input, { idempotencyKey: 'intent-12345' })
    let call = fetch.mock.calls.find(c => c[1]?.method === 'POST')!
    expect(new Headers(call[1].headers).get('X-CSRF-TOKEN')).toBe(csrf.token)
    expect(new Headers(call[1].headers).get('Idempotency-Key')).toBe('intent-12345')
    await foldersApi.update('p', folder, { ...input, name: 'PQ' })
    call = fetch.mock.calls.find(c => c[1]?.method === 'PUT')!
    expect(new Headers(call[1].headers).get('If-Match')).toBe('"3"')
    await foldersApi.remove('p', folder)
    expect(fetch.mock.calls.find(c => c[1]?.method === 'DELETE')?.[0]).toBe('/api/v1/projects/p/folders/f')
  })
  it('moves objects using an independent placement version', async () => {
    const fetch = vi.fn().mockImplementation((url: string) => Promise.resolve(respond(url.endsWith('/csrf') ? csrf : { objectId: 'test', folderId: null, rowVersion: 4 })))
    vi.stubGlobal('fetch', fetch)
    const current = await foldersApi.location('p', 'TEST_PLAN', 'test')
    await foldersApi.place('p', 'TEST_PLAN', current, null)
    const call = fetch.mock.calls.find(c => c[1]?.method === 'PUT')!
    expect(call[0]).toBe('/api/v1/projects/p/folder-placements/TEST_PLAN/test')
    expect(new Headers(call[1].headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(call[1].body)).toEqual({ folderId: null })
  })
  it('sends folder filtering alongside the original list cursor', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(respond({ items: [], nextCursor: null })))
    vi.stubGlobal('fetch', fetch)
    await testsApi.list('p', '', '7', undefined, 'root')
    expect(fetch.mock.calls[0][0]).toContain('cursor=7&folderId=root')
    await executionApi.pageSets('p', 'opaque', 25, undefined, 'f')
    expect(fetch.mock.calls[1][0]).toContain('folderId=f')
    expect(fetch.mock.calls[1][0]).toContain('cursor=opaque')
  })
})
