import { useCallback, useEffect, useRef, useState } from 'react'

// One scoped operation at a time; round checks also protect transports ignoring abort.
export function useCoreRequest() {
  const round = useRef(0), controller = useRef<AbortController | null>(null), timer = useRef<number | undefined>(undefined)
  const [busy, setBusy] = useState(false), [message, setMessage] = useState(''), [denied, setDenied] = useState(false)
  const cancel = useCallback(() => { round.current++; controller.current?.abort(); window.clearTimeout(timer.current) }, [])
  useEffect(() => cancel, [cancel])
  const run = useCallback(async <T,>(work: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void): Promise<'ok' | 'unknown' | 'rejected' | 'obsolete'> => {
    cancel(); const current = round.current, c = new AbortController(); controller.current = c
    setBusy(true); setMessage('')
    try {
      const timeout = new Promise<never>((_, reject) => { timer.current = window.setTimeout(() => { c.abort(); reject(new Error('请求超时，写入结果可能未知，请使用原意图重试')) }, 8000) })
      const value = await Promise.race([work(c.signal), timeout])
      if (current !== round.current) return 'obsolete'
      apply(value); return 'ok'
    } catch (e) {
      if (current !== round.current) return 'obsolete'
      const error = e as { status?: number; code?: string; message?: string }
      setMessage(error.message ?? '网络不可用，写入结果可能未知，请重试原意图')
      if (error.status === 401 || error.status === 404 || error.code === 'FORBIDDEN' || error.code === 'UNAUTHENTICATED') {
        cancel(); setBusy(false); setDenied(true)
      }
      return error.status && error.status >= 400 && error.status < 500 ? 'rejected' : 'unknown'
    } finally { if (current === round.current) { window.clearTimeout(timer.current); controller.current = null; setBusy(false) } }
  }, [cancel])
  return { run, busy, message, setMessage, denied }
}
