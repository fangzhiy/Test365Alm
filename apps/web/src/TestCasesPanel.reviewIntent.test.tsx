import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TestCasesPanel from './TestCasesPanel'
import { testsApi, type TestCase } from './tests'
import { reviewApi, type ReviewView } from './reviewApi'
import { foldersApi } from './folders'

vi.mock('./tests', () => ({ testsApi: { list: vi.fn(), get: vi.fn(), revisions: vi.fn(), appendRevision: vi.fn() } }))
vi.mock('./reviewApi', () => ({ reviewApi: { get: vi.fn(), act: vi.fn() } }))
vi.mock('./folders', () => ({ foldersApi: { tree: vi.fn(), location: vi.fn(), place: vi.fn() } }))
vi.mock('./BusinessFieldsForm', () => ({ default: () => null }))
vi.mock('./AttachmentsPanel', () => ({ default: () => null }))
vi.mock('./HistoryPanel', () => ({ default: () => null }))
vi.mock('./ReviewerPicker', () => ({ default: () => <span>Reviewer</span> }))

const access = { tenantId: 'tenant', projectId: 'project', principalId: 'author', roles: ['PROJECT_MEMBER'] as const, permissions: ['test:read', 'test:create', 'test:update', 'test:history:read'] }
const saved: TestCase = { id: 'test', projectId: 'project', displayNumber: 'TC-1', testType: 'MANUAL', rowVersion: 1, etag: '"1"', currentRevisionId: 'revision', revisionNumber: 1, title: 'Original content', description: '', preconditions: '', createdAt: '2026-11-11T00:00:00Z', createdBy: 'author', steps: [] }
const other: TestCase = { ...saved, id: 'other', displayNumber: 'TC-2', currentRevisionId: 'other-revision', title: 'Other content' }
const draft: ReviewView = { testCaseId: saved.id, revisionId: saved.currentRevisionId, revisionNo: 1, currentRevisionId: saved.currentRevisionId, rowVersion: 1, contentDigest: 'a'.repeat(64), reviewerId: 'reviewer', state: 'DRAFT', canSubmit: true, canDecide: false, latest: null, items: [], nextCursor: null }
const submitted: ReviewView = { ...draft, rowVersion: 2, state: 'SUBMITTED', canSubmit: false }
const deferred = <T,>() => {
  let resolve!: (value: T) => void, reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(testsApi.list).mockImplementation(async projectId => ({ items: [{ ...saved, projectId }, { ...other, projectId }], nextCursor: 'next-page' }))
  vi.mocked(testsApi.get).mockImplementation(async (projectId, id) => ({ ...(id === other.id ? other : saved), projectId }))
  vi.mocked(testsApi.revisions).mockImplementation(async (_project, id) => [{ id: id === other.id ? other.currentRevisionId : saved.currentRevisionId, revisionNumber: 1, title: 'Old revision', description: '', preconditions: '', createdAt: saved.createdAt, createdBy: saved.createdBy, steps: [] }])
  vi.mocked(reviewApi.get).mockImplementation(async (_project, id, revisionId) => ({ ...draft, testCaseId: id, revisionId, currentRevisionId: revisionId }))
  vi.mocked(foldersApi.tree).mockResolvedValue([{ id: 'folder', name: 'Other folder', parentId: null, rowVersion: 1, kind: 'TEST_PLAN', sortOrder: 0 }])
  vi.mocked(foldersApi.location).mockImplementation(async (_project, _kind, objectId) => ({ objectId, folderId: null, rowVersion: 1 }))
})
afterEach(cleanup)

