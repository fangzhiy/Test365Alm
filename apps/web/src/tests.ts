import { requestJsonWithResponse } from './projectAccess'
import { newIdempotencyKey } from './requirements'
import type { AccessError, AccessRequestOptions } from './projectAccess'

export type TestStep = {
  stepKey: string
  ordinal: number
  action: string
  expected: string
}

export type TestRevision = {
  id: string
  revisionNumber: number
  title: string
  description: string
  preconditions: string
  createdAt: string
  createdBy: string
  steps: TestStep[]
}

export type TestCase = {
  id: string
  projectId: string
  displayNumber: string
  testType: 'MANUAL'
  rowVersion: number
  etag?: string
  currentRevisionId: string
  revisionNumber: number
  title: string
  description: string
  preconditions: string
  steps: TestStep[]
  createdAt: string
  createdBy: string
}

export type TestCaseInput = {
  title: string
  description: string
  preconditions: string
  steps: Array<Pick<TestStep, 'action' | 'expected'> & Partial<Pick<TestStep, 'stepKey' | 'ordinal'>>>
}

export type TestCasePage = { items: TestCase[]; nextCursor?: string | null }

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string'
const isPositiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 1
const isStepKey = (value: unknown): value is string => isString(value) && value.length > 0 && value.length <= 128
const isServerStepKey = (value: unknown): value is string => isString(value) && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)

const invalid = (message: string): AccessError => ({ code: 'INVALID_RESPONSE', message })

const parseStep = (value: unknown): TestStep | null => {
  if (!isRecord(value) || !isStepKey(value.stepKey) || !isPositiveInteger(value.ordinal) || !isString(value.action) || !isString(value.expected)) return null
  return { stepKey: value.stepKey, ordinal: value.ordinal, action: value.action, expected: value.expected }
}

const parseSteps = (value: unknown): TestStep[] | null => {
  if (!Array.isArray(value)) return null
  const steps = value.map(parseStep)
  if (steps.some((step) => step === null)) return null
  const parsed = steps as TestStep[]
  const keys = new Set(parsed.map((step) => step.stepKey))
  const ordinals = new Set(parsed.map((step) => step.ordinal))
  if (keys.size !== parsed.length || ordinals.size !== parsed.length) return null
  return [...parsed].sort((a, b) => a.ordinal - b.ordinal)
}

const revisionValue = (value: Record<string, unknown>): Record<string, unknown> => isRecord(value.revision) ? value.revision : isRecord(value.currentRevision) ? value.currentRevision : value

const parseRevision = (value: unknown): TestRevision | null => {
  if (!isRecord(value)) return null
  const source = revisionValue(value)
  const id = isString(source.id) ? source.id : isString(source.revisionId) ? source.revisionId : null
  const title = source.title
  const description = source.description ?? source.body
  const preconditions = source.preconditions
  const createdBy = source.createdBy ?? source.authorPrincipalId ?? source.author
  const steps = parseSteps(source.steps)
  const revisionNumber = isPositiveInteger(source.revisionNumber) ? source.revisionNumber : source.revisionNo
  if (!id || !isPositiveInteger(revisionNumber) || !isString(title) || !isString(description) || !isString(preconditions) || !isString(createdBy) || !isString(source.createdAt) || !steps) return null
  return { id, revisionNumber, title, description, preconditions, createdAt: source.createdAt, createdBy, steps }
}

const parseTestCase = (value: unknown, allowSummary = false): TestCase | null => {
  if (!isRecord(value) || !isString(value.id) || !isString(value.projectId) || !(isString(value.displayNumber) || isPositiveInteger(value.displayNumber)) || value.testType !== 'MANUAL'
    || !isPositiveInteger(value.rowVersion) || !isString(value.createdAt) || !isString(value.createdBy)) return null
  const revision = parseRevision(value) ?? (allowSummary && isPositiveInteger(value.revisionNo) && isString(value.title) && isString(value.description) && isString(value.preconditions)
    ? { id: isString(value.currentRevisionId) ? value.currentRevisionId : '', revisionNumber: value.revisionNo, title: value.title, description: value.description, preconditions: value.preconditions, createdAt: value.createdAt, createdBy: value.createdBy, steps: [] }
    : null)
  if (!revision) return null
  const currentRevisionId = isString(value.currentRevisionId) ? value.currentRevisionId : revision.id
  if (!currentRevisionId) return null
  const etag = value.etag === undefined ? undefined : isString(value.etag) && value.etag.length > 0 ? value.etag : null
  if (etag === null) return null
  return {
    id: value.id,
    projectId: value.projectId,
    displayNumber: String(value.displayNumber),
    testType: 'MANUAL',
    rowVersion: value.rowVersion,
    etag,
    currentRevisionId,
    revisionNumber: revision.revisionNumber,
    title: revision.title,
    description: revision.description,
    preconditions: revision.preconditions,
    steps: revision.steps,
    createdAt: value.createdAt,
    createdBy: value.createdBy,
  }
}

