import { requestJson } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'

export type FieldDefinition = { key: string; type: 'TEXT' | 'NUMBER' | 'ENUM'; required: boolean; maxLength?: number; precision?: number; scale?: number; minimum?: string; maximum?: string; options?: string[]; defaultValue?: string | null }
export type Field = { id: string; projectId: string; kind: 'CUSTOM'; rowVersion: number; definition: FieldDefinition }
export type Link = { id: string; projectId: string; requirementId: string; testCaseId: string; requirementRevisionId: string; testRevisionId: string; rowVersion: number; state: 'ACTIVE' | 'INACTIVE' }
export type Coverage = { requirementId: string; linkedTests: number; activeTests: number; passedTests: number; rule: string }
export type Page<T> = { items: T[]; nextCursor: string | null }
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const string = (v: unknown): v is string => typeof v === 'string' && v.length > 0
const version = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const invalid = (): never => { throw { code: 'INVALID_RESPONSE', message: '服务返回了无效字段或追踪响应' } }
function field(v: unknown): Field {
  if (!record(v) || !string(v.id) || !string(v.projectId) || v.kind !== 'CUSTOM' || !version(v.rowVersion) || !record(v.definition)) return invalid()
  const d = v.definition
  if (!string(d.key) || !['TEXT','NUMBER','ENUM'].includes(String(d.type)) || typeof d.required !== 'boolean') return invalid()
  if (d.type === 'TEXT' && !version(d.maxLength)) return invalid()
  if (d.type === 'NUMBER' && (!version(d.precision) || typeof d.scale !== 'number' || !Number.isInteger(d.scale) || d.scale < 0)) return invalid()
  if (d.type === 'ENUM' && (!Array.isArray(d.options) || !d.options.length || !d.options.every(string))) return invalid()
  for (const k of ['minimum','maximum']) if (d[k] !== undefined && typeof d[k] !== 'string') return invalid()
  if (d.defaultValue !== undefined && d.defaultValue !== null && typeof d.defaultValue !== 'string') return invalid()
  return { id: v.id, projectId: v.projectId, kind: 'CUSTOM', rowVersion: v.rowVersion, definition: d as FieldDefinition }
}
function link(v: unknown): Link {
  if (!record(v) || !['id','projectId','requirementId','testCaseId','requirementRevisionId','testRevisionId'].every(k => string(v[k])) || !version(v.rowVersion) || (v.state !== 'ACTIVE' && v.state !== 'INACTIVE')) return invalid()
  return v as Link
}
function page<T>(v: unknown, parse: (v: unknown) => T): Page<T> {
  if (!record(v) || !Array.isArray(v.items) || !(v.nextCursor === null || string(v.nextCursor))) return invalid()
  return { items: v.items.map(parse), nextCursor: v.nextCursor }
}
const root = (p: string) => `/api/v1/projects/${encodeURIComponent(p)}`
const json = (method: string, body: unknown, tag?: number): RequestInit => ({ method, headers: { 'Content-Type': 'application/json', ...(tag ? { 'If-Match': `"${tag}"` } : {}) }, body: JSON.stringify(body) })
export const coreApi = {
  fields: async (p: string, cursor: string | null, o?: AccessRequestOptions) => page(await requestJson(`${root(p)}/field-definitions${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, undefined, o), field),
  system: async (p: string, o?: AccessRequestOptions): Promise<string[]> => {
    const v = await requestJson(`${root(p)}/field-definitions/system`, undefined, o)
    if (!Array.isArray(v) || !v.every(x => record(x) && string(x.key) && x.kind === 'SYSTEM')) return invalid()
    return v.map(x => x.key)
  },
  createField: async (p: string, d: FieldDefinition, o?: AccessRequestOptions) => field(await requestJson(`${root(p)}/field-definitions`, json('POST', d), o)),
  reviseField: async (p: string, f: Field, d: FieldDefinition, o?: AccessRequestOptions) => field(await requestJson(`${root(p)}/field-definitions/${f.id}`, json('PATCH', d, f.rowVersion), o)),
  validate: async (p: string, f: Field, value: string, o?: AccessRequestOptions) => {
    const v = await requestJson(`${root(p)}/field-definitions/${f.id}/validate`, json('POST', { revision: f.rowVersion, value }), o)
    if (!record(v) || v.valid !== true || v.revision !== f.rowVersion || !(v.value === null || typeof v.value === 'string')) return invalid()
    return v
  },
  links: async (p: string, r: string, cursor: string | null, o?: AccessRequestOptions) => page(await requestJson(`${root(p)}/trace-links?requirementId=${encodeURIComponent(r)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, undefined, o), link),
  createLink: async (p: string, requirementId: string, testCaseId: string, o?: AccessRequestOptions) => link(await requestJson(`${root(p)}/trace-links`, json('POST', { requirementId, testCaseId }), o)),
  invalidate: async (p: string, l: Link, o?: AccessRequestOptions) => link(await requestJson(`${root(p)}/trace-links/${l.id}/invalidate`, json('POST', {}, l.rowVersion), o)),
  coverage: async (p: string, r: string, o?: AccessRequestOptions): Promise<Coverage> => {
    const v = await requestJson(`${root(p)}/coverage?requirementId=${encodeURIComponent(r)}`, undefined, o)
    if (!record(v) || v.requirementId !== r || ![v.linkedTests,v.activeTests,v.passedTests].every(x => typeof x === 'number' && Number.isSafeInteger(x) && x >= 0) || v.rule !== 'CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE' || Number(v.passedTests) + Number(v.activeTests) > Number(v.linkedTests)) return invalid()
    return v as Coverage
  },
}
