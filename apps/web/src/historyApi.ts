import { requestJson } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'

export type HistoryObjectType = 'test_case' | 'defect' | 'run'
export type HistoryChange = { field: string; before: string | null; after: string | null }
export type HistoryEntry = {
  id: string
  at: string
  actor: { id: string; displayName: string | null }
  action: string
  revision: number | null
  changes: HistoryChange[]
}
export type HistoryPage = { items: HistoryEntry[]; nextCursor: string | null }

const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.length > 0
const nullableString = (v: unknown) => v === null || typeof v === 'string'
const invalid = (): never => { throw { code: 'INVALID_RESPONSE', message: '审计历史接口返回了无效数据' } }

function entry(v: unknown): HistoryEntry {
  if (!record(v) || !text(v.id) || !text(v.at) || !Number.isFinite(Date.parse(v.at)) || !text(v.action)
    || !record(v.actor) || !text(v.actor.id) || !nullableString(v.actor.displayName)
    || !(v.revision === null || typeof v.revision === 'number' && Number.isSafeInteger(v.revision) && v.revision >= 0)
    || !Array.isArray(v.changes)) return invalid()
  for (const change of v.changes) {
    if (!record(change) || !text(change.field) || !nullableString(change.before) || !nullableString(change.after)) return invalid()
  }
  return v as HistoryEntry
}

export const historyApi = {
  async list(projectId: string, objectType: HistoryObjectType, objectId: string, cursor: string | null = null, options?: AccessRequestOptions): Promise<HistoryPage> {
    const path = `/api/v1/projects/${encodeURIComponent(projectId)}/history/${objectType}/${encodeURIComponent(objectId)}`
    const v = await requestJson(`${path}?limit=25${cursor === null ? '' : `&cursor=${encodeURIComponent(cursor)}`}`, undefined, options)
    if (!record(v) || !Array.isArray(v.items) || !(v.nextCursor === null || text(v.nextCursor))) return invalid()
    return { items: v.items.map(entry), nextCursor: v.nextCursor }
  },
}
