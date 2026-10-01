export type ProjectRole = 'PROJECT_ADMIN' | 'PROJECT_MEMBER' | 'PROJECT_VIEWER'

export type Tenant = {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'
  rowVersion: number
  /** Server-authoritative capability; never infer create access from project roles. */
  canCreateProject: boolean
}

export type Domain = {
  id: string
  tenantId: string
  name: string
  status: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'
  rowVersion?: number
}

export type Project = {
  id: string
  tenantId: string
  domainId?: string | null
  code: string
  name: string
  state: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'
  rowVersion?: number
}

export type ProjectMember = {
  principalId: string
  displayName: string
  roles: ProjectRole[]
  state: 'ACTIVE' | 'REVOKED' | 'DISABLED'
  rowVersion?: number
  authorizationVersion?: number
  validUntil?: string | null
}

export type MemberCandidate = { principalId: string; displayName: string }

export type ProjectAccess = {
  tenantId: string
  projectId: string
  principalId: string
  roles: ProjectRole[]
  permissions: string[]
}

export type AccessError = { code: string; message: string }
export type CreateProjectInput = { tenantId: string; domainId: string; code: string; name: string }
export type AccessRequestOptions = { signal?: AbortSignal }

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const isNonNegativeInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0
const isPositiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 1
const isRole = (value: unknown): value is ProjectRole => value === 'PROJECT_ADMIN' || value === 'PROJECT_MEMBER' || value === 'PROJECT_VIEWER'

const parseError = async (response: Response): Promise<AccessError> => {
  try {
    const body: unknown = await response.json()
    if (isRecord(body) && isString(body.code) && isString(body.message)) return { code: body.code, message: body.message }
  } catch { /* Fall through to a status based message. */ }
  return { code: `HTTP_${response.status}`, message: `请求失败（${response.status}）` }
}

