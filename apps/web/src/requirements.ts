import { requestJson, requestJsonWithResponse } from './projectAccess'
import type { AccessError, AccessRequestOptions } from './projectAccess'

// Re-export the transport helper for requirement-specific contract tests and
// callers that need to assert HTTP/business error separation.
export { requestJson }

export type RequirementRevision = {
  id: string
  revisionNumber: number
  title: string
  body: string
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  createdAt: string
  createdBy: string
}

export type Requirement = {
  id: string
  projectId: string
  displayNumber: string
  title: string
  body: string
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  revisionNumber: number
  rowVersion: number
  etag?: string
  createdAt: string
  createdBy: string
  currentRevisionId: string
}

export type RequirementPage = { items: Requirement[]; nextCursor?: string | null }
export type RequirementInput = { title: string; body: string }

export const newIdempotencyKey = () => typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string'
const isPositiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 1
const isPriority = (value: unknown): value is Requirement['priority'] => value === 'LOW' || value === 'MEDIUM' || value === 'HIGH' || value === 'CRITICAL'

const parseRequirement = (value: unknown): Requirement | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.projectId) || !isPositiveInteger(value.rowVersion)
    || !isString(value.createdAt) || !isString(value.createdBy) || !isString(value.currentRevisionId)) return null
  const revision = isRecord(value.revision) ? value.revision : null
  const title = isString(value.title) ? value.title : revision && isString(revision.title) ? revision.title : null
  const body = isString(value.body) ? value.body : revision && isString(revision.body) ? revision.body : ''
  if (!title) return null
  const revisionNumber = isPositiveInteger(value.revisionNumber) ? value.revisionNumber : revision && isPositiveInteger(revision.revisionNumber) ? revision.revisionNumber : null
  if (!revisionNumber) return null
  const displayNumber = isPositiveInteger(value.displayNumber) ? String(value.displayNumber) : isString(value.displayNumber) ? value.displayNumber : isPositiveInteger(value.number) ? String(value.number) : isString(value.number) ? value.number : null
  if (!displayNumber) return null
  if (!isPriority(value.priority)) return null
  const priority = value.priority
  return { id: value.id, projectId: value.projectId, displayNumber, title, body, priority, revisionNumber, rowVersion: value.rowVersion, etag: isString(value.etag) ? value.etag : undefined, createdAt: value.createdAt, createdBy: value.createdBy, currentRevisionId: value.currentRevisionId }
}

const parseRevision = (value: unknown): RequirementRevision | null => {
  const revisionId = isRecord(value) && (isString(value.id) ? value.id : isString(value.revisionId) ? value.revisionId : null)
  const createdBy = isRecord(value) && (isString(value.createdBy) ? value.createdBy : isString(value.authorPrincipalId) ? value.authorPrincipalId : isString(value.author) ? value.author : null)
  if (!isRecord(value) || !revisionId || !isPositiveInteger(value.revisionNumber) || !isString(value.title) || !isString(value.body) || !isPriority(value.priority) || !isString(value.createdAt) || !createdBy) return null
  return { id: revisionId, revisionNumber: value.revisionNumber, title: value.title, body: value.body, priority: value.priority, createdAt: value.createdAt, createdBy }
}

const parsePage = (body: unknown): RequirementPage => {
  const items = Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : null
  if (!items) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效需求列表' } satisfies AccessError
  const parsed = items.map(parseRequirement)
  if (parsed.some((item) => item === null)) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效需求对象' } satisfies AccessError
  return { items: parsed as Requirement[], nextCursor: isRecord(body) && (body.nextCursor === null || isString(body.nextCursor)) ? body.nextCursor : null }
}

const parseOne = (body: unknown, etag?: string): Requirement => {
  const parsed = parseRequirement(body)
  if (!parsed) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效需求对象' } satisfies AccessError
  return etag ? { ...parsed, etag } : parsed
}

const responseEtag = (response: Response) => response.headers.get('ETag') ?? response.headers.get('etag') ?? undefined
const pendingIntentKeys = new Map<string, string>()
const intentKey = (scope: string, input: unknown) => `${scope}\u0000${JSON.stringify(input)}`

export const requirementsApi = {
  async list(projectId: string, options?: AccessRequestOptions): Promise<RequirementPage> {
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/requirements?limit=50`, undefined, options)
    return parsePage(result.body)
  },
  async get(projectId: string, requirementId: string, options?: AccessRequestOptions): Promise<Requirement> {
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/requirements/${encodeURIComponent(requirementId)}`, undefined, options)
    return parseOne(result.body, responseEtag(result.response))
  },
  async create(projectId: string, input: RequirementInput, options?: AccessRequestOptions): Promise<Requirement> {
    const intent = intentKey(`create:${projectId}`, input)
    const key = options?.idempotencyKey ?? pendingIntentKeys.get(intent) ?? newIdempotencyKey()
    if (!options?.idempotencyKey) pendingIntentKeys.set(intent, key)
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/requirements`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify({ title: input.title, body: input.body }) }, options)
    const parsed = parseOne(result.body, responseEtag(result.response)); pendingIntentKeys.delete(intent); return parsed
  },
  async update(projectId: string, requirement: Requirement, input: RequirementInput, options?: AccessRequestOptions): Promise<Requirement> {
    if (!requirement.etag) throw { code: 'MISSING_ETAG', message: '需求版本标识不可用，请先刷新需求' } satisfies AccessError
    const intent = intentKey(`update:${projectId}:${requirement.id}:${requirement.etag}`, input)
    const key = options?.idempotencyKey ?? pendingIntentKeys.get(intent) ?? newIdempotencyKey()
    if (!options?.idempotencyKey) pendingIntentKeys.set(intent, key)
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/requirements/${encodeURIComponent(requirement.id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'If-Match': requirement.etag }, body: JSON.stringify({ title: input.title, body: input.body }) }, options)
    const parsed = parseOne(result.body, responseEtag(result.response)); pendingIntentKeys.delete(intent); return parsed
  },
  async revisions(projectId: string, requirementId: string, options?: AccessRequestOptions): Promise<RequirementRevision[]> {
    const body = (await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/requirements/${encodeURIComponent(requirementId)}/revisions`, undefined, options)).body
    const values = Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : null
    if (!values) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效修订列表' } satisfies AccessError
    const parsed = values.map(parseRevision)
    if (parsed.some((item) => item === null)) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效修订对象' } satisfies AccessError
    return parsed as RequirementRevision[]
  },
}
