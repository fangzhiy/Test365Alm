import { requestJson, type AccessError, type AccessRequestOptions } from './projectAccess'
import { newIdempotencyKey } from './requirements'

export type ExecutionState = 'RUNNING' | 'PAUSED' | 'FINISHED'
export type StepOutcome = 'NOT_RUN' | 'PASS' | 'FAIL' | 'BLOCKED'
export type AttemptOutcome = 'PASS' | 'FAIL' | 'BLOCKED' | null

export type TestSet = { id: string; projectId: string; name: string; description: string; rowVersion?: number; instanceCount?: number }
export type TestInstance = { id: string; projectId?: string; setId: string; testCaseId: string; testRevisionId: string; title: string; revisionNumber: number; displayNumber?: string }
export type RunStep = { stepKey: string; ordinal: number; action: string; expected: string; actual: string; outcome: StepOutcome }
export type RunAttempt = { id: string; attemptNo: number; state: ExecutionState; outcome: AttemptOutcome; rowVersion?: number; steps: RunStep[]; updatedAt?: string }
export type Run = { id: string; projectId: string; instanceId: string; manifestId: string; attemptId: string; state: ExecutionState; outcome: AttemptOutcome; rowVersion: number; attempt?: RunAttempt; manifest?: { title: string; description: string; preconditions: string; steps: RunStep[]; formatVersion?: string; rulesVersion?: string } }

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string'
const isPositiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 1
const invalid = (message: string): AccessError => ({ code: 'INVALID_RESPONSE', message })
const listBody = (body: unknown): unknown[] => Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : (() => { throw invalid('服务返回了无效列表') })()
const stringOrEmpty = (value: unknown): string | null => value === undefined || value === null ? '' : isString(value) ? value : null

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