async function openCase() {
  fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
  await screen.findByRole('button', { name: '提交复核' })
  fireEvent.change(screen.getByLabelText('复核说明或拒绝理由'), { target: { value: 'Original review reason' } })
}
const navigation = ['same case', 'other case', 'query Enter', 'refresh cases', 'next page', 'choose folder', 'refresh folders', 'conflict latest', 'read history'] as const
function navigate(name: typeof navigation[number]) {
  if (name === 'query Enter') {
    const input = screen.getByRole('textbox', { name: '查询测试用例' })
    // Native Enter submits the form even when the submit button is disabled.
    fireEvent.submit(input.closest('form')!)
  } else fireEvent.click(screen.getByRole('button', { name: ({
    'same case': /TC-1/, 'other case': /TC-2/, 'refresh cases': '刷新用例', 'next page': '加载更多用例',
    'choose folder': 'Other folder', 'refresh folders': '刷新目录', 'conflict latest': '查看最新版本', 'read history': /修订 1.*author/,
  } as const)[name] }))
}
it.each(navigation.flatMap(name => ['unknown', 'in-flight'].map(phase => ({ name, phase }))))('preserves original review intent across $name while $phase', async ({ name, phase }) => {
  const pending = deferred<ReviewView>()
  vi.mocked(reviewApi.act).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(submitted)
  render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  if (name === 'conflict latest') {
    vi.mocked(testsApi.appendRevision).mockRejectedValueOnce({ code: 'STALE_VERSION' })
    fireEvent.click(screen.getByRole('button', { name: '保存为新修订' }))
    await screen.findByRole('button', { name: '查看最新版本' })
  }
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await waitFor(() => expect(reviewApi.act).toHaveBeenCalledTimes(1))
  if (phase === 'unknown') await act(async () => pending.reject(new TypeError('unknown network outcome')))
  const before = { lists: vi.mocked(testsApi.list).mock.calls.length, details: vi.mocked(testsApi.get).mock.calls.length }
  const original = vi.mocked(reviewApi.act).mock.calls[0]
  navigate(name)
  expect(screen.getByRole('region', { name: '用例复核' })).toBeVisible()
  expect(screen.getByRole('button', { name: '重试原复核意图' })).toBeVisible()
  expect(original[6]?.signal?.aborted).toBe(false)
  expect(testsApi.list).toHaveBeenCalledTimes(before.lists)
  expect(testsApi.get).toHaveBeenCalledTimes(before.details)
  expect(reviewApi.act).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: /TC-1/ })).toBeDisabled()
  expect(screen.getByRole('button', { name: /TC-2/ })).toBeDisabled()
  expect(screen.getByRole('textbox', { name: '查询测试用例' })).toBeDisabled()
  if (phase === 'in-flight') await act(async () => pending.reject(new TypeError('unknown network outcome')))
  fireEvent.click(screen.getByRole('button', { name: '重试原复核意图' }))
  await waitFor(() => expect(reviewApi.act).toHaveBeenCalledTimes(2))
  expect(vi.mocked(reviewApi.act).mock.calls[1].slice(0, 6)).toEqual(original.slice(0, 6))
  expect(original.slice(0, 5)).toEqual(['project', 'test', 'revision', { action: 'SUBMIT', reason: 'Original review reason' }, '"1"'])
  await waitFor(() => expect(screen.getByRole('button', { name: /TC-2/ })).toBeEnabled())
  expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument()
})

