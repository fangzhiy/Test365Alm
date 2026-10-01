import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
const me = { id: 'principal-1', issuer: 'https://issuer.example', subject: 'sub-1', displayName: 'A Tester' }
const tenant = { id: 'tenant-1', code: 'acme', name: 'Acme', status: 'ACTIVE' }
const project = { id: 'project-1', tenantId: 'tenant-1', domainId: null, code: 'web', name: 'Web quality', state: 'ACTIVE' }
const member = { principalId: 'principal-1', displayName: 'A Tester', roles: ['PROJECT_ADMIN'], state: 'ACTIVE' }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('workbench session cleanup', () => {
  it('clears project access data after a successful logout', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/api/v1/me')) return json(me)
      if (path.endsWith('/api/v1/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      if (path.endsWith('/api/v1/auth/logout')) return json({ status: 'LOGGED_OUT' })
      if (path.endsWith('/api/v1/version')) return json({ productName: 'Test365Alm', version: '0.0.1', commit: 'abc123' })
      if (path.endsWith('/health/live')) return json({ status: 'UP' })
      if (path.endsWith('/health/ready')) return json({ status: 'UP', database: 'UP', migration: 'APPLIED' })
      if (path.endsWith('/tenants')) return json([tenant])
      if (path.includes('/projects?')) return json([project])
      if (path.endsWith('/member-candidates')) return json([])
      if (path.endsWith('/members')) return json([member])
      return json({ tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_ADMIN'], permissions: ['project:manage-members'] })
    }))
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '加载访问范围' }))
    expect(await screen.findByText('A Tester')).toBeVisible()
    fireEvent.click(await screen.findByRole('button', { name: '退出登录' }))
    expect(await screen.findByText('未登录或会话已过期')).toBeVisible()
    expect(screen.getByText('登录后加载你有权查看的租户和项目。')).toBeVisible()
    expect(screen.queryByText('Web quality（web）')).not.toBeInTheDocument()
  })
})