const parseOutcome = (value: unknown): AttemptOutcome => value === undefined || value === null ? null : value === 'PASS' || value === 'FAIL' || value === 'BLOCKED' ? value : null
const parseState = (value: unknown): ExecutionState | null => value === 'RUNNING' || value === 'PAUSED' || value === 'FINISHED' ? value : null
const parseStep = (value: unknown): RunStep | null => {
  if (!isRecord(value) || !isString(value.stepKey) || !isPositiveInteger(value.ordinal) || !isString(value.action) || !isString(value.expected)) return null
  const actual = value.actual === undefined ? value.actualResult : value.actual
  const outcome = value.outcome === undefined ? value.conclusion : value.outcome
  if (!isString(actual) || !(outcome === 'NOT_RUN' || outcome === 'PASS' || outcome === 'FAIL' || outcome === 'BLOCKED')) return null
  return { stepKey: value.stepKey, ordinal: value.ordinal, action: value.action, expected: value.expected, actual, outcome }
}
const parseManifestStep = (value: unknown): RunStep | null => {
  if (!isRecord(value) || !isString(value.stepKey) || !isPositiveInteger(value.ordinal) || !isString(value.action) || !isString(value.expected)) return null
  return { stepKey: value.stepKey, ordinal: value.ordinal, action: value.action, expected: value.expected, actual: '', outcome: 'NOT_RUN' }
}
const parseAttempt = (value: unknown): RunAttempt | null => {
  if (!isRecord(value) || !isString(value.id) || !isPositiveInteger(value.attemptNo)) return null
  const state = parseState(value.state ?? value.status); const outcome = parseOutcome(value.outcome ?? value.conclusion); const values = value.steps === undefined ? [] : value.steps
  if (!state || !Array.isArray(values)) return null
  const steps = values.map(parseStep); if (steps.some((step) => step === null)) return null
  return { id: value.id, attemptNo: value.attemptNo, state, outcome, rowVersion: isPositiveInteger(value.rowVersion) ? value.rowVersion : undefined, steps: steps as RunStep[], updatedAt: isString(value.updatedAt) ? value.updatedAt : undefined }
}
const parseRun = (value: unknown): Run | null => {
  if (!isRecord(value)) return null
  if (isRecord(value.run)) {
    const base = parseRun(value.run); if (!base) return null
    const attempt = value.currentAttempt === undefined || value.currentAttempt === null ? base.attempt : parseAttempt(value.currentAttempt)
    const manifest = isRecord(value.manifest) && isString(value.manifest.title) && isString(value.manifest.description) && isString(value.manifest.preconditions) && Array.isArray(value.manifest.steps)
      ? { title: value.manifest.title, description: value.manifest.description, preconditions: value.manifest.preconditions, formatVersion: isString(value.manifest.formatVersion) ? value.manifest.formatVersion : undefined, rulesVersion: isString(value.manifest.rulesVersion) ? value.manifest.rulesVersion : undefined, steps: (value.manifest.steps.map(parseManifestStep).filter((step): step is RunStep => step !== null)) }
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
const path = (projectId: string, suffix: string) => `/api/v1/projects/${encodeURIComponent(projectId)}${suffix}`
const write = (input: RequestInfo | URL, body: unknown, options?: AccessRequestOptions) => requestJson(input, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() })

export const executionApi = {
  async listSets(projectId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, '/test-sets'), undefined, options), parseSet, '服务返回了无效测试集列表') },
  async createSet(projectId: string, input: { name: string; description: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, '/test-sets'), input, options), parseSet, '服务返回了无效测试集') },
  async getSet(projectId: string, setId: string, options?: AccessRequestOptions) { const body = await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}`), undefined, options); return parse(isRecord(body) && isRecord(body.testSet) ? body.testSet : body, parseSet, '服务返回了无效测试集') },
  async listInstances(projectId: string, setId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, `/test-sets/${encodeURIComponent(setId)}/instances`), undefined, options), parseInstance, '服务返回了无效测试实例列表') },
  async addInstance(projectId: string, setId: string, input: { testCaseId: string; testRevisionId: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/test-sets/${encodeURIComponent(setId)}/instances`), input, options), parseInstance, '服务返回了无效测试实例') },
  async createRun(projectId: string, input: { instanceId: string; mode: 'MANUAL'; reason?: string }, options?: AccessRequestOptions) { return parse(await write(path(projectId, '/runs'), { testInstanceId: input.instanceId, instanceId: input.instanceId, mode: input.mode, ...(input.reason ? { reason: input.reason } : {}) }, options), parseRun, '服务返回了无效运行') },
  async listRuns(projectId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, '/runs'), undefined, options), parseRun, '服务返回了无效运行列表') },
  async getRun(projectId: string, runId: string, options?: AccessRequestOptions) { return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}`), undefined, options), parseRun, '服务返回了无效运行') },
  async listAttempts(projectId: string, runId: string, options?: AccessRequestOptions) { return parseList(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts`), undefined, options), parseAttempt, '服务返回了无效尝试列表') },
  async getAttempt(projectId: string, runId: string, attemptId: string, options?: AccessRequestOptions) { return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}`), undefined, options), parseAttempt, '服务返回了无效尝试') },
  async saveStep(projectId: string, runId: string, attemptId: string, stepKey: string, input: { outcome: StepOutcome; actual: string; rowVersion?: number }, options?: AccessRequestOptions) { return parse(await requestJson(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/steps/${encodeURIComponent(stepKey)}`), { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': input.rowVersion ? `"${input.rowVersion}"` : '' }, body: JSON.stringify({ actualResult: input.actual, actual: input.actual, conclusion: input.outcome, outcome: input.outcome, ...(input.rowVersion ? { expectedVersion: input.rowVersion } : {}) }) }, { ...options, idempotencyKey: options?.idempotencyKey ?? newIdempotencyKey() }), parseAttempt, '服务返回了无效尝试') },
  async pause(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/pause`), { expectedVersion: version }, options), parseAttempt, '服务返回了无效尝试') },
  async resume(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/resume`), { expectedVersion: version }, options), parseAttempt, '服务返回了无效尝试') },
  async finish(projectId: string, runId: string, attemptId: string, version: number, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts/${encodeURIComponent(attemptId)}/finish`), { expectedVersion: version }, options), parseAttempt, '服务返回了无效尝试') },
  async rerun(projectId: string, runId: string, options?: AccessRequestOptions) { return parse(await write(path(projectId, `/runs/${encodeURIComponent(runId)}/attempts`), {}, options), parseAttempt, '服务返回了无效尝试') },
}
