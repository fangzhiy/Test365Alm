import { requestJson, type AccessError, type AccessRequestOptions } from './projectAccess'
import { newIdempotencyKey } from './requirements'

export type ExecutionState = 'RUNNING' | 'PAUSED' | 'FINISHED'
export type StepOutcome = 'NOT_RUN' | 'PASS' | 'FAIL' | 'BLOCKED'
export type AttemptOutcome = 'PASS' | 'FAIL' | 'BLOCKED' | null

export type TestSet = { id: string; projectId: string; name: string; description: string; rowVersion?: number; instanceCount?: number }
export type TestInstance = { id: string; projectId?: string; setId: string; testCaseId: string; testRevisionId: string; title: string; revisionNumber: number; displayNumber?: string; assigneeId?: string | null; assigneeName?: string | null; assignmentVersion?: number; runId?: string | null; attemptId?: string | null; status?: ExecutionState | 'NOT_RUN'; conclusion?: AttemptOutcome; executionDate?: string | null }
// A run step carries its own optimistic-lock version.  The attempt version
// protects state transitions, but it is not interchangeable with a step
// version: two step updates may advance different rows independently.
export type RunStep = { stepKey: string; ordinal: number; action: string; expected: string; actual: string; outcome: StepOutcome; rowVersion?: number; runStepId?: string; executedAt?: string | null; updatedAt?: string | null }
export type RunAttempt = { id: string; attemptNo: number; state: ExecutionState; outcome: AttemptOutcome; rowVersion?: number; steps: RunStep[]; updatedAt?: string; startedAt?: string; finishedAt?: string | null }
export type Run = { id: string; projectId: string; instanceId: string; manifestId: string; attemptId: string; state: ExecutionState; outcome: AttemptOutcome; rowVersion: number; attempt?: RunAttempt; manifest?: { title: string; description: string; preconditions: string; steps: RunStep[]; formatVersion?: string; rulesVersion?: string; assigneeId?: string | null; assigneeName?: string | null } }
export type Assignment = { instanceId: string; assigneeId: string | null; assigneeName: string | null; assignmentVersion: number }
export type Assignee = { id: string; displayName: string }
export type GridFilters = { status?: ExecutionState | 'NOT_RUN'; assigneeId?: string }
export type Page<T> = { items: T[]; nextCursor: string | null }
export type RunSummary = {
  totalInstances: number
  unrunInstances: number
  activeAttempts: number
  latestCompletedPass: number
  latestCompletedFail: number
  latestCompletedBlocked: number
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string'
const isPositiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 1
const invalid = (message: string): AccessError => ({ code: 'INVALID_RESPONSE', message })
const listBody = (body: unknown): unknown[] => Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : (() => { throw invalid('服务返回了无效列表') })()
const stringOrEmpty = (value: unknown): string | null => value === undefined || value === null ? '' : isString(value) ? value : null

const nullableString = (value: unknown): value is string | null => value === null || isString(value)
const serverTime = (value: unknown): value is string | null => value === null || (isString(value) && /^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value)))

const parseSet = (value: unknown): TestSet | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.projectId) || !isString(value.name)) return null
  const description = stringOrEmpty(value.description)
  if (description === null) return null
  return { id: value.id, projectId: value.projectId, name: value.name, description, rowVersion: isPositiveInteger(value.rowVersion) ? value.rowVersion : undefined, instanceCount: isPositiveInteger(value.instanceCount) ? value.instanceCount : undefined }
}

const parseInstance = (value: unknown): TestInstance | null => {
  const revisionNumber = isRecord(value) && isPositiveInteger(value.revisionNumber) ? value.revisionNumber : isRecord(value) ? value.revisionNo : undefined
  if (!isRecord(value) || !isString(value.id) || !(isString(value.setId) || isString(value.testSetId)) || !isString(value.testCaseId) || !(isString(value.testRevisionId) || isString(value.revisionId)) || !isString(value.title) || !isPositiveInteger(revisionNumber)) return null
  return { id: value.id, projectId: isString(value.projectId) ? value.projectId : undefined, setId: isString(value.setId) ? value.setId : value.testSetId as string, testCaseId: value.testCaseId, testRevisionId: isString(value.testRevisionId) ? value.testRevisionId : value.revisionId as string, title: value.title, revisionNumber, displayNumber: isString(value.displayNumber) ? value.displayNumber : undefined }
}

