import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ProjectAccessPanel from './ProjectAccessPanel'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
const tenant = { id: 'tenant-1', code: 'acme', name: 'Acme', status: 'ACTIVE' }
const project = { id: 'project-1', tenantId: 'tenant-1', domainId: null, code: 'web', name: 'Web quality', state: 'ACTIVE' }
const member = { principalId: 'principal-1', displayName: 'A Tester', roles: ['PROJECT_MEMBER'], state: 'ACTIVE' }
const secondProject = { id: 'project-2', tenantId: 'tenant-1', domainId: null, code: 'api', name: 'API quality', state: 'ACTIVE' }
const secondMember = { principalId: 'principal-2', displayName: 'Second Tester', roles: ['PROJECT_MEMBER'], state: 'ACTIVE' }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('project access management', () => {
  it('loads tenant, project, members and effective permissions from the API', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project])
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project:read', 'project:manage-members'] })
    }))
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('Web quality（web）')).toBeVisible()
    expect(screen.getByText('A Tester')).toBeVisible()
    expect(screen.getByText('项目成员', { selector: 'strong' })).toBeVisible()
    expect(screen.getByText(/项目管理员 · 权限：project:read、project:manage-members/)).toBeVisible()
  })

  it('upserts a member and revokes access through the project endpoints', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project])
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      if (init?.method === 'PUT') return json({ ...member, principalId: 'principal-2', displayName: 'Second Tester', roles: ['PROJECT_VIEWER'] })
      return json({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project.read', 'project.members.write'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    await screen.findByText('A Tester')
    fireEvent.change(screen.getByLabelText('主体 ID'), { target: { value: 'principal-2' } })
    fireEvent.change(screen.getByLabelText('固定角色'), { target: { value: 'PROJECT_VIEWER' } })
    fireEvent.click(screen.getByRole('button', { name: '保存成员' }))
    expect(await screen.findByText('成员授权已保存')).toBeVisible()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project-1/members/principal-2', expect.objectContaining({ method: 'PUT' }))
    fireEvent.click(screen.getAllByRole('button', { name: '撤销访问' })[0])
    await waitFor(() => expect(screen.getByText('已撤销')).toBeVisible())
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project-1/members/principal-1', expect.objectContaining({ method: 'DELETE' }))
  })

  it('shows a structured API error without exposing an empty management state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json({ code: 'FORBIDDEN', message: '当前主体无权读取项目' }, 403)))
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('当前主体无权读取项目')
  })

  it('loads a read-only project without treating admin-only member endpoints as a fatal error', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project])
      if (path.endsWith('/member-candidates') || path.endsWith('/members')) return json({ code: 'FORBIDDEN', message: '当前主体无权读取项目成员' }, 403)
      return json({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))

    expect(await screen.findByText('Web quality（web）')).toBeVisible()
    expect(screen.getByText(/项目查看者 · 权限：project:read/)).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('新建项目')).not.toBeInTheDocument()
    expect(screen.queryByText('项目成员', { selector: 'strong' })).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/v1/projects/project-1/members', expect.anything())
    expect(fetchMock).not.toHaveBeenCalledWith('/api/v1/projects/project-1/member-candidates', expect.anything())
  })

  it('does not let a slow response for the previous project replace the selected project', async () => {
    let resolveOldMembers!: (value: Response) => void
    let resolveOldCandidates!: (value: Response) => void
    let resolveOldPermissions!: (value: Response) => void
    const oldMembers = new Promise<Response>((resolve) => { resolveOldMembers = resolve })
    const oldCandidates = new Promise<Response>((resolve) => { resolveOldCandidates = resolve })
    const oldPermissions = new Promise<Response>((resolve) => { resolveOldPermissions = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project, secondProject])
      if (path.endsWith('/members') && path.includes('/project-1/')) return oldMembers
      if (path.endsWith('/member-candidates') && path.includes('/project-1/')) return oldCandidates
      if (path.includes('/me/permissions?projectId=project-1')) return oldPermissions
      if (path.endsWith('/members')) return json([secondMember])
      if (path.endsWith('/member-candidates')) return json([])
      return json({ tenantId: 'tenant-1', projectId: 'project-2', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project:manage-members'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    const projectSelect = await screen.findByLabelText('项目')
    fireEvent.change(projectSelect, { target: { value: 'project-2' } })
    expect(await screen.findByText('Second Tester')).toBeVisible()

    resolveOldMembers(new Response(JSON.stringify([member]), { headers: { 'Content-Type': 'application/json' } }))
    resolveOldCandidates(new Response(JSON.stringify([]), { headers: { 'Content-Type': 'application/json' } }))
    resolveOldPermissions(new Response(JSON.stringify({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project:manage-members'] }), { headers: { 'Content-Type': 'application/json' } }))
    await waitFor(() => expect(screen.getByLabelText('项目')).toHaveValue('project-2')
    )
    expect(screen.queryByText('A Tester')).not.toBeInTheDocument()
    expect(screen.getByText('Second Tester')).toBeVisible()
  })

  it('clears loaded project data when the parent session generation changes', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project])
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project:manage-members'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { rerender } = render(<ProjectAccessPanel resetSignal={0} />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('A Tester')).toBeVisible()
    rerender(<ProjectAccessPanel resetSignal={1} />)
    expect(screen.getByText('登录后加载你有权查看的租户和项目。')).toBeVisible()
    expect(screen.queryByText('A Tester')).not.toBeInTheDocument()
    expect(screen.queryByText('Web quality（web）')).not.toBeInTheDocument()
  })

})
