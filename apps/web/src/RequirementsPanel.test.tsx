import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
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

})