const parseAssignment = (value: unknown): Assignment | null => {
  if (!isRecord(value) || !isString(value.instanceId) || !nullableString(value.assigneeId) || !nullableString(value.assigneeName)
    || (value.assigneeId === null) !== (value.assigneeName === null) || !isPositiveInteger(value.assignmentVersion)) return null
  return { instanceId: value.instanceId, assigneeId: value.assigneeId, assigneeName: value.assigneeName, assignmentVersion: value.assignmentVersion }
}
const parseGridRow = (value: unknown, projectId: string, setId: string): TestInstance | null => {
  if (!isRecord(value)) return null
  const instance = parseInstance(value.instance)
  if (!instance || instance.projectId !== projectId || instance.setId !== setId) return null
  const assignment = parseAssignment({ ...value, instanceId: instance.id })
  if (!assignment || !nullableString(value.runId) || !nullableString(value.attemptId) || !serverTime(value.executionDate)
    || !['NOT_RUN','RUNNING','PAUSED','FINISHED'].includes(String(value.status))
    || !(value.conclusion === null || ['PASS','FAIL','BLOCKED'].includes(String(value.conclusion)))
    || (value.status === 'NOT_RUN' ? value.runId !== null || value.attemptId !== null || value.executionDate !== null
      : !value.runId || !value.attemptId || !value.executionDate)) return null
  return { ...instance, ...assignment, runId: value.runId, attemptId: value.attemptId,
    status: value.status as TestInstance['status'], conclusion: value.conclusion as AttemptOutcome, executionDate: value.executionDate }
}