const parsePage = (body: unknown): TestCasePage => {
  const values = Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : null
  if (!values) throw invalid('服务返回了无效测试用例列表')
  const items = values.map((value) => parseTestCase(value, true))
  if (items.some((item) => item === null)) throw invalid('服务返回了无效测试用例对象')
  const nextCursor = isRecord(body) && (body.nextCursor === null || isString(body.nextCursor)) ? body.nextCursor : null
  return { items: items as TestCase[], nextCursor }
}

const parseOne = (body: unknown, etag?: string): TestCase => {
  const parsed = parseTestCase(body)
  if (!parsed) throw invalid('服务返回了无效测试用例对象')
  return etag ? { ...parsed, etag } : parsed
}

const parseRevisionList = (body: unknown): TestRevision[] => {
  const values = Array.isArray(body) ? body : isRecord(body) && Array.isArray(body.items) ? body.items : null
  if (!values) throw invalid('服务返回了无效测试修订列表')
  const revisions = values.map(parseRevision)
  if (revisions.some((revision) => revision === null)) throw invalid('服务返回了无效测试修订对象')
  return revisions as TestRevision[]
}

const responseEtag = (response: Response) => response.headers.get('ETag') ?? response.headers.get('etag') ?? undefined
const pendingIntentKeys = new Map<string, string>()
const intentKey = (scope: string, input: unknown) => `${scope}\u0000${JSON.stringify(input)}`

const encodeInput = (input: TestCaseInput, preserveStepKeys = true) => ({
  title: input.title,
  description: input.description,
  preconditions: input.preconditions,
  testType: 'MANUAL',
  steps: input.steps.map((step, index) => ({ ...(preserveStepKeys && isServerStepKey(step.stepKey) ? { stepKey: step.stepKey } : {}), ordinal: index + 1, action: step.action, expected: step.expected })),
})

export const testsApi = {
  async list(projectId: string, query = '', cursor?: string | null, options?: AccessRequestOptions): Promise<TestCasePage> {
    const params = new URLSearchParams({ limit: '50' })
    if (query.trim()) params.set('q', query.trim())
    if (cursor) params.set('cursor', cursor)
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests?${params.toString()}`, undefined, options)
    return parsePage(result.body)
  },
  async get(projectId: string, testId: string, options?: AccessRequestOptions): Promise<TestCase> {
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests/${encodeURIComponent(testId)}`, undefined, options)
    return parseOne(result.body, responseEtag(result.response))
  },
  async create(projectId: string, input: TestCaseInput, options?: AccessRequestOptions): Promise<TestCase> {
    const intent = intentKey(`create:${projectId}`, input)
    const key = options?.idempotencyKey ?? pendingIntentKeys.get(intent) ?? newIdempotencyKey()
    if (!options?.idempotencyKey) pendingIntentKeys.set(intent, key)
    try {
      const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(encodeInput(input, false)) }, options)
      return parseOne(result.body, responseEtag(result.response))
    } finally {
      pendingIntentKeys.delete(intent)
    }
  },
  async appendRevision(projectId: string, testCase: TestCase, input: TestCaseInput, options?: AccessRequestOptions): Promise<TestCase> {
    if (!testCase.etag) throw invalid('测试用例版本标识不可用，请先刷新用例')
    const intent = intentKey(`revision:${projectId}:${testCase.id}:${testCase.etag}`, input)
    const key = options?.idempotencyKey ?? pendingIntentKeys.get(intent) ?? newIdempotencyKey()
    if (!options?.idempotencyKey) pendingIntentKeys.set(intent, key)
    try {
      const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests/${encodeURIComponent(testCase.id)}/revisions`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'If-Match': testCase.etag }, body: JSON.stringify(encodeInput(input)) }, options)
      return parseOne(result.body, responseEtag(result.response))
    } finally {
      pendingIntentKeys.delete(intent)
    }
  },
  async revisions(projectId: string, testId: string, options?: AccessRequestOptions): Promise<TestRevision[]> {
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests/${encodeURIComponent(testId)}/revisions`, undefined, options)
    return parseRevisionList(result.body)
  },
  async revision(projectId: string, testId: string, revisionId: string, options?: AccessRequestOptions): Promise<TestRevision> {
    const result = await requestJsonWithResponse(`/api/v1/projects/${encodeURIComponent(projectId)}/tests/${encodeURIComponent(testId)}/revisions/${encodeURIComponent(revisionId)}`, undefined, options)
    const parsed = parseRevision(result.body)
    if (!parsed) throw invalid('服务返回了无效测试修订对象')
    return parsed
  },
}

