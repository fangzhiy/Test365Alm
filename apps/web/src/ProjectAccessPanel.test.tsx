import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ProjectAccessPanel from './ProjectAccessPanel'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
const tenant = { id: 'tenant-1', code: 'acme', name: 'Acme', status: 'ACTIVE', rowVersion: 0, canCreateProject: true }
const project = { id: 'project-1', tenantId: 'tenant-1', domainId: 'domain-1', code: 'web', name: 'Web quality', state: 'ACTIVE', rowVersion: 0 }
const domain = { id: 'domain-1', tenantId: 'tenant-1', name: 'Quality', status: 'ACTIVE', rowVersion: 0 }
const member = { principalId: 'principal-1', displayName: 'A Tester', roles: ['PROJECT_MEMBER'], state: 'ACTIVE', authorizationVersion: 1 }
const secondProject = { id: 'project-2', tenantId: 'tenant-1', domainId: 'domain-1', code: 'api', name: 'API quality', state: 'ACTIVE', rowVersion: 0 }
const secondMember = { principalId: 'principal-2', displayName: 'Second Tester', roles: ['PROJECT_MEMBER'], state: 'ACTIVE', authorizationVersion: 1 }
const tenantB = { id: 'tenant-2', code: 'beta', name: 'Beta', status: 'ACTIVE', rowVersion: 0, canCreateProject: false }
const projectB = { id: 'project-b', tenantId: 'tenant-2', domainId: 'domain-b', code: 'beta-api', name: 'Beta API', state: 'ACTIVE', rowVersion: 0 }
const domainB = { id: 'domain-b', tenantId: 'tenant-2', name: 'Beta quality', status: 'ACTIVE', rowVersion: 0 }

const deferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

afterEach(() => { vi.useRealTimers(); cleanup(); vi.unstubAllGlobals() })

