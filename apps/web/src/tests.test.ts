import { afterEach, describe, expect, it, vi } from 'vitest'
import { testsApi } from './tests'

const testId = '00000000-0000-4000-8000-000000000001'
const revisionId = '00000000-0000-4000-8000-000000000002'
const projectId = '00000000-0000-4000-8000-000000000003'
const principalId = '00000000-0000-4000-8000-000000000004'
const stepKey = '00000000-0000-4000-8000-000000000005'

const responseBody = {
  id: testId,
  projectId,
  displayNumber: 1,
  testType: 'MANUAL',
  rowVersion: 1,
  currentRevisionId: revisionId,
  createdAt: '2026-10-01T00:00:00Z',
  createdBy: principalId,
  currentRevision: {
    id: revisionId,
    testCaseId: testId,
    revisionNo: 1,
    title: 'Login',
    description: '',
    preconditions: '',
    createdAt: '2026-10-01T00:00:00Z',
    createdBy: principalId,
    steps: [{ stepKey, ordinal: 1, action: 'Open login', expected: 'Form is visible' }],
  },
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('manual test case API request shapes', () => {
  it('sends testType only for create, not for revision requests', async () => {
    const bodies: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (!init?.body) return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'test-token' }), { status: 200 })
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      return new Response(JSON.stringify(responseBody), { status: bodies.length === 1 ? 201 : 200, headers: { ETag: '"1"' } })
    }))

    const input = { title: 'Login', description: '', preconditions: '', steps: [{ stepKey, action: 'Open login', expected: 'Form is visible' }] }
    await testsApi.create(projectId, input)
    await testsApi.appendRevision(projectId, { ...responseBody, testType: 'MANUAL', displayNumber: '1', revisionNumber: 1, title: 'Login', description: '', preconditions: '', steps: responseBody.currentRevision.steps, etag: '"1"' }, input)

    expect(bodies[0]).toMatchObject({ testType: 'MANUAL', title: 'Login' })
    expect(bodies[1]).not.toHaveProperty('testType')
    expect(bodies[1].steps).toEqual([{ stepKey, ordinal: 1, action: 'Open login', expected: 'Form is visible' }])
  })
})

