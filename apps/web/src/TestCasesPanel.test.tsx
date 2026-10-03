import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TestCasesPanel from './TestCasesPanel'
import { testsApi } from './tests'
import type { TestCase, TestRevision } from './tests'

vi.mock('./tests', async () => {
  const actual = await vi.importActual<typeof import('./tests')>('./tests')
  return { ...actual, testsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), appendRevision: vi.fn(), revisions: vi.fn(), revision: vi.fn() } }
})

const access = { tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_MEMBER'] as const, permissions: ['test:read', 'test:create', 'test:update', 'test:history:read'] }
const viewer = { ...access, roles: ['PROJECT_VIEWER'] as const, permissions: ['test:read', 'test:history:read'] }
const noRead = { ...access, permissions: ['test:create', 'test:update'] as const }
const testCase: TestCase = { id: 'test-1', projectId: 'project-1', displayNumber: 'TC-1', testType: 'MANUAL', rowVersion: 1, etag: '"1"', currentRevisionId: 'test-rev-1', revisionNumber: 1, title: 'Sign in', description: 'User signs in', preconditions: 'Account exists', createdAt: '2026-10-01T00:00:00Z', createdBy: 'principal-1', steps: [{ stepKey: 'step-a', ordinal: 1, action: 'Open login', expected: 'Login is visible' }, { stepKey: 'step-b', ordinal: 2, action: 'Enter credentials', expected: 'Credentials accepted' }] }
const revision: TestRevision = { id: 'test-rev-1', revisionNumber: 1, title: testCase.title, description: testCase.description, preconditions: testCase.preconditions, createdAt: testCase.createdAt, createdBy: testCase.createdBy, steps: testCase.steps }
const deferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => { resolve = resolvePromise; reject = rejectPromise })
  return { promise, resolve, reject }
}

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('TestCasesPanel', () => {
  it('loads a project-scoped manual case and immutable history', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    render(<TestCasesPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('TC-1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /TC-1/ }))
    expect(await screen.findByDisplayValue('Sign in')).toBeVisible()
    fireEvent.click(within(screen.getByRole('region', { name: '测试用例修订历史' })).getByRole('button', { name: /修订 1/ }))
    expect(await screen.findByText('修订 1（只读）')).toBeVisible()
    expect(screen.getAllByText('Open login').length).toBeGreaterThanOrEqual(2)
    expect(testsApi.get).toHaveBeenCalledWith('project-1', 'test-1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('creates a manual case with step content and stable draft step key', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    const created = { ...testCase, title: 'Checkout' }
    vi.mocked(testsApi.create).mockResolvedValue(created)
    vi.mocked(testsApi.get).mockResolvedValue(created)
    vi.mocked(testsApi.revisions).mockResolvedValue([{ ...revision, title: 'Checkout' }])
    render(<TestCasesPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有手工测试用例')
    fireEvent.change(screen.getByLabelText('测试用例标题'), { target: { value: 'Checkout' } })
    fireEvent.click(screen.getByRole('button', { name: '新增步骤' }))
    const action = screen.getByLabelText('步骤 1 操作')
    const expected = screen.getByLabelText('步骤 1 预期结果')
    fireEvent.change(action, { target: { value: 'Open checkout' } })
    fireEvent.change(expected, { target: { value: 'Checkout is visible' } })
    fireEvent.click(screen.getByRole('button', { name: '创建用例' }))
    await waitFor(() => expect(testsApi.create).toHaveBeenCalledTimes(1))
    const input = vi.mocked(testsApi.create).mock.calls[0][1]
    expect(input.title).toBe('Checkout')
    expect(input.steps).toHaveLength(1)
    expect(input.steps[0].action).toBe('Open checkout')
    expect(input.steps[0].stepKey).toMatch(/^draft-/)
    expect(await screen.findByText('测试用例已创建')).toBeVisible()
  })

  it('reorders existing steps without changing stable keys when saving a revision', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockResolvedValue({ ...testCase, rowVersion: 2, etag: '"2"', revisionNumber: 2, currentRevisionId: 'test-rev-2', steps: [{ ...testCase.steps[1], ordinal: 1 }, { ...testCase.steps[0], ordinal: 2 }] })
    render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Open login')
    fireEvent.click(screen.getAllByRole('button', { name: '下移' })[0])
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    await waitFor(() => expect(testsApi.appendRevision).toHaveBeenCalledTimes(1))
    const input = vi.mocked(testsApi.appendRevision).mock.calls[0][2]
    expect(input.steps.map((step) => step.stepKey)).toEqual(['step-b', 'step-a'])
    expect(input.steps.map((step) => step.ordinal)).toEqual([1, 2])
    expect(vi.mocked(testsApi.appendRevision).mock.calls[0][1].etag).toBe('"1"')
  })

  it('preserves complete step draft on a stale-version response', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockRejectedValue({ code: 'HTTP_412', message: '版本冲突' })
    render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Open login')
    fireEvent.change(screen.getByLabelText('步骤 1 操作'), { target: { value: 'Keep this step draft' } })
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('草稿已保留')
    expect(screen.getByDisplayValue('Keep this step draft')).toBeVisible()
    expect(screen.getByText('查看最新版本')).toBeVisible()
  })

  it('does not expose create or edit controls to a viewer', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    render(<TestCasesPanel projectId="project-1" access={viewer} />)
    expect(await screen.findByText('TC-1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /TC-1/ }))
    expect(await screen.findByText('只读用户可以查看用例和历史，但不能创建或保存。')).toBeVisible()
    expect(screen.queryByRole('button', { name: '创建用例' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存为新修订' })).not.toBeInTheDocument()
    expect(testsApi.create).not.toHaveBeenCalled()
  })

  it('drops old project responses and restores the new scope after a switch', async () => {
    let resolveA: ((value: { items: TestCase[]; nextCursor: null }) => void) | undefined
    vi.mocked(testsApi.list).mockImplementation((projectId) => projectId === 'project-1' ? new Promise((resolve) => { resolveA = resolve }) : Promise.resolve({ items: [{ ...testCase, id: 'test-2', projectId: 'project-2', displayNumber: 'TC-2', title: 'Project B' }], nextCursor: null }))
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    rerender(<TestCasesPanel projectId="project-2" access={{ ...access, projectId: 'project-2' }} />)
    expect(await screen.findByText('TC-2')).toBeVisible()
    resolveA?.({ items: [testCase], nextCursor: null })
    await waitFor(() => expect(screen.queryByText('TC-1')).not.toBeInTheDocument())
  })

  it('reuses the same create key after an unknown response until the user changes the intent', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    const created = { ...testCase, title: 'Retry me' }
    vi.mocked(testsApi.create)
      .mockRejectedValueOnce(Object.assign(new Error('request timed out'), { name: 'AbortError' }))
      .mockResolvedValueOnce(created)
    vi.mocked(testsApi.get).mockResolvedValue(created)
    vi.mocked(testsApi.revisions).mockResolvedValue([{ ...revision, title: 'Retry me' }])
    render(<TestCasesPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有手工测试用例')
    fireEvent.change(screen.getByLabelText('测试用例标题'), { target: { value: 'Retry me' } })
    fireEvent.click(screen.getByRole('button', { name: '创建用例' }))
    expect(await screen.findByText('创建请求超时，请保留原意图后重试')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '创建用例' }))
    await waitFor(() => expect(testsApi.create).toHaveBeenCalledTimes(2))
    expect(vi.mocked(testsApi.create).mock.calls[0][2]?.idempotencyKey).toBe(vi.mocked(testsApi.create).mock.calls[1][2]?.idempotencyKey)
  })

  it('keeps readable data when a write is forbidden instead of treating it as a lost scope', async () => {
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockRejectedValue({ code: 'FORBIDDEN', message: '只读成员不能写入' })
    render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    expect(await screen.findByRole('status')).toHaveTextContent('没有修改')
    expect(screen.getByText('TC-1')).toBeVisible()
    expect(screen.queryByRole('button', { name: '保存为新修订' })).not.toBeInTheDocument()
  })

  it('restores list controls when a delayed load-more is superseded by detail loading', async () => {
    const delayedPage = deferred<{ items: TestCase[]; nextCursor: null }>()
    vi.mocked(testsApi.list).mockResolvedValueOnce({ items: [testCase], nextCursor: 'cursor-2' }).mockReturnValueOnce(delayedPage.promise)
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    render(<TestCasesPanel projectId="project-1" access={access} />)

    await screen.findByText('TC-1')
    fireEvent.click(screen.getByRole('button', { name: '加载更多用例' }))
    fireEvent.click(screen.getByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')

    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '加载更多用例' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '查询' })).toBeEnabled()

    delayedPage.resolve({ items: [{ ...testCase, id: 'test-2', displayNumber: 'TC-2' }], nextCursor: null })
    await waitFor(() => expect(screen.getByDisplayValue('Sign in')).toBeVisible())
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    expect(screen.getByText('TC-1')).toBeVisible()
  })

  it.each([
    ['success', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.resolve({ items: [{ ...testCase, id: 'test-2', displayNumber: 'TC-2' }], nextCursor: null })],
    ['failure', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.reject(new Error('late list failure'))],
    ['abort', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))],
  ])('ignores delayed load-more %s after a newer detail request', async (_label, settle) => {
    const delayedPage = deferred<{ items: TestCase[]; nextCursor: null }>()
    vi.mocked(testsApi.list).mockResolvedValueOnce({ items: [testCase], nextCursor: 'cursor-2' }).mockReturnValueOnce(delayedPage.promise)
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    render(<TestCasesPanel projectId="project-1" access={access} />)

    await screen.findByText('TC-1')
    fireEvent.click(screen.getByRole('button', { name: '加载更多用例' }))
    fireEvent.click(screen.getByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    settle(delayedPage)
    await waitFor(() => expect(screen.getByDisplayValue('Sign in')).toBeVisible())
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    expect(screen.queryByText('late list failure')).not.toBeInTheDocument()
  })

  it('disables every detail step editor until detail and history have loaded', async () => {
    const detail = deferred<TestCase>()
    const history = deferred<TestRevision[]>()
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockReturnValue(detail.promise)
    vi.mocked(testsApi.revisions).mockReturnValue(history.promise)
    render(<TestCasesPanel projectId="project-1" access={access} />)

    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    const detailRegion = screen.getByLabelText('测试用例详情')
    expect(screen.getByLabelText('步骤 1 操作')).toBeDisabled()
    expect(screen.getByLabelText('步骤 1 预期结果')).toBeDisabled()
    expect(screen.getAllByRole('button', { name: '上移' })[0]).toBeDisabled()
    expect(screen.getAllByRole('button', { name: '下移' })[0]).toBeDisabled()
    expect(screen.getAllByRole('button', { name: '移除' })[0]).toBeDisabled()
    expect(within(detailRegion).getByRole('button', { name: '新增步骤' })).toBeDisabled()

    detail.resolve(testCase)
    history.resolve([revision])
    await waitFor(() => expect(screen.getByLabelText('步骤 1 操作')).toBeEnabled())
    expect(within(detailRegion).getByRole('button', { name: '新增步骤' })).toBeEnabled()
  })

  it('protects step drafts while viewing the latest version after a 412 conflict', async () => {
    const latest = { ...testCase, rowVersion: 2, etag: '"2"', revisionNumber: 2, currentRevisionId: 'test-rev-2', title: 'Latest' }
    const latestRevision = { ...revision, id: 'test-rev-2', revisionNumber: 2, title: 'Latest' }
    const latestDetail = deferred<TestCase>()
    const latestHistory = deferred<TestRevision[]>()
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValueOnce(testCase).mockReturnValueOnce(latestDetail.promise)
    vi.mocked(testsApi.revisions).mockResolvedValueOnce([revision]).mockReturnValueOnce(latestHistory.promise)
    vi.mocked(testsApi.appendRevision).mockRejectedValue({ code: 'HTTP_412', message: '版本冲突' })
    render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Open login')
    fireEvent.change(screen.getByLabelText('步骤 1 操作'), { target: { value: 'Keep this step draft' } })
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    fireEvent.click(await screen.findByRole('button', { name: '查看最新版本' }))

    expect(screen.getByLabelText('步骤 1 操作')).toBeDisabled()
    expect(within(screen.getByLabelText('测试用例详情')).getByRole('button', { name: '新增步骤' })).toBeDisabled()
    latestDetail.resolve(latest)
    latestHistory.resolve([revision, latestRevision])
    await waitFor(() => expect(screen.getByLabelText('步骤 1 操作')).toBeEnabled())
    expect(screen.getByDisplayValue('Keep this step draft')).toBeVisible()
    expect(screen.getByText('已加载最新版本，草稿仍保留，请确认后再保存。')).toBeVisible()
  })

  it('clears saving and stale data when the active project loses access during a write', async () => {
    const pendingSave = deferred<TestCase>()
    const projectB = { ...testCase, id: 'test-b', projectId: 'project-2', displayNumber: 'TC-B', title: 'Project B' }
    vi.mocked(testsApi.list).mockImplementation((project) => Promise.resolve(project === 'project-1' ? { items: [testCase], nextCursor: null } : { items: [projectB], nextCursor: null }))
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockReturnValue(pendingSave.promise)
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    rerender(<TestCasesPanel projectId="project-2" access={{ ...access, projectId: 'project-2' }} />)
    await screen.findByText('TC-B')
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '创建用例' })).toBeEnabled()
    pendingSave.resolve(projectB)
    await waitFor(() => expect(screen.queryByText('TC-1')).not.toBeInTheDocument())
  })

  it('drops write state when permissions change to viewer or the session is reset', async () => {
    const pendingSave = deferred<TestCase>()
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockReturnValue(pendingSave.promise)
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))

    rerender(<TestCasesPanel projectId="project-1" access={viewer} />)
    await screen.findByText('TC-1')
    expect(screen.queryByRole('button', { name: '保存为新修订' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled())

    rerender(<TestCasesPanel projectId="project-1" access={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取')
    pendingSave.resolve(testCase)
    await waitFor(() => expect(screen.queryByText('Sign in')).not.toBeInTheDocument())
  })

  it.each([
    ['late success', (pending: { resolve: (value: TestCase) => void; reject: (reason?: unknown) => void }) => pending.resolve(testCase)],
    ['late failure', (pending: { resolve: (value: TestCase) => void; reject: (reason?: unknown) => void }) => pending.reject(new Error('late save failure'))],
    ['late abort', (pending: { resolve: (value: TestCase) => void; reject: (reason?: unknown) => void }) => pending.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))],
  ])('invalidates a pending write when the same project loses all access (%s)', async (_label, settle) => {
    const pendingSave = deferred<TestCase>()
    vi.mocked(testsApi.list).mockResolvedValue({ items: [testCase], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValue(testCase)
    vi.mocked(testsApi.revisions).mockResolvedValue([revision])
    vi.mocked(testsApi.appendRevision).mockReturnValue(pendingSave.promise)
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    await waitFor(() => expect(testsApi.appendRevision).toHaveBeenCalledTimes(1))
    const before = { get: vi.mocked(testsApi.get).mock.calls.length, revisions: vi.mocked(testsApi.revisions).mock.calls.length }

    rerender(<TestCasesPanel projectId="project-1" access={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取')
    expect(vi.mocked(testsApi.appendRevision).mock.calls[0][3]?.signal?.aborted).toBe(true)
    settle(pendingSave)
    await waitFor(() => expect(vi.mocked(testsApi.get).mock.calls.length).toBe(before.get))
    expect(vi.mocked(testsApi.revisions).mock.calls.length).toBe(before.revisions)
    expect(screen.queryByText('保存中…')).not.toBeInTheDocument()
  })

  it.each([
    ['late success', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.resolve({ items: [testCase], nextCursor: null })],
    ['late failure', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.reject(new Error('late list failure'))],
    ['late abort', (pending: { resolve: (value: { items: TestCase[]; nextCursor: null }) => void; reject: (reason?: unknown) => void }) => pending.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))],
  ])('invalidates a pending read when the same project loses test:read (%s)', async (_label, settle) => {
    const pendingList = deferred<{ items: TestCase[]; nextCursor: null }>()
    vi.mocked(testsApi.list).mockReturnValue(pendingList.promise)
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    await waitFor(() => expect(testsApi.list).toHaveBeenCalledTimes(1))
    rerender(<TestCasesPanel projectId="project-1" access={noRead} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取')
    expect(vi.mocked(testsApi.list).mock.calls[0][3]?.signal?.aborted).toBe(true)
    settle(pendingList)
    await waitFor(() => expect(vi.mocked(testsApi.list).mock.calls.length).toBe(1))
    expect(screen.queryByText('TC-1')).not.toBeInTheDocument()
  })

  it('loads a fresh scope after direct same-project access is restored', async () => {
    const firstList = deferred<{ items: TestCase[]; nextCursor: null }>()
    vi.mocked(testsApi.list).mockReturnValueOnce(firstList.promise).mockResolvedValueOnce({ items: [{ ...testCase, id: 'test-restored', displayNumber: 'TC-R' }], nextCursor: null })
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)
    await waitFor(() => expect(testsApi.list).toHaveBeenCalledTimes(1))
    rerender(<TestCasesPanel projectId="project-1" access={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取')
    rerender(<TestCasesPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('TC-R')).toBeVisible()
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    firstList.resolve({ items: [testCase], nextCursor: null })
    await waitFor(() => expect(screen.queryByText('TC-1')).not.toBeInTheDocument())
  })

  it('restores a fresh same-project scope after access is lost during a pending save', async () => {
    const pendingSave = deferred<TestCase>()
    const restored = { ...testCase, id: 'test-restored-write', displayNumber: 'TC-RW', title: 'Restored after access loss' }
    vi.mocked(testsApi.list).mockResolvedValueOnce({ items: [testCase], nextCursor: null }).mockResolvedValueOnce({ items: [restored], nextCursor: null })
    vi.mocked(testsApi.get).mockResolvedValueOnce(testCase).mockResolvedValueOnce(restored)
    vi.mocked(testsApi.revisions).mockResolvedValueOnce([revision]).mockResolvedValueOnce([{ ...revision, id: 'test-restored-rev', title: restored.title }])
    vi.mocked(testsApi.appendRevision).mockReturnValue(pendingSave.promise)
    const { rerender } = render(<TestCasesPanel projectId="project-1" access={access} />)

    fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
    await screen.findByDisplayValue('Sign in')
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    await waitFor(() => expect(testsApi.appendRevision).toHaveBeenCalledTimes(1))
    const readsBeforeLateSave = { get: vi.mocked(testsApi.get).mock.calls.length, revisions: vi.mocked(testsApi.revisions).mock.calls.length }

    rerender(<TestCasesPanel projectId="project-1" access={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取')
    expect(vi.mocked(testsApi.appendRevision).mock.calls[0][3]?.signal?.aborted).toBe(true)
    pendingSave.resolve({ ...testCase, title: 'Stale save response' })
    await waitFor(() => expect(vi.mocked(testsApi.get).mock.calls.length).toBe(readsBeforeLateSave.get))
    expect(vi.mocked(testsApi.revisions).mock.calls.length).toBe(readsBeforeLateSave.revisions)

    rerender(<TestCasesPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('TC-RW')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /TC-RW/ }))
    expect(await screen.findByDisplayValue('Restored after access loss')).toBeVisible()
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '创建用例' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '保存为新修订' })).toBeEnabled()
    expect(screen.queryByText('测试用例已保存为新修订')).not.toBeInTheDocument()
  })
})