const idempotencyKey = () => typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`

type CsrfResponse = { headerName: string; token: string }

const csrfToken = async (signal?: AbortSignal): Promise<CsrfResponse> => {
  const response = await fetch('/api/v1/csrf', { credentials: 'same-origin', signal })
  if (!response.ok) throw { code: 'CSRF_UNAVAILABLE', message: '无法取得写操作所需的安全令牌' } satisfies AccessError
  const body: unknown = await response.json()
  if (!isRecord(body) || !isString(body.headerName) || !isString(body.token)) {
    throw { code: 'INVALID_CSRF_RESPONSE', message: '服务返回了无效安全令牌' } satisfies AccessError
  }
  return { headerName: body.headerName, token: body.token }
}

const requestJson = async (input: RequestInfo | URL, init?: RequestInit, options?: AccessRequestOptions): Promise<unknown> => {
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers = new Headers(init?.headers)
  const signal = options?.signal ?? init?.signal ?? undefined
  if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
    const csrf = await csrfToken(signal)
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch(input, { credentials: 'same-origin', ...init, headers, signal })
  if (!response.ok) throw await parseError(response)
  try { return await response.json() } catch { throw { code: 'INVALID_JSON', message: '服务返回了无效 JSON' } satisfies AccessError }
}

const parseTenant = (value: unknown): Tenant | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.code) || !isString(value.name) || !isNonNegativeInteger(value.rowVersion) || typeof value.canCreateProject !== 'boolean') return null
  const status = value.status
  if (status !== 'ACTIVE' && status !== 'SUSPENDED' && status !== 'ARCHIVED') return null
  return { id: value.id, code: value.code, name: value.name, status, rowVersion: value.rowVersion, canCreateProject: value.canCreateProject }
}

const parseProject = (value: unknown): Project | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.tenantId) || !isString(value.code) || !isString(value.name) || !isNonNegativeInteger(value.rowVersion)) return null
  const state = value.state
  if (state !== 'ACTIVE' && state !== 'SUSPENDED' && state !== 'ARCHIVED') return null
  if (!(typeof value.domainId === 'string' || value.domainId === null)) return null
  return { id: value.id, tenantId: value.tenantId, domainId: value.domainId, code: value.code, name: value.name, state, rowVersion: value.rowVersion }
}

const parseDomain = (value: unknown): Domain | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.tenantId) || !isString(value.name) || !isNonNegativeInteger(value.rowVersion)) return null
  const status = value.status
  if (status !== 'ACTIVE' && status !== 'SUSPENDED' && status !== 'ARCHIVED') return null
  return { id: value.id, tenantId: value.tenantId, name: value.name, status, rowVersion: value.rowVersion }
}

const parseMember = (value: unknown): ProjectMember | null => {
  if (!isRecord(value) || !isString(value.principalId) || !isString(value.displayName) || !Array.isArray(value.roles) || !value.roles.every(isRole) || !isPositiveInteger(value.authorizationVersion)) return null
  const state = value.state
  if (state !== 'ACTIVE' && state !== 'REVOKED' && state !== 'DISABLED') return null
  if (!(value.validUntil === undefined || value.validUntil === null || typeof value.validUntil === 'string')) return null
  return {
    principalId: value.principalId,
    displayName: value.displayName,
    roles: value.roles,
    state,
    rowVersion: typeof value.rowVersion === 'number' ? value.rowVersion : undefined,
    authorizationVersion: value.authorizationVersion,
    validUntil: typeof value.validUntil === 'string' ? value.validUntil : value.validUntil === null ? null : undefined,
  }
}

const parseCandidate = (value: unknown): MemberCandidate | null => {
  if (!isRecord(value) || !isString(value.principalId) || !isString(value.displayName)) return null
  return { principalId: value.principalId, displayName: value.displayName }
}

const parseList = <T>(body: unknown, parser: (value: unknown) => T | null): T[] => {
  const values = Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : null
  if (!values) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效列表' } satisfies AccessError
  const parsed = values.map(parser)
  if (parsed.some((value) => value === null)) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效对象' } satisfies AccessError
  return parsed as T[]
}

export const accessApi = {
  async listTenants(options?: AccessRequestOptions): Promise<Tenant[]> { return parseList(await requestJson('/api/v1/tenants', undefined, options), parseTenant) },
  async listDomains(tenantId: string, options?: AccessRequestOptions): Promise<Domain[]> { return parseList(await requestJson(`/api/v1/domains?tenantId=${encodeURIComponent(tenantId)}`, undefined, options), parseDomain) },
  async createProject(input: CreateProjectInput, options?: AccessRequestOptions): Promise<Project> {
    const body = await requestJson('/api/v1/projects', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() }, body: JSON.stringify(input) }, options)
    const project = parseProject(body)
    if (!project) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效项目对象' } satisfies AccessError
    return project
  },
  async listProjects(tenantId: string, options?: AccessRequestOptions): Promise<Project[]> { return parseList(await requestJson(`/api/v1/projects?tenantId=${encodeURIComponent(tenantId)}`, undefined, options), parseProject) },
  async getProject(projectId: string, options?: AccessRequestOptions): Promise<Project> {
    const project = parseProject(await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}`, undefined, options))
    if (!project) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效项目对象' } satisfies AccessError
    return project
  },
  async updateProject(projectId: string, name: string, rowVersion: number, options?: AccessRequestOptions): Promise<Project> {
    const body = await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() }, body: JSON.stringify({ name, rowVersion }) }, options)
    const project = parseProject(body)
    if (!project) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效项目对象' } satisfies AccessError
    return project
  },
  async listMembers(projectId: string, options?: AccessRequestOptions): Promise<ProjectMember[]> { return parseList(await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}/members`, undefined, options), parseMember) },
  async listMemberCandidates(projectId: string, options?: AccessRequestOptions): Promise<MemberCandidate[]> { return parseList(await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}/member-candidates`, undefined, options), parseCandidate) },
  async getPermissions(projectId: string, options?: AccessRequestOptions): Promise<ProjectAccess> {
    const body = await requestJson(`/api/v1/me/permissions?projectId=${encodeURIComponent(projectId)}`, undefined, options)
    if (!isRecord(body) || !isString(body.tenantId) || !isString(body.projectId) || !isString(body.principalId) || !Array.isArray(body.roles) || !body.roles.every(isRole) || !Array.isArray(body.permissions) || !body.permissions.every(isString)) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效权限对象' } satisfies AccessError
    return { tenantId: body.tenantId, projectId: body.projectId, principalId: body.principalId, roles: body.roles, permissions: body.permissions }
  },
  async setMemberRoles(projectId: string, principalId: string, roles: ProjectRole[], authorizationVersion = 0, options?: AccessRequestOptions): Promise<ProjectMember> {
    const body = await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(principalId)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() }, body: JSON.stringify({ roles, authorizationVersion }) }, options)
    const member = parseMember(body)
    if (!member) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效成员对象' } satisfies AccessError
    return member
  },
  async revokeMember(projectId: string, principalId: string, authorizationVersion: number, options?: AccessRequestOptions): Promise<ProjectMember> {
    const body = await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(principalId)}`, { method: 'DELETE', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() }, body: JSON.stringify({ authorizationVersion }) }, options)
    const member = parseMember(body)
    if (!member) throw { code: 'INVALID_RESPONSE', message: '服务返回了无效成员对象' } satisfies AccessError
    return member
  },
}
