export type SafeLocation = { pathname: string; loginFailed: boolean }
export type SafeHttpEvent = SafeLocation & { status: number; observedAt: string; diagnosticId?: string; authStage?: string; authCategory?: string }

export function safeLocation(rawUrl: string): SafeLocation {
  try {
    const url = new URL(rawUrl)
    return { pathname: url.pathname, loginFailed: url.searchParams.get('login') === 'failed' }
  } catch {
    return { pathname: '[unavailable]', loginFailed: false }
  }
}

export function safeHttpEvent(rawUrl: string, status: number, observedAt: string, headers: Record<string, string> = {}): SafeHttpEvent | null {
  const location = safeLocation(rawUrl)
  if (!Number.isInteger(status) || status < 100 || status > 599) return null
  if (!location.pathname.startsWith('/api/v1/')
    && location.pathname !== '/oauth2/authorization/test365alm'
    && location.pathname !== '/login/oauth2/code/test365alm') return null
  const id = headers['x-auth-diagnostic-id'], stage = headers['x-auth-stage'], category = headers['x-auth-category']
  const stages = ['authorization', 'callback', 'session_association', 'oauth_validation', 'token_validation', 'token_exchange', 'user_info', 'local_principal', 'authentication', 'login', 'signature']
  const categories = ['redirected', 'no_session', 'session_present', 'request_missing', 'state_rejected', 'token_rejected', 'exchange_rejected', 'profile_rejected', 'identity_disabled', 'persistence_failed', 'access_denied', 'unclassified_failure', 'authenticated', 'binding_or_permission_rejected']
  return { ...location, status, observedAt,
    ...(id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id) ? { diagnosticId: id } : {}),
    ...(stages.includes(stage) ? { authStage: stage } : {}),
    ...(categories.includes(category) ? { authCategory: category } : {}),
  }
}
