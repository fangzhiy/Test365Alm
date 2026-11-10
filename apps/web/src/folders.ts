import { requestJson, type AccessRequestOptions } from './projectAccess'

export type FolderKind = 'TEST_PLAN' | 'TEST_LAB'
export type Folder = { id: string; kind: FolderKind; parentId: string | null; name: string; sortOrder: number; rowVersion: number }
export type Placement = { objectId: string; folderId: string | null; rowVersion: number }
export type FolderInput = Pick<Folder, 'parentId' | 'name' | 'sortOrder'>
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const invalid = (): never => { throw { code: 'INVALID_RESPONSE', message: '目录响应格式无效' } }
const folder = (v: unknown): Folder => {
  if (!record(v) || typeof v.id !== 'string' || !['TEST_PLAN', 'TEST_LAB'].includes(String(v.kind)) || (v.parentId !== null && typeof v.parentId !== 'string') || typeof v.name !== 'string' || !Number.isInteger(v.sortOrder) || !Number.isInteger(v.rowVersion) || Number(v.rowVersion) < 1) return invalid()
  return v as Folder
}
const placement = (v: unknown): Placement => {
  if (!record(v) || typeof v.objectId !== 'string' || (v.folderId !== null && typeof v.folderId !== 'string') || !Number.isInteger(v.rowVersion) || Number(v.rowVersion) < 1) return invalid()
  return v as Placement
}
const path = (project: string, suffix: string) => `/api/v1/projects/${encodeURIComponent(project)}${suffix}`
const write = (method: string, body?: unknown, version?: number): RequestInit => ({ method, headers: { 'Content-Type': 'application/json', ...(version ? { 'If-Match': `"${version}"` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
export const foldersApi = {
  async tree(project: string, kind: FolderKind, options?: AccessRequestOptions) {
    const body = await requestJson(path(project, `/folders?kind=${kind}`), undefined, options)
    if (!Array.isArray(body)) return invalid()
    return body.map(folder)
  },
  async create(project: string, kind: FolderKind, input: FolderInput, options: AccessRequestOptions) {
    return folder(await requestJson(path(project, '/folders'), write('POST', { ...input, kind }), options))
  },
  async update(project: string, current: Folder, input: FolderInput, options?: AccessRequestOptions) {
    return folder(await requestJson(path(project, `/folders/${encodeURIComponent(current.id)}`), write('PUT', input, current.rowVersion), options))
  },
  async remove(project: string, current: Folder, options?: AccessRequestOptions) {
    await requestJson(path(project, `/folders/${encodeURIComponent(current.id)}`), write('DELETE', undefined, current.rowVersion), options)
  },
  async location(project: string, kind: FolderKind, id: string, options?: AccessRequestOptions) {
    return placement(await requestJson(path(project, `/folder-placements/${kind}/${encodeURIComponent(id)}`), undefined, options))
  },
  async place(project: string, kind: FolderKind, current: Placement, folderId: string | null, options?: AccessRequestOptions) {
    return placement(await requestJson(path(project, `/folder-placements/${kind}/${encodeURIComponent(current.objectId)}`), write('PUT', { folderId }, current.rowVersion), options))
  },
}