describe('project access management', () => {
  it('loads tenant, project, members and effective permissions from the API', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
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

  it('finishes loading after switching tenants while the default tenant is still pending', async () => {
    const oldDomains = deferred<Response>()
    const oldProjects = deferred<Response>()
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant, tenantB])
      if (path === '/api/v1/domains?tenantId=tenant-1') return oldDomains.promise
      if (path === '/api/v1/projects?tenantId=tenant-1') return oldProjects.promise
      if (path === '/api/v1/domains?tenantId=tenant-2') return json([domainB])
      if (path === '/api/v1/projects?tenantId=tenant-2') return json([projectB])
      if (path === '/api/v1/projects/project-b') return json(projectB)
      if (path.endsWith('/member-candidates') || path.endsWith('/members')) return json([])
      return json({ tenantId: tenantB.id, projectId: projectB.id, principalId: 'viewer-b', roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))

    const tenantSelect = await screen.findByLabelText('租户')
    fireEvent.change(tenantSelect, { target: { value: tenantB.id } })
    expect(await screen.findByText('Beta API（beta-api）')).toBeVisible()
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()

    oldDomains.resolve(json([domain]))
    oldProjects.resolve(json([project]))
    await waitFor(() => expect(screen.getByLabelText('租户')).toHaveValue(tenantB.id))
    expect(screen.getByText('Beta API（beta-api）')).toBeVisible()
    expect(screen.queryByText('Web quality（web）')).not.toBeInTheDocument()
  })

  it('keeps the new tenant ready when the previous tenant eventually fails', async () => {
    const oldDomains = deferred<Response>()
    const oldProjects = deferred<Response>()
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant, tenantB])
      if (path === '/api/v1/domains?tenantId=tenant-1') return oldDomains.promise
      if (path === '/api/v1/projects?tenantId=tenant-1') return oldProjects.promise
      if (path === '/api/v1/domains?tenantId=tenant-2') return json([domainB])
      if (path === '/api/v1/projects?tenantId=tenant-2') return json([projectB])
      if (path === '/api/v1/projects/project-b') return json(projectB)
      if (path.endsWith('/member-candidates') || path.endsWith('/members')) return json([])
      return json({ tenantId: tenantB.id, projectId: projectB.id, principalId: 'viewer-b', roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    const tenantSelect = await screen.findByLabelText('租户')
    fireEvent.change(tenantSelect, { target: { value: tenantB.id } })
    expect(await screen.findByText('Beta API（beta-api）')).toBeVisible()
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()

    oldDomains.resolve(json([domain]))
    oldProjects.reject(new Error('old tenant failed late'))
    await waitFor(() => expect(screen.getByLabelText('租户')).toHaveValue(tenantB.id))
    expect(screen.getByText('Beta API（beta-api）')).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('leaves a failed tenant switch in a retryable state', async () => {
    const oldDomains = deferred<Response>()
    const oldProjects = deferred<Response>()
    let betaAvailable = false
    let tenantReads = 0
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json(tenantReads++ === 0 ? [tenant, tenantB] : [tenantB])
      if (path === '/api/v1/domains?tenantId=tenant-1') return oldDomains.promise
      if (path === '/api/v1/projects?tenantId=tenant-1') return oldProjects.promise
      if (path === '/api/v1/domains?tenantId=tenant-2') return json([domainB])
      if (path === '/api/v1/projects?tenantId=tenant-2') return betaAvailable ? json([projectB]) : json({ code: 'HTTP_503', message: 'Beta 暂不可用' }, 503)
      if (path === '/api/v1/projects/project-b') return json(projectB)
      if (path.endsWith('/member-candidates') || path.endsWith('/members')) return json([])
      return json({ tenantId: tenantB.id, projectId: projectB.id, principalId: 'viewer-b', roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    fireEvent.change(await screen.findByLabelText('租户'), { target: { value: tenantB.id } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Beta 暂不可用')
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()

    oldDomains.resolve(json([domain]))
    oldProjects.reject(new Error('old tenant failed late'))
    betaAvailable = true
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('Beta API（beta-api）')).toBeVisible()
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()
  })

  it('treats an empty tenant list as ready and retryable', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).endsWith('/tenants') ? json([]) : json([])))
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('当前会话没有可管理的租户')).toBeVisible()
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()
  })

  it('exits loading on timeout and keeps the retry button enabled', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (!String(input).endsWith('/tenants')) return json([])
      return new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(3001) })
    vi.useRealTimers()
    expect(await screen.findByRole('alert')).toHaveTextContent('访问范围加载超时')
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()
  })

  it('aborts a pending request when the panel is unmounted', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (!String(input).endsWith('/tenants')) return json([])
      return new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(3001) })
    vi.useRealTimers()
  })

  it('keeps the latest scope state under React StrictMode', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
      return json({ tenantId: tenant.id, projectId: project.id, principalId: member.principalId, roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    }))
    render(<StrictMode><ProjectAccessPanel /></StrictMode>)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('Web quality（web）')).toBeVisible()
    expect(screen.getByRole('button', { name: '加载访问范围' })).toBeEnabled()
  })

  it('upserts a member and revokes access through the project endpoints', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      if (init?.method === 'PUT') return json({ ...member, principalId: 'principal-2', displayName: 'Second Tester', roles: ['PROJECT_VIEWER'] })
      if (init?.method === 'DELETE') return json({ ...member, state: 'REVOKED', authorizationVersion: 2 })
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
      if (path.endsWith('/tenants')) return json([{ ...tenant, canCreateProject: false }])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
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

  it('renders project details and sends the selected row version for an admin edit', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1' && init?.method === 'PATCH') return json({ ...project, name: 'Renamed project', rowVersion: 1 })
      if (path === '/api/v1/projects/project-1') return json(project)
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: tenant.id, projectId: project.id, principalId: member.principalId, roles: ['PROJECT_ADMIN'], permissions: ['project:read', 'project:write', 'project:manage-members'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByRole('region', { name: '项目详情' })).toHaveTextContent('Web quality')
    fireEvent.change(screen.getByLabelText('项目名称'), { target: { value: 'Renamed project' } })
    fireEvent.click(screen.getByRole('button', { name: '保存项目' }))
    expect(await screen.findByText('项目已更新')).toBeVisible()
    const patchCall = fetchMock.mock.calls.find(([input, init]) => String(input) === '/api/v1/projects/project-1' && init?.method === 'PATCH')
    expect(patchCall?.[1]).toEqual(expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ name: 'Renamed project', rowVersion: 0 }) }))
  })

  it('keeps viewer project details read-only and does not render admin controls', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([{ ...tenant, canCreateProject: false }])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
      return json({ tenantId: tenant.id, projectId: project.id, principalId: 'viewer-1', roles: ['PROJECT_VIEWER'], permissions: ['project:read'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByRole('region', { name: '项目详情' })).toHaveTextContent('Web quality')
    expect(screen.queryByRole('button', { name: '保存项目' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建项目' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存成员' })).not.toBeInTheDocument()
  })

  it('uses the server tenant capability to hide project creation when it is disabled', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/tenants')) return json([{ ...tenant, canCreateProject: false }])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: tenant.id, projectId: project.id, principalId: member.principalId, roles: ['PROJECT_ADMIN'], permissions: ['project:manage-members'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    await screen.findByRole('region', { name: '项目详情' })
    expect(screen.queryByRole('button', { name: '创建项目' })).not.toBeInTheDocument()
    expect(screen.queryByText('新建项目')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith('/api/v1/projects', expect.objectContaining({ method: 'POST' }))
  })

  it('shows a stale-version conflict without replacing the current project detail', async () => {
    let projectReads = 0
    const refreshedProject = { ...project, name: 'Concurrent update', rowVersion: 1 }
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1' && init?.method === 'PATCH') return json({ code: 'STALE_VERSION', message: '项目已被其他请求更新' }, 409)
      if (path === '/api/v1/projects/project-1') return json(projectReads++ === 0 ? project : refreshedProject)
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: tenant.id, projectId: project.id, principalId: member.principalId, roles: ['PROJECT_ADMIN'], permissions: ['project:read', 'project:write', 'project:manage-members'] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ProjectAccessPanel />)
    fireEvent.click(screen.getByRole('button', { name: '加载访问范围' }))
    await screen.findByRole('region', { name: '项目详情' })
    fireEvent.change(screen.getByLabelText('项目名称'), { target: { value: 'stale edit' } })
    fireEvent.click(screen.getByRole('button', { name: '保存项目' }))
    expect(await screen.findByText('项目已被其他请求更新')).toBeVisible()
    expect(screen.getByRole('region', { name: '项目详情' })).toHaveTextContent('Concurrent update')
    expect(screen.getByRole('region', { name: '项目详情' })).toHaveTextContent('版本 1')
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
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project, secondProject])
      if (path === '/api/v1/projects/project-1') return json(project)
      if (path === '/api/v1/projects/project-2') return json(secondProject)
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
      if (path.includes('/domains?')) return json([domain])
      if (path.includes('/projects?')) return json([project])
      if (path === '/api/v1/projects/project-1') return json(project)
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