const parseOutcome = (value: unknown): AttemptOutcome => value === undefined || value === null ? null : value === 'PASS' || value === 'FAIL' || value === 'BLOCKED' ? value : null
const parseState = (value: unknown): ExecutionState | null => value === 'RUNNING' || value === 'PAUSED' || value === 'FINISHED' ? value : null
const parseStep = (value: unknown): RunStep | null => {
  if (!isRecord(value) || !isString(value.stepKey) || !isPositiveInteger(value.ordinal) || !isString(value.action) || !isString(value.expected) || !isPositiveInteger(value.rowVersion)) return null
  const actual = value.actual === undefined ? value.actualResult : value.actual
  const outcome = value.outcome === undefined ? value.conclusion : value.outcome
  if (!isString(actual) || !(outcome === 'NOT_RUN' || outcome === 'PASS' || outcome === 'FAIL' || outcome === 'BLOCKED')) return null
  if ((value.executedAt !== undefined && !serverTime(value.executedAt)) || (value.updatedAt !== undefined && !serverTime(value.updatedAt)) || (value.runStepId !== undefined && value.runStepId !== null && !isString(value.runStepId))) return null
  return { stepKey: value.stepKey, ordinal: value.ordinal, action: value.action, expected: value.expected, actual, outcome, rowVersion: value.rowVersion, runStepId: isString(value.runStepId) ? value.runStepId : undefined, executedAt: value.executedAt as string | null | undefined, updatedAt: value.updatedBy === null ? null : value.updatedAt as string | null | undefined }
}
const parseManifestStep = (value: unknown): RunStep | null => {
  if (!isRecord(value) || !isString(value.stepKey) || !isPositiveInteger(value.ordinal) || !isString(value.action) || !isString(value.expected)) return null
  return { stepKey: value.stepKey, ordinal: value.ordinal, action: value.action, expected: value.expected, actual: '', outcome: 'NOT_RUN' }
}
const parseAttempt = (value: unknown): RunAttempt | null => {
  if (!isRecord(value) || !isString(value.id) || !isPositiveInteger(value.attemptNo) || !isPositiveInteger(value.rowVersion)) return null
  const state = parseState(value.state ?? value.status); const outcome = parseOutcome(value.outcome ?? value.conclusion); const values = value.steps === undefined ? [] : value.steps
  if (!state || !Array.isArray(values)) return null
  const steps = values.map(parseStep); if (steps.some((step) => step === null)) return null
  return { id: value.id, attemptNo: value.attemptNo, state, outcome, rowVersion: value.rowVersion, steps: steps as RunStep[], startedAt:isString(value.startedAt)?value.startedAt:undefined, finishedAt:nullableString(value.finishedAt)?value.finishedAt:undefined, updatedAt: isString(value.updatedAt) ? value.updatedAt : undefined }
}
const parseRun = (value: unknown): Run | null => {
  if (!isRecord(value)) return null
  if (isRecord(value.run)) {
    const base = parseRun(value.run); if (!base) return null
    const hasCurrentAttempt = value.currentAttempt !== undefined && value.currentAttempt !== null
    const attempt = hasCurrentAttempt ? parseAttempt(value.currentAttempt) : base.attempt
    if (hasCurrentAttempt && !attempt) return null
    const manifestSteps = isRecord(value.manifest) && Array.isArray(value.manifest.steps) ? value.manifest.steps.map(parseManifestStep) : null
    if (manifestSteps?.some((step) => step === null)) return null
    const manifest = isRecord(value.manifest) && isString(value.manifest.title) && isString(value.manifest.description) && isString(value.manifest.preconditions) && manifestSteps
      ? { title: value.manifest.title, description: value.manifest.description, preconditions: value.manifest.preconditions, formatVersion: isString(value.manifest.formatVersion) ? value.manifest.formatVersion : undefined, rulesVersion: isString(value.manifest.rulesVersion) ? value.manifest.rulesVersion : undefined, steps: manifestSteps as RunStep[], assigneeId: nullableString(value.manifest.assigneeId) ? value.manifest.assigneeId : undefined, assigneeName: nullableString(value.manifest.assigneeName) ? value.manifest.assigneeName : undefined }
      : base.manifest
    return { ...base, attempt: attempt ?? undefined, attemptId: attempt?.id ?? base.attemptId, manifest }
  }
  const instanceId = isString(value.instanceId) ? value.instanceId : isString(value.testInstanceId) ? value.testInstanceId : null
  if (!isString(value.id) || !isString(value.projectId) || !instanceId || !isString(value.manifestId) || !isPositiveInteger(value.rowVersion)) return null
  const state = parseState(value.state ?? value.status); const outcome = parseOutcome(value.outcome ?? value.conclusion); if (!state) return null
  const attempt = value.attempt === undefined ? undefined : parseAttempt(value.attempt); if (value.attempt !== undefined && !attempt) return null
  return { id: value.id, projectId: value.projectId, instanceId, manifestId: value.manifestId, attemptId: isString(value.attemptId) ? value.attemptId : attempt?.id ?? '', state, outcome, rowVersion: value.rowVersion, attempt: attempt ?? undefined }
}
const parse = <T>(body: unknown, parser: (value: unknown) => T | null, message: string): T => { const value = parser(body); if (!value) throw invalid(message); return value }
const parseList = <T>(body: unknown, parser: (value: unknown) => T | null, message: string): T[] => { const result = listBody(body).map(parser); if (result.some((value) => value === null)) throw invalid(message); return result as T[] }
const parsePage = <T>(body: unknown, parser: (value: unknown) => T | null, message: string): Page<T> => {
  if (!isRecord(body) || !Array.isArray(body.items) || (body.nextCursor !== null && body.nextCursor !== undefined && !isString(body.nextCursor))) throw invalid(message)
  const items = body.items.map(parser)
  if (items.some((value) => value === null)) throw invalid(message)
  return { items: items as T[], nextCursor: body.nextCursor === undefined ? null : body.nextCursor as string | null }
}
const parseCount = (value: unknown): number | null => typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null
const parseSummary = (body: unknown): RunSummary => {
  if (!isRecord(body)) throw invalid('服务返回了无效运行汇总')
  const totalInstances = parseCount(body.totalInstances); const unrunInstances = parseCount(body.unrunInstances); const activeAttempts = parseCount(body.activeAttempts)
  const latestCompletedPass = parseCount(body.latestCompletedPass ?? body.latestPass); const latestCompletedFail = parseCount(body.latestCompletedFail ?? body.latestFail); const latestCompletedBlocked = parseCount(body.latestCompletedBlocked ?? body.latestBlocked)
  if (totalInstances === null || unrunInstances === null || activeAttempts === null || latestCompletedPass === null || latestCompletedFail === null || latestCompletedBlocked === null) throw invalid('服务返回了无效运行汇总')
  return { totalInstances, unrunInstances, activeAttempts, latestCompletedPass, latestCompletedFail, latestCompletedBlocked }
}
const path = (projectId: string, suffix: string) => `/api/v1/projects/${encodeURIComponent(projectId)}${suffix}`
const query = (params: Record<string, string | number | undefined>) => {
  const value = new URLSearchParams()
  Object.entries(params).forEach(([key, entry]) => { if (entry !== undefined && entry !== '') value.set(key, String(entry)) })
  const encoded = value.toString()
  return encoded ? `?${encoded}` : ''
}
const write = (input: RequestInfo | URL, body: unknown, options?: AccessRequestOptions) => requestJson(input, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() })
const requireVersion = (version: number) => { if (!isPositiveInteger(version)) throw invalid('尝试版本是必填的正整数'); return version }
const versionedWrite = (input: RequestInfo | URL, version: number, options?: AccessRequestOptions) => {
  const expectedVersion = requireVersion(version)
  return requestJson(input, { method: 'POST', headers: { 'Content-Type': 'application/json', 'If-Match': `"${expectedVersion}"` }, body: JSON.stringify({ expectedVersion }) }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() })
}

