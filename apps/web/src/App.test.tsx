import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, act } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'
import App from './App'

const jsonResponse = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
const healthyResponses = () => vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => { const path = String(input); if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.1', commit: 'abc123' }); if (path.includes('/health/live')) return jsonResponse({ status: 'UP' }); return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' }) }))
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => { resolve = resolvePromise; reject = rejectPromise })
  return { promise, resolve, reject }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })
describe('Test365Alm workbench', () => {
  it('shows real version and healthy status', async () => { healthyResponses(); render(<App />); expect(await screen.findByText('0.0.1')).toBeVisible(); expect(screen.getAllByText('正常')).toHaveLength(3); expect(screen.getByText('abc123')).toBeVisible() })
  it('shows readiness as unavailable when the backend returns 503', async () => { vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => { const path = String(input); if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.1', commit: 'abc123' }); if (path.includes('/health/live')) return jsonResponse({ status: 'UP' }); return jsonResponse({ status: 'DOWN', database: 'DOWN', migration: 'UNKNOWN' }, 503) })); render(<App />); await waitFor(() => expect(screen.getByText('不可用')).toBeVisible()); expect(screen.getByText('数据库 DOWN · 迁移 UNKNOWN')).toBeVisible() })
  it('shows backend unavailable and does not retain stale version data', async () => { healthyResponses(); render(<App />); expect(await screen.findByText('0.0.1')).toBeVisible(); vi.mocked(fetch).mockRejectedValue(new TypeError('network down')); fireEvent.click(screen.getByRole('button', { name: '刷新状态' })); await waitFor(() => expect(screen.getAllByText('无法连接')).toHaveLength(3)); expect(screen.queryByText('0.0.1')).not.toBeInTheDocument(); expect(screen.getByText('不可用')).toBeVisible() })
  it('recovers after a later successful refresh', async () => { vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('network down')).mockRejectedValueOnce(new TypeError('network down')).mockRejectedValueOnce(new TypeError('network down')).mockImplementation((input: RequestInfo | URL) => { const path = String(input); if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.2', commit: 'def456' }); if (path.includes('/health/live')) return jsonResponse({ status: 'UP' }); return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' }) })); render(<App />); await waitFor(() => expect(screen.getAllByText('无法连接')).toHaveLength(3)); fireEvent.click(screen.getByRole('button', { name: '刷新状态' })); expect(await screen.findByText('0.0.2')).toBeVisible(); expect(screen.getAllByText('正常')).toHaveLength(3) })

  it('keeps the newest failed refresh when an older refresh resolves slowly', async () => {
    const staleRequests = new Map<string, ReturnType<typeof deferred<Response>>>()
    let callCount = 0
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      callCount += 1
      if (callCount <= 3) {
        if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.1', commit: 'initial' })
        if (path.includes('/health/live')) return jsonResponse({ status: 'UP' })
        return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' })
      }
      if (callCount <= 6) {
        const request = deferred<Response>()
        staleRequests.set(path, request)
        return request.promise
      }
      return Promise.reject(new TypeError('new request failed'))
    }))
    render(<App />)
    expect(await screen.findByText('0.0.1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(6))
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    await waitFor(() => expect(screen.getAllByText('无法连接')).toHaveLength(3))
    staleRequests.get('/api/v1/version')?.resolve(await new Response(JSON.stringify({ productName: 'Test365Alm', version: 'stale', commit: 'stale' })))
    staleRequests.get('/health/live')?.resolve(await new Response(JSON.stringify({ status: 'UP' })))
    staleRequests.get('/health/ready')?.resolve(await new Response(JSON.stringify({ status: 'UP', database: 'UP', migration: 'APPLIED' })))
    await waitFor(() => expect(screen.getAllByText('无法连接')).toHaveLength(3))
    expect(screen.queryAllByText('stale')).toHaveLength(0)
  })

  it('keeps the newest successful refresh when an older refresh fails slowly', async () => {
    const staleRequests = new Map<string, ReturnType<typeof deferred<Response>>>()
    let callCount = 0
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      callCount += 1
      if (callCount <= 3) return Promise.reject(new TypeError('initial request failed'))
      if (callCount <= 6) {
        const request = deferred<Response>()
        staleRequests.set(path, request)
        return request.promise
      }
      if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.2', commit: 'new' })
      if (path.includes('/health/live')) return jsonResponse({ status: 'UP' })
      return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' })
    }))
    render(<App />)
    await waitFor(() => expect(screen.getAllByText('无法连接')).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(6))
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    expect(await screen.findByText('0.0.2')).toBeVisible()
    staleRequests.get('/api/v1/version')?.reject(new TypeError('stale request failed'))
    staleRequests.get('/health/live')?.reject(new TypeError('stale request failed'))
    staleRequests.get('/health/ready')?.reject(new TypeError('stale request failed'))
    await waitFor(() => expect(screen.getAllByText('正常')).toHaveLength(3))
  })

  it('does not treat invalid JSON or invalid health fields as healthy and recovers', async () => {
    let healthy = false
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (healthy) {
        if (path.includes('/api/v1/version')) return jsonResponse({ productName: 'Test365Alm', version: '0.0.3', commit: 'recovered' })
        if (path.includes('/health/live')) return jsonResponse({ status: 'UP' })
        return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' })
      }
      if (path.includes('/api/v1/version')) return Promise.resolve(new Response('<html>not json</html>', { status: 200 }))
      if (path.includes('/health/live')) return jsonResponse({ status: 'UP' })
      return jsonResponse({ status: 'UP', database: 123, migration: null })
    }))
    render(<App />)
    await waitFor(() => expect(screen.getByText('不可用')).toBeVisible())
    expect(screen.getAllByText('正常')).toHaveLength(1)
    expect(screen.getByText('不可用')).toBeVisible()
    healthy = true
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    expect(await screen.findByText('0.0.3')).toBeVisible()
    expect(screen.getAllByText('正常')).toHaveLength(3)
  })

  it.each([
    ['empty object', {}],
    ['null', null],
    ['missing commit', { productName: 'Test365Alm', version: '0.0.4' }],
    ['wrong version type', { productName: 'Test365Alm', version: '0.0.4', commit: 42 }],
  ])('rejects a %s version response', async (_name, invalidVersion) => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.includes('/api/v1/version')) return jsonResponse(invalidVersion)
      if (path.includes('/health/live')) return jsonResponse({ status: 'UP' })
      return jsonResponse({ status: 'UP', database: 'UP', migration: 'APPLIED' })
    }))
    render(<App />)
    await waitFor(() => expect(screen.getByText('不可用')).toBeVisible())
    expect(screen.getAllByText('正常')).toHaveLength(2)
  })

  it('aborts requests and clears the timeout on unmount', async () => {
    vi.useFakeTimers()
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal
      if (signal) signals.push(signal)
      return new Promise<Response>((_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))
    }))
    const { unmount } = render(<App />)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    await act(async () => { await Promise.resolve() })
    expect(signals.every((signal) => signal.aborted)).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    vi.useRealTimers()
  })

  it('marks the current refresh unavailable when the request timeout expires', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('timed out', 'AbortError')))
    })))
    render(<App />)
    await act(async () => {
      vi.advanceTimersByTime(3000)
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(screen.getAllByText('无法连接')).toHaveLength(3)
    vi.useRealTimers()
  })

  it('remains correct when mounted under StrictMode', async () => {
    healthyResponses()
    render(<StrictMode><App /></StrictMode>)
    expect(await screen.findByText('0.0.1')).toBeVisible()
    expect(screen.getAllByText('正常')).toHaveLength(3)
  })
})
