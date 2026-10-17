export type SafeLocation = { pathname: string; loginFailed: boolean }
export type SafeHttpEvent = SafeLocation & { status: number; observedAt: string }

export function safeLocation(rawUrl: string): SafeLocation {
  try {
    const url = new URL(rawUrl)
    return { pathname: url.pathname, loginFailed: url.searchParams.get('login') === 'failed' }
  } catch {
    return { pathname: '[unavailable]', loginFailed: false }
  }
}

export function safeHttpEvent(rawUrl: string, status: number, observedAt: string): SafeHttpEvent | null {
  const location = safeLocation(rawUrl)
  if (!Number.isInteger(status) || status < 100 || status > 599) return null
  if (!location.pathname.startsWith('/api/v1/')
    && location.pathname !== '/oauth2/authorization/test365alm'
    && location.pathname !== '/login/oauth2/code/test365alm') return null
  return { ...location, status, observedAt }
}