export const executionApi = {
  async executionGrid(projectId: string, setId: string, cursor?: string | null, limit = 25, filters: GridFilters = {}, options?: AccessRequestOptions) {
    const body = await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}/execution-grid${query({ cursor: cursor ?? undefined, limit, ...filters })}`), undefined, options)
    return parsePage(body, value => parseGridRow(value, projectId, setId), '服务返回了无效执行网格')
  },
  async executionAssignees(projectId: string, cursor?: string | null, options?: AccessRequestOptions): Promise<Page<Assignee>> {
    return parsePage(await requestJson(path(projectId, `/execution-assignees${query({ cursor: cursor ?? undefined, limit:25 })}`), undefined, options), value =>
      isRecord(value) && isString(value.id) && isString(value.displayName) ? {id:value.id,displayName:value.displayName} : null, '服务返回了无效负责人列表')
  },
  async assignInstance(projectId: string, instanceId: string, assigneeId: string | null, version: number, options?: AccessRequestOptions) {
    const expectedVersion = requireVersion(version)
    const body = await requestJson(path(projectId, `/test-instances/${encodeURIComponent(instanceId)}/assignee`), {
      method:'PUT', headers:{'Content-Type':'application/json','If-Match':`"${expectedVersion}"`}, body:JSON.stringify({assigneeId,expectedVersion})
    }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() })
    const assignment = parse(body, parseAssignment, '服务返回了无效负责人版本')
    if (assignment.instanceId !== instanceId || assignment.assigneeId !== assigneeId || assignment.assignmentVersion !== expectedVersion + 1) throw invalid('服务返回了不一致的负责人版本')
    return assignment
  },
  async listSets(projectId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, '/test-sets'), undefined, options), parseSet, '服务返回了无效测试集列表') },
  async pageSets(projectId: string, cursor?: string | null, limit?: number, options?: AccessRequestOptions, folderId?: string) {
    const body = await requestJson(path(projectId, `/test-sets/page${query({ cursor: cursor ?? undefined, limit, folderId })}`), undefined, options)
    return parsePage(body, parseSet, '服务返回了无效测试集分页')
  },
  async createSet(projectId: string, input: { name: string; description: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, '/test-sets'), input, options), parseSet, '服务返回了无效测试集') },
  async getSet(projectId: string, setId: string, options?: AccessRequestOptions) { const body = await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}`), undefined, options); return parse(isRecord(body) && isRecord(body.testSet) ? body.testSet : body, parseSet, '服务返回了无效测试集') },
  async listInstances(projectId: string, setId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}/instances`), undefined, options), parseInstance, '服务返回了无效测试实例列表') },
  async pageInstances(projectId: string, setId: string, cursor?: string | null, limit?: number, options?: AccessRequestOptions) {
    const body = await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}/instances/page${query({ cursor: cursor ?? undefined, limit })}`), undefined, options)
    return parsePage(body, parseInstance, '服务返回了无效测试实例分页')
  },
  async addInstance(projectId: string, setId: string, input: { testCaseId: string; testRevisionId: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/test-sets/${encodeURIComponent(setId)}/instances`), input, options), parseInstance, '服务返回了无效测试实例') },
  async createRun(projectId: string, input: { instanceId: string; mode: 'MANUAL'; reason?: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, '/runs'), { testInstanceId: input.instanceId, instanceId: input.instanceId, mode: input.mode, ...(input.reason ? { reason: input.reason } : {}) }, options), parseRun, '服务返回了无效运行') },
  async listRuns(projectId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, '/runs'), undefined, options), parseRun, '服务返回了无效运行列表') },
  async pageRuns(projectId: string, filters?: { setId?: string } | string, cursor?: string | null, limit?: number, options?: AccessRequestOptions) {
    const setId = typeof filters === 'string' ? filters : filters?.setId
    const body = await requestJson(path(projectId, `/runs/page${query({ testSetId: setId, cursor: cursor ?? undefined, limit })}`), undefined, options)
    return parsePage(body, parseRun, '服务返回了无效运行分页')
  },
  async summary(projectId: string, filters?: { setId?: string } | string, options?: AccessRequestOptions) {
    const setId = typeof filters === 'string' ? filters : filters?.setId
    const body = await requestJson(path(projectId, `/runs/summary${query({ testSetId: setId })}`), undefined, options)
    return parseSummary(body)
  },
  async getRun(projectId: string, runId: string, options?: AccessRequestOptions) { return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}`), undefined, options), parseRun, '服务返回了无效运行') },
  async listAttempts(projectId: string, runId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts`), undefined, options), parseAttempt, '服务返回了无效尝试列表') },
  async pageAttempts(projectId: string, runId: string, cursor?: string | null, limit?: number, options?: AccessRequestOptions) {
    const body = await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/page${query({ cursor: cursor ?? undefined, limit })}`), undefined, options)
    return parsePage(body, parseAttempt, '服务返回了无效尝试分页')
  },
  async getAttempt(projectId: string, runId: string, attemptId: string, options?: AccessRequestOptions) { return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}`), undefined, options), parseAttempt, '服务返回了无效尝试') },
  async saveStep(projectId: string, runId: string, attemptId: string, stepKey: string, input: { outcome: StepOutcome; actual: string; rowVersion: number }, options?: AccessRequestOptions) {
    if (!isPositiveInteger(input.rowVersion)) throw invalid('步骤版本是必填的正整数')
    return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/steps/${encodeURIComponent(stepKey)}`), { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': `"${input.rowVersion}"` }, body: JSON.stringify({ actualResult: input.actual, actual: input.actual, conclusion: input.outcome, outcome: input.outcome, expectedVersion: input.rowVersion }) }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() }), parseAttempt, '服务返回了无效尝试')
  },
  async pause(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await versionedWrite(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/pause`), version, options), parseAttempt, '服务返回了无效尝试') },
  async resume(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await versionedWrite(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/resume`), version, options), parseAttempt, '服务返回了无效尝试') },
  async finish(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await versionedWrite(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/finish`), version, options), parseAttempt, '服务返回了无效尝试') },
  async rerun(projectId: string, runId: string, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts`), {}, options), parseAttempt, '服务返回了无效尝试') },
}
