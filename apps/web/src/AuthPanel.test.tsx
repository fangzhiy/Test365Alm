import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuthPanel from './AuthPanel'

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }))
const me = { id: '123', issuer: 'http://127.0.0.1:18090/realms/test365alm', subject: 'sub-1', displayName: 'R03 Tester' }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('identity panel', () => {
  it('shows verified current user without token fields', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json(me)))
    render(<AuthPanel />)
    expect(await screen.findByText('R03 Tester')).toBeVisible()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeVisible()
    expect(screen.queryByText('access_token')).not.toBeInTheDocument()
  })

  it('shows anonymous, disabled and unavailable states distinctly', async () => {
    for (const [response, label] of [
      [() => json({ code: 'UNAUTHENTICATED' }, 401), '未登录或会话已过期'],
      [() => json({ code: 'IDENTITY_DISABLED' }, 403), '本地身份已停用'],
      [() => Promise.reject(new TypeError('offline')), '身份服务暂不可用'],
    ] as const) {
      vi.stubGlobal('fetch', vi.fn(response))
      const view = render(<AuthPanel />)
      expect(await screen.findByText(label)).toBeVisible()
      view.unmount()
    }
  })

  it('gets CSRF token and logs out locally', async () => {
    vi.stubGlobal('fetch', vi.fn((path: RequestInfo | URL) => {
      if (String(path).endsWith('/me')) return json(me)
      if (String(path).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' })
      return json({ status: 'LOGGED_OUT' })
    }))
    render(<AuthPanel />)
    fireEvent.click(await screen.findByRole('button', { name: '退出登录' }))
    await waitFor(() => expect(screen.getByText('未登录或会话已过期')).toBeVisible())
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/v1/auth/logout', expect.objectContaining({ method: 'POST', headers: { 'X-CSRF-TOKEN': 'csrf-test' } }))
    expect(screen.getByRole('link', { name: '登录 Test365Alm' })).toHaveAttribute('href', '/oauth2/authorization/test365alm')
  })

  it('does not falsely show a malformed success as authenticated', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json({ displayName: 'Untrusted' })))
    render(<AuthPanel />)
    expect(await screen.findByText('身份服务暂不可用')).toBeVisible()
  })
})
