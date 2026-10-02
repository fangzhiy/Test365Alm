import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RequirementsPanel from './RequirementsPanel'
import { requirementsApi } from './requirements'

vi.mock('./requirements', async () => {
  const actual = await vi.importActual<typeof import('./requirements')>('./requirements')
  return { ...actual, requirementsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), revisions: vi.fn() } }
})

const access = { tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_MEMBER'] as const, permissions: ['requirement:read', 'requirement:create', 'requirement:update', 'requirement:history:read'] }
const viewer = { ...access, roles: ['PROJECT_VIEWER'] as const, permissions: ['requirement:read', 'requirement:history:read'] }
const requirement = { id: 'req-1', projectId: 'project-1', displayNumber: 'REQ-1', title: 'Login flow', body: 'The user can sign in.', priority: 'MEDIUM' as const, revisionNumber: 1, rowVersion: 1, createdAt: '2026-10-01T00:00:00Z', createdBy: 'principal-1', currentRevisionId: 'rev-1' }
const revision = { id: 'rev-1', revisionNumber: 1, title: requirement.title, body: requirement.body, priority: requirement.priority, createdAt: requirement.createdAt, createdBy: 'principal-1' }
const invalidAccessErrors = [
  ['FORBIDDEN', '主体无权访问'],
  ['NOT_FOUND', '项目不存在'],
  ['UNAUTHENTICATED', '会话已失效'],
  ['IDENTITY_DISABLED', '本地身份已停用'],
] as const

afterEach(() => { cleanup(); vi.clearAllMocks(); vi.useRealTimers() })

describe('RequirementsPanel', () => {
  it('loads a real project list, details and immutable history', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('REQ-1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /REQ-1/ }))
    await screen.findByRole('button', { name: /principal-1/ })
    fireEvent.click(document.querySelector('.revision-row') as HTMLElement)
    expect(await screen.findByText('修订 1（只读）')).toBeVisible()
    expect(screen.getAllByText('The user can sign in.').length).toBeGreaterThanOrEqual(2)
  })

  it('creates a requirement with a stable project scope and idempotent API call', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Login flow' } })
    fireEvent.change(screen.getByLabelText('需求正文'), { target: { value: 'The user can sign in.' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    await waitFor(() => expect(requirementsApi.create).toHaveBeenCalledWith('project-1', { title: 'Login flow', body: 'The user can sign in.' }, expect.objectContaining({ idempotencyKey: expect.any(String), signal: expect.any(AbortSignal) })))
    expect(await screen.findByText('需求已创建')).toBeVisible()
  })

  it('locks the create draft while the request is pending and re-enables it afterwards', async () => {
    let resolveCreate: ((value: typeof requirement) => void) | undefined
    const user = userEvent.setup()
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')

    const titleInput = screen.getByLabelText('需求标题')
    const bodyInput = screen.getByLabelText('需求正文')
    await user.type(titleInput, 'Submitted A')
    await user.type(bodyInput, 'Body A')
    await user.click(screen.getByRole('button', { name: '创建需求' }))

    expect(titleInput).toBeDisabled()
    expect(bodyInput).toBeDisabled()
    expect(titleInput).toHaveValue('Submitted A')
    expect(bodyInput).toHaveValue('Body A')
    expect(requirementsApi.create).toHaveBeenCalledWith('project-1', { title: 'Submitted A', body: 'Body A' }, expect.anything())
    await user.type(titleInput, 'Later B')
    await user.type(bodyInput, 'Body B')
    expect(titleInput).toHaveValue('Submitted A')
    expect(bodyInput).toHaveValue('Body A')

    resolveCreate?.(requirement)
    expect(await screen.findByText('需求已创建')).toBeVisible()
    expect(screen.getByLabelText('需求标题')).toBeEnabled()
    expect(screen.getByLabelText('需求正文')).toBeEnabled()
  })

  it('does not let a stale list success replace a failed create operation', async () => {
    let resolveList: ((value: { items: typeof requirement[]; nextCursor: null }) => void) | undefined
    vi.mocked(requirementsApi.list).mockImplementation(() => new Promise((resolve) => { resolveList = resolve }))
    vi.mocked(requirementsApi.create).mockRejectedValue({ code: 'NETWORK_ERROR', message: '创建失败，请重试' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Create A' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByText('创建失败，请重试')).toBeVisible()

    resolveList?.({ items: [requirement], nextCursor: null })
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('创建失败，请重试')).toBeVisible()
    expect(screen.queryByText('REQ-1')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新需求' })).toBeEnabled()
  })

  it('does not let a stale AbortError from a replaced list read overwrite a failed create', async () => {
    let rejectList: ((reason?: unknown) => void) | undefined
    vi.mocked(requirementsApi.list).mockImplementation(() => new Promise((_resolve, reject) => { rejectList = reject }))
    vi.mocked(requirementsApi.create).mockRejectedValue({ code: 'NETWORK_ERROR', message: '创建失败，请重试' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Create A' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByText('创建失败，请重试')).toBeVisible()

    rejectList?.(new DOMException('cancelled', 'AbortError'))
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('创建失败，请重试')).toBeVisible()
  })

  it('restores the create form after an unknown result and retries with the same key', async () => {
    const user = userEvent.setup()
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockRejectedValueOnce(new DOMException('timed out', 'AbortError')).mockResolvedValueOnce(requirement)
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    const titleInput = screen.getByLabelText('需求标题')
    await user.type(titleInput, 'Unknown result')
    await user.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByText('需求请求超时，请稍后重试')).toBeVisible()
    expect(screen.getByLabelText('需求标题')).toBeEnabled()
    expect(screen.getByDisplayValue('Unknown result')).toBeVisible()
    const firstKey = vi.mocked(requirementsApi.create).mock.calls[0][2]?.idempotencyKey

    await user.click(screen.getByRole('button', { name: '创建需求' }))
    await waitFor(() => expect(requirementsApi.create).toHaveBeenCalledTimes(2))
    expect(vi.mocked(requirementsApi.create).mock.calls[1][2]?.idempotencyKey).toBe(firstKey)
    expect(await screen.findByText('需求已创建')).toBeVisible()
  })

  it('does not let a detail read replaced by create overwrite a failed create result', async () => {
    let rejectDetail: ((reason?: unknown) => void) | undefined
    let detailCalls = 0
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockImplementation(async () => {
      if (detailCalls++ === 0) return new Promise((_resolve, reject) => { rejectDetail = reject })
      return requirement
    })
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.create).mockRejectedValue({ code: 'NETWORK_ERROR', message: '创建失败，请重试' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Create while detail loads' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByText('创建失败，请重试')).toBeVisible()

    rejectDetail?.(new Error('旧详情失败'))
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('创建失败，请重试')).toBeVisible()
  })

  it('invalidates a refresh read before saving so its late failure cannot replace the save result', async () => {
    let rejectRefresh: ((reason?: unknown) => void) | undefined
    let listCalls = 0
    vi.mocked(requirementsApi.list).mockImplementation(async () => {
      if (listCalls++ === 0) return { items: [requirement], nextCursor: null }
      return new Promise((_resolve, reject) => { rejectRefresh = reject })
    })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockRejectedValue({ code: 'NETWORK_ERROR', message: '保存失败，请重试' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'Save A' } })

    const form = screen.getByRole('button', { name: '保存需求' }).closest('form')
    expect(form).not.toBeNull()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '刷新需求' }))
      fireEvent.submit(form as HTMLFormElement)
      await Promise.resolve()
    })
    await waitFor(() => expect(requirementsApi.update).toHaveBeenCalled())
    expect(await screen.findByText('保存失败，请重试')).toBeVisible()

    rejectRefresh?.(new Error('旧刷新失败'))
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('保存失败，请重试')).toBeVisible()
    expect(screen.getByRole('button', { name: '刷新需求' })).toBeEnabled()
  })

  it('preserves the unsaved draft on 412 and does not overwrite it', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockRejectedValue({ code: 'HTTP_412', message: '版本冲突' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'My unsaved draft' } })
    fireEvent.click(screen.getByRole('button', { name: '保存需求' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('草稿已保留')
    expect(screen.getByDisplayValue('My unsaved draft')).toBeVisible()
    expect(screen.getByText('草稿已保留')).toBeVisible()
  })

  it('does not expose create/edit controls to a viewer', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={viewer} />)
    expect(await screen.findByText('REQ-1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /REQ-1/ }))
    await screen.findByText('只读用户可以查看需求和历史，但不能创建或保存。')
    expect(screen.queryByRole('button', { name: '创建需求' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存需求' })).not.toBeInTheDocument()
    expect(requirementsApi.create).not.toHaveBeenCalled()
  })

  it('clears old project data when project context changes', async () => {
    vi.mocked(requirementsApi.list).mockImplementation(async (projectId) => projectId === 'project-1' ? { items: [requirement], nextCursor: null } : { items: [], nextCursor: null })
    const { rerender } = render(<RequirementsPanel projectId="project-1" access={access} resetSignal={0} />)
    expect(await screen.findByText('REQ-1')).toBeVisible()
    rerender(<RequirementsPanel projectId="project-2" access={{ ...access, projectId: 'project-2' }} resetSignal={0} />)
    expect(await screen.findByText('当前项目还没有需求。')).toBeVisible()
    expect(screen.queryByText('REQ-1')).not.toBeInTheDocument()
  })

  it('leaves a timed-out load retryable instead of stuck in loading', async () => {
    vi.useFakeTimers()
    vi.mocked(requirementsApi.list).mockImplementation((_projectId, options) => new Promise((_resolve, reject) => options?.signal?.addEventListener('abort', () => reject(new DOMException('timed out', 'AbortError')))))
    render(<RequirementsPanel projectId="project-1" access={access} />)
    expect(screen.getByRole('button', { name: '加载中…' })).toBeDisabled()
    await act(async () => { vi.advanceTimersByTime(5000); await Promise.resolve() })
    expect(screen.getByRole('alert')).toHaveTextContent('超时')
    expect(screen.getByRole('button', { name: '刷新需求' })).toBeEnabled()
  })

  it('does not let a slow save from project A overwrite project B', async () => {
    let resolveSave: ((value: typeof requirement) => void) | undefined
    vi.mocked(requirementsApi.list).mockImplementation(async (projectId) => projectId === 'project-1' ? { items: [requirement], nextCursor: null } : { items: [{ ...requirement, id: 'req-2', projectId: 'project-2', displayNumber: 'REQ-2', title: 'Project B' }], nextCursor: null })
    vi.mocked(requirementsApi.get).mockImplementation(async (_projectId, id) => id === 'req-2' ? { ...requirement, id: 'req-2', projectId: 'project-2', displayNumber: 'REQ-2', title: 'Project B' } : requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockImplementation(() => new Promise((resolve) => { resolveSave = resolve }))
    const { rerender } = render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'Old save' } })
    fireEvent.click(screen.getByRole('button', { name: '保存需求' }))
    rerender(<RequirementsPanel projectId="project-2" access={{ ...access, projectId: 'project-2' }} />)
    expect(await screen.findByText('REQ-2')).toBeVisible()
    resolveSave?.({ ...requirement, title: 'Old save' })
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('Project B')).toBeVisible()
    expect(screen.queryByText('Old save')).not.toBeInTheDocument()
  })

  it('ignores a slow save after leaving the project context', async () => {
    let resolveSave: ((value: typeof requirement) => void) | undefined
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockImplementation(() => new Promise((resolve) => { resolveSave = resolve }))
    const { rerender } = render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'Pending' } })
    fireEvent.click(screen.getByRole('button', { name: '保存需求' }))
    rerender(<RequirementsPanel projectId="" access={null} />)
    resolveSave?.({ ...requirement, title: 'Pending' })
    await act(async () => { await Promise.resolve() })
    expect(screen.getByText('请选择一个项目以查看需求。')).toBeVisible()
    expect(screen.queryByText('Pending')).not.toBeInTheDocument()
  })

  it('keeps a conflict draft when viewing the latest server version', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValueOnce(requirement).mockResolvedValue({ ...requirement, title: 'Server latest', rowVersion: 2 })
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockRejectedValue({ code: 'HTTP_412', message: '版本冲突' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'Keep this draft' } })
    fireEvent.click(screen.getByRole('button', { name: '保存需求' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: '查看最新版本' }))
    await waitFor(() => expect(screen.getByDisplayValue('Keep this draft')).toBeVisible())
    expect(screen.getAllByText('Server latest').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('草稿已保留')).toBeVisible()
  })

  it('clears the selected requirement when detail access is revoked', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockRejectedValue({ code: 'HTTP_403', message: '无权访问' })
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('不可访问')
    expect(screen.queryByText('Login flow')).not.toBeInTheDocument()
  })

  it.each(invalidAccessErrors)('clears old list data for %s returned by the API', async (code, message) => {
    let calls = 0
    vi.mocked(requirementsApi.list).mockImplementation(async () => {
      if (calls++ === 0) return { items: [requirement], nextCursor: null }
      throw { code, message }
    })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('REQ-1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '刷新需求' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前项目不可访问')
    expect(screen.queryByText('REQ-1')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('需求标题')).not.toBeInTheDocument()
  })

  it.each(invalidAccessErrors)('clears selected detail and history for %s', async (code, message) => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockRejectedValue({ code, message })
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前需求不可访问')
    expect(screen.queryByLabelText('需求详情')).not.toBeInTheDocument()
    expect(screen.queryByText('REQ-1')).not.toBeInTheDocument()
  })

  it.each(invalidAccessErrors)('clears selected detail when history returns %s', async (code, message) => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockRejectedValue({ code, message })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前需求不可访问')
    expect(screen.queryByLabelText('需求详情')).not.toBeInTheDocument()
  })

  it.each(invalidAccessErrors)('clears create draft and write controls for %s', async (code, message) => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockRejectedValue({ code, message })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Sensitive draft' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前项目不可访问')
    expect(screen.queryByDisplayValue('Sensitive draft')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建需求' })).not.toBeInTheDocument()
  })

  it.each(invalidAccessErrors)('clears saved detail and write controls for %s', async (code, message) => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    vi.mocked(requirementsApi.update).mockRejectedValue({ code, message })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /REQ-1/ }))
    await screen.findByDisplayValue('Login flow')
    fireEvent.change(screen.getByLabelText('编辑需求标题'), { target: { value: 'Should clear' } })
    fireEvent.click(screen.getByRole('button', { name: '保存需求' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前需求不可访问')
    expect(screen.queryByLabelText('需求详情')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('Should clear')).not.toBeInTheDocument()
  })

  it('clears scope for an explicit FORBIDDEN domain error carried by HTTP 403', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockRejectedValue({ code: 'FORBIDDEN', status: 403, message: '项目访问被拒绝' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Sensitive draft' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前项目不可访问')
    expect(screen.queryByLabelText('需求标题')).not.toBeInTheDocument()
  })

  it('keeps the current scope when a write is rejected by CSRF HTTP 403', async () => {
    vi.mocked(requirementsApi.list).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(requirementsApi.create).mockRejectedValue({ code: 'CSRF_REJECTED', status: 403, message: '安全令牌无效' })
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Keep draft' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    expect(await screen.findByText('安全令牌无效')).toBeVisible()
    expect(screen.getByDisplayValue('Keep draft')).toBeVisible()
    expect(screen.getByRole('button', { name: '创建需求' })).toBeVisible()
  })

  it('keeps a network create draft and reuses its idempotency key after refresh', async () => {
    let listCalls = 0
    vi.mocked(requirementsApi.list).mockImplementation(async () => listCalls++ === 0 ? { items: [], nextCursor: null } : { items: [requirement], nextCursor: null })
    vi.mocked(requirementsApi.create).mockRejectedValueOnce({ code: 'NETWORK_ERROR', message: '网络连接断开' }).mockResolvedValueOnce(requirement)
    vi.mocked(requirementsApi.get).mockResolvedValue(requirement)
    vi.mocked(requirementsApi.revisions).mockResolvedValue([revision])
    render(<RequirementsPanel projectId="project-1" access={access} />)
    await screen.findByText('当前项目还没有需求。')
    fireEvent.change(screen.getByLabelText('需求标题'), { target: { value: 'Retry me' } })
    fireEvent.change(screen.getByLabelText('需求正文'), { target: { value: 'Keep this draft' } })
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    await waitFor(() => expect(requirementsApi.create).toHaveBeenCalledTimes(1))
    const firstKey = vi.mocked(requirementsApi.create).mock.calls[0][2]?.idempotencyKey
    expect(screen.getByDisplayValue('Retry me')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '刷新需求' }))
    expect(await screen.findByText('REQ-1')).toBeVisible()
    expect(screen.getByDisplayValue('Retry me')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '创建需求' }))
    await waitFor(() => expect(requirementsApi.create).toHaveBeenCalledTimes(2))
    expect(vi.mocked(requirementsApi.create).mock.calls[1][2]?.idempotencyKey).toBe(firstKey)
  })

  it('clears and reloads deterministically across project A, empty, then B', async () => {
    vi.mocked(requirementsApi.list).mockImplementation(async (projectId) => {
      if (projectId === 'project-1') return { items: [requirement], nextCursor: null }
      if (projectId === 'project-2') return { items: [{ ...requirement, id: 'req-2', projectId: 'project-2', displayNumber: 'REQ-2', title: 'Project B' }], nextCursor: null }
      return { items: [], nextCursor: null }
    })
    const { rerender } = render(<RequirementsPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('REQ-1')).toBeVisible()
    rerender(<RequirementsPanel projectId="" access={null} />)
    expect(screen.getByText('请选择一个项目以查看需求。')).toBeVisible()
    rerender(<RequirementsPanel projectId="project-2" access={{ ...access, projectId: 'project-2' }} />)
    expect(await screen.findByText('REQ-2')).toBeVisible()
    expect(screen.queryByText('REQ-1')).not.toBeInTheDocument()
  })

})
