import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import AuthPanel from './AuthPanel'

type CheckState = 'loading' | 'up' | 'down' | 'unavailable'
type VersionResponse = { productName: string; version: string; commit: string }
type HealthResponse = { status: 'UP' | 'DOWN'; database?: 'UP' | 'DOWN'; migration?: 'APPLIED' | 'NOT_APPLIED' | 'UNKNOWN' }
type HealthCardProps = { label: string; state: CheckState; detail?: string }
type HealthCheck = { response: Response; body: HealthResponse }

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isVersionResponse = (value: unknown): value is VersionResponse => isRecord(value) && typeof value.productName === 'string' && value.productName.length > 0 && typeof value.version === 'string' && value.version.length > 0 && typeof value.commit === 'string' && value.commit.length > 0
const isHealthResponse = (value: unknown, requireReadinessDetails: boolean): value is HealthResponse => {
  if (!isRecord(value) || (value.status !== 'UP' && value.status !== 'DOWN')) return false
  if (!requireReadinessDetails) return true
  return (value.database === 'UP' || value.database === 'DOWN') && (value.migration === 'APPLIED' || value.migration === 'NOT_APPLIED' || value.migration === 'UNKNOWN')
}

const parseJson = async (response: Response): Promise<unknown> => {
  try {
    return await response.json()
  } catch {
    throw new Error('Invalid JSON response')
  }
}

const requestVersion = async (signal: AbortSignal): Promise<VersionResponse> => {
  const response = await fetch('/api/v1/version', { signal })
  const body = await parseJson(response)
  if (!response.ok || !isVersionResponse(body)) throw new Error(`Invalid version response (${response.status})`)
  return body
}

const requestHealth = async (path: string, signal: AbortSignal, requireReadinessDetails: boolean): Promise<HealthCheck> => {
  const response = await fetch(path, { signal })
  const body = await parseJson(response)
  if (!isHealthResponse(body, requireReadinessDetails)) throw new Error(`Invalid health response (${response.status})`)
  return { response, body }
}

const statusFromHealth = (response: Response, body: HealthResponse, requireReadinessDetails: boolean): CheckState => {
  if (!response.ok || body.status !== 'UP') return 'down'
  if (requireReadinessDetails && (body.database !== 'UP' || body.migration !== 'APPLIED')) return 'down'
  return 'up'
}

function HealthCard({ label, state, detail }: HealthCardProps) {
  const stateLabel = { loading: '检查中', up: '正常', down: '不可用', unavailable: '无法连接' }[state]
  return <article className="status-card"><div className="status-card-header"><span>{label}</span><span className={`status-dot status-${state}`} aria-hidden="true" /></div><strong>{stateLabel}</strong>{detail && <p>{detail}</p>}</article>
}

function App() {
  const [version, setVersion] = useState<VersionResponse | null>(null)
  const [versionState, setVersionState] = useState<CheckState>('loading')
  const [liveState, setLiveState] = useState<CheckState>('loading')
  const [readyState, setReadyState] = useState<CheckState>('loading')
  const [readyDetail, setReadyDetail] = useState('')
  const [lastChecked, setLastChecked] = useState<string | null>(null)
  const mountedRef = useRef(false)
  const requestRoundRef = useRef(0)
  const controllerRef = useRef<AbortController | null>(null)
  const timeoutRef = useRef<number | null>(null)

  const refresh = useCallback(async () => {
    const requestRound = requestRoundRef.current + 1
    requestRoundRef.current = requestRound
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current)
      timeoutRef.current = null
    }
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    const isCurrent = () => mountedRef.current && requestRoundRef.current === requestRound

    setVersion(null)
    setVersionState('loading')
    setLiveState('loading')
    setReadyState('loading')
    setReadyDetail('')

    timeoutRef.current = window.setTimeout(() => controller.abort(), 3000)
    const versionRequest = requestVersion(controller.signal)
      .then((response) => { if (isCurrent()) { setVersion(response); setVersionState('up') } })
      .catch(() => { if (isCurrent()) { setVersion(null); setVersionState('unavailable') } })
    const liveRequest = requestHealth('/health/live', controller.signal, false)
      .then(({ response, body }) => { if (isCurrent()) setLiveState(statusFromHealth(response, body, false)) })
      .catch(() => { if (isCurrent()) setLiveState('unavailable') })
    const readyRequest = requestHealth('/health/ready', controller.signal, true)
      .then(({ response, body }) => {
        if (!isCurrent()) return
        setReadyDetail(`数据库 ${body.database} · 迁移 ${body.migration}`)
        setReadyState(statusFromHealth(response, body, true))
      })
      .catch(() => { if (isCurrent()) { setReadyDetail(''); setReadyState('unavailable') } })

    await Promise.allSettled([versionRequest, liveRequest, readyRequest])
    if (isCurrent()) {
      if (timeoutRef.current !== null) {
        window.clearTimeout(timeoutRef.current)
        timeoutRef.current = null
      }
      controllerRef.current = null
      setLastChecked(new Date().toLocaleTimeString())
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    void refresh()
    return () => {
      mountedRef.current = false
      requestRoundRef.current += 1
      controllerRef.current?.abort()
      controllerRef.current = null
      if (timeoutRef.current !== null) {
        window.clearTimeout(timeoutRef.current)
        timeoutRef.current = null
      }
    }
  }, [refresh])

  return <main className="workbench">
    <header className="hero-header"><div><p className="eyebrow">TEST365ALM · ENGINEERING FOUNDATION</p><h1>{version?.productName ?? 'Test365Alm'} 工作台</h1><p className="lede">查看当前应用构建信息与运行状态，数据来自真实后端接口。</p></div><button className="refresh-button" type="button" onClick={() => void refresh()}>刷新状态</button></header>
    <AuthPanel />
    <section className="version-panel" aria-label="构建信息"><div><span className="panel-label">应用版本</span><strong>{version?.version ?? (versionState === 'loading' ? '读取中…' : '不可用')}</strong></div><div><span className="panel-label">构建提交</span><code>{version?.commit ?? (versionState === 'loading' ? '读取中…' : 'unknown')}</code></div></section>
    <section className="status-grid" aria-label="服务状态"><HealthCard label="应用存活" state={liveState} detail="不依赖数据库连接" /><HealthCard label="应用就绪" state={readyState} detail={readyDetail || '数据库与迁移检查'} /><HealthCard label="版本接口" state={versionState} detail="GET /api/v1/version" /></section>
    <footer className="workbench-footer"><span>{lastChecked ? `最近检查：${lastChecked}` : '正在检查服务…'}</span><span>开发环境 · localhost</span></footer>
  </main>
}
export default App