it('ignores a late directory-move refresh while an unknown review needs its original retry', async () => {
  const move = deferred<Awaited<ReturnType<typeof foldersApi.place>>>()
  vi.mocked(foldersApi.place).mockReturnValueOnce(move.promise)
  vi.mocked(reviewApi.act).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(submitted)
  render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  await waitFor(() => expect(screen.getByRole('button', { name: '移动用例' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '移动用例' }))
  await waitFor(() => expect(foldersApi.place).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await screen.findByText('复核结果未知，请重试原复核意图确认结果。')
  await act(async () => move.resolve({ objectId: saved.id, folderId: null, rowVersion: 2 }))
  expect(testsApi.list).toHaveBeenCalledTimes(1)
  const original = vi.mocked(reviewApi.act).mock.calls[0]
  fireEvent.click(screen.getByRole('button', { name: '重试原复核意图' }))
  await waitFor(() => expect(reviewApi.act).toHaveBeenCalledTimes(2))
  expect(vi.mocked(reviewApi.act).mock.calls[1].slice(0, 6)).toEqual(original.slice(0, 6))
})

it.each([200, 400, 403, 412, 422])('unlocks normal navigation after a confirmed review HTTP %i', async status => {
  if (status === 200) vi.mocked(reviewApi.act).mockResolvedValueOnce(submitted)
  else vi.mocked(reviewApi.act).mockRejectedValueOnce({ status })
  render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await waitFor(() => expect(reviewApi.act).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled())
  expect(screen.getByRole('button', { name: /TC-2/ })).toBeEnabled()
  expect(screen.getByRole('textbox', { name: '查询测试用例' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /TC-2/ }))
  await waitFor(() => expect(testsApi.get).toHaveBeenLastCalledWith('project', 'other', expect.any(Object)))
  expect(reviewApi.act).toHaveBeenCalledTimes(1)
})

it.each(['project', 'identity', 'viewer', 'read loss', 'reset'].flatMap(scope => ['success', 'failure'].map(late => ({ scope, late }))))('tears down review safely on $scope change and ignores late $late', async ({ scope, late }) => {
  const pending = deferred<ReviewView>()
  vi.mocked(reviewApi.act).mockReturnValueOnce(pending.promise)
  const { rerender } = render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await waitFor(() => expect(reviewApi.act).toHaveBeenCalledTimes(1))
  const signal = vi.mocked(reviewApi.act).mock.calls[0][6]?.signal
  const project = scope === 'project' ? 'new-project' : 'project'
  const nextAccess = scope === 'read loss' ? null : scope === 'viewer' ? { ...access, permissions: ['test:read'] } : { ...access, projectId: project, principalId: scope === 'identity' ? 'new-author' : access.principalId }
  rerender(<TestCasesPanel projectId={project} access={nextAccess} resetSignal={scope === 'reset' ? 1 : 0} />)
  await waitFor(() => expect(signal?.aborted).toBe(true))
  expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument()
  if (scope === 'read loss') expect(screen.getByRole('alert')).toHaveTextContent('没有读取')
  else {
    if (scope === 'reset') fireEvent.click(screen.getByRole('button', { name: '刷新用例' }))
    await waitFor(() => expect(testsApi.list).toHaveBeenCalledTimes(2))
    await openCaseIfWritable(scope)
  }
  await act(async () => { if (late === 'success') pending.resolve(submitted); else pending.reject(new TypeError('late old scope failure')) })
  expect(screen.queryByText('复核结果未知，请重试原复核意图确认结果。')).not.toBeInTheDocument()
  expect(screen.queryByText('待复核')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument()
  expect(reviewApi.act).toHaveBeenCalledTimes(1)
  if (scope !== 'read loss') expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
})
async function openCaseIfWritable(scope: string) {
  fireEvent.click(await screen.findByRole('button', { name: /TC-1/ }))
  await screen.findByRole('region', { name: '用例复核' })
  if (scope !== 'viewer') await screen.findByRole('button', { name: '提交复核' })
}

it.each(['project', 'identity', 'viewer', 'read loss', 'reset'])('clears an unknown original intent on genuine %s teardown', async scope => {
  vi.mocked(reviewApi.act).mockRejectedValueOnce(new TypeError('offline'))
  const { rerender } = render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await screen.findByText('复核结果未知，请重试原复核意图确认结果。')
  const projectId = scope === 'project' ? 'new-project' : 'project'
  const nextAccess = scope === 'read loss' ? null : scope === 'viewer' ? { ...access, permissions: ['test:read'] } : { ...access, projectId, principalId: scope === 'identity' ? 'new-author' : access.principalId }
  rerender(<TestCasesPanel projectId={projectId} access={nextAccess} resetSignal={scope === 'reset' ? 1 : 0} />)
  await waitFor(() => expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument())
  if (scope === 'read loss') expect(screen.getByRole('alert')).toHaveTextContent('没有读取')
  else {
    expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
    if (scope !== 'reset') await waitFor(() => expect(testsApi.list).toHaveBeenCalledTimes(2))
  }
  expect(reviewApi.act).toHaveBeenCalledTimes(1)
})

it.each([401, 404])('clears protected review data on HTTP %i without retaining a retry', async status => {
  vi.mocked(reviewApi.act).mockRejectedValueOnce({ status })
  render(<TestCasesPanel projectId="project" access={access} />)
  await openCase()
  fireEvent.click(screen.getByRole('button', { name: '提交复核' }))
  await screen.findByText('当前测试用例复核范围不可访问，请重新选择项目')
  expect(screen.queryByRole('region', { name: '用例复核' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '重试原复核意图' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '刷新用例' })).toBeEnabled()
  expect(reviewApi.act).toHaveBeenCalledTimes(1)
})
