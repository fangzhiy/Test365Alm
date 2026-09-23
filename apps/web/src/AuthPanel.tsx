import { useEffect, useState } from 'react'

type Me = { id: string; issuer: string; subject: string; displayName: string }
type AuthState = 'loading' | 'anonymous' | 'authenticated' | 'disabled' | 'unavailable' | 'failed'

function isMe(value: unknown): value is Me {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const record = value as Record<string, unknown>
  return ['id', 'issuer', 'subject', 'displayName'].every((key) => typeof record[key] === 'string' && (record[key] as string).length > 0)
}

export default function AuthPanel() {
  const [state, setState] = useState<AuthState>('loading')
  const [me, setMe] = useState<Me | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    let alive = true
    const timeout = window.setTimeout(() => { controller.abort(); if (alive) setState('unavailable') }, 3000)
    const failed = new URLSearchParams(window.location.search).get('login') === 'failed'
    void fetch('/api/v1/me', { signal: controller.signal, credentials: 'same-origin' })
      .then(async (response) => {
        if (!alive) return
        if (response.status === 401) { setState(failed ? 'failed' : 'anonymous'); return }
        if (response.status === 403) { setState('disabled'); return }
        if (!response.ok) throw new Error('Current user request failed')
        const body: unknown = await response.json()
        if (!isMe(body)) throw new Error('Invalid current user response')
        if (!alive) return
        setMe(body)
        setState('authenticated')
      })
      .catch(() => { if (alive) setState('unavailable') })
      .finally(() => window.clearTimeout(timeout))
    return () => { alive = false; controller.abort(); window.clearTimeout(timeout) }
  }, [])

  const logout = async () => {
    setBusy(true)
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 3000)
    try {
      const csrfResponse = await fetch('/api/v1/csrf', { credentials: 'same-origin', signal: controller.signal })
      if (!csrfResponse.ok) throw new Error('CSRF request failed')
      const csrf: unknown = await csrfResponse.json()
      if (typeof csrf !== 'object' || csrf === null || !('headerName' in csrf) || !('token' in csrf)
        || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') throw new Error('Invalid CSRF response')
      const response = await fetch('/api/v1/auth/logout', {
        method: 'POST', credentials: 'same-origin', headers: { [csrf.headerName]: csrf.token }, signal: controller.signal,
      })
      if (!response.ok) throw new Error('Logout failed')
      setMe(null)
      setState('anonymous')
    } catch {
      setState('unavailable')
    } finally {
      window.clearTimeout(timeout)
      setBusy(false)
    }
  }

  return <section className="auth-panel" aria-label="登录状态">
    <div><span className="panel-label">当前身份</span>
      {state === 'loading' && <strong>检查登录状态…</strong>}
      {state === 'authenticated' && me && <strong>{me.displayName}</strong>}
      {state === 'anonymous' && <strong>未登录或会话已过期</strong>}
      {state === 'failed' && <strong>登录失败，请检查身份提供方后重试</strong>}
      {state === 'disabled' && <strong>本地身份已停用</strong>}
      {state === 'unavailable' && <strong>身份服务暂不可用</strong>}
    </div>
    {state === 'authenticated' ? <button type="button" disabled={busy} onClick={() => void logout()}>退出登录</button>
      : <a href="/oauth2/authorization/test365alm">登录 Test365Alm</a>}
  </section>
}
