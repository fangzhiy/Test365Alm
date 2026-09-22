import { useCallback, useEffect, useState } from 'react'
import './App.css'

type CheckState = 'loading' | 'up' | 'down' | 'unavailable'
type VersionResponse = { productName: string; version: string; commit: string }
type HealthResponse = { status: 'UP' | 'DOWN'; database?: string; migration?: string }
type HealthCardProps = { label: string; state: CheckState; detail?: string }

const requestJson = async <T,>(path: string, signal: AbortSignal): Promise<T> => {
  const response = await fetch(path, { signal })
  const body = (await response.json().catch(() => ({}))) as T
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return body
}
const statusFromHealth = (response: HealthResponse): CheckState => response.status === 'UP' ? 'up' : 'down'

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
  const refresh = useCallback(async () => {
    setVersion(null); setVersionState('loading'); setLiveState('loading'); setReadyState('loading'); setReadyDetail('')
    const controller = new AbortController(); const timeout = window.setTimeout(() => controller.abort(), 3000)
    const versionRequest = requestJson<VersionResponse>('/api/v1/version', controller.signal).then((response) => { setVersion(response); setVersionState('up') }).catch(() => setVersionState('unavailable'))
    const liveRequest = requestJson<HealthResponse>('/health/live', controller.signal).then((response) => setLiveState(statusFromHealth(response))).catch(() => setLiveState('unavailable'))
    const readyRequest = fetch('/health/ready', { signal: controller.signal }).then(async (response) => { const body = (await response.json().catch(() => ({}))) as HealthResponse; setReadyDetail(body.database ? `数据库 ${body.database} · 迁移 ${body.migration ?? '未知'}` : ''); setReadyState(response.ok ? statusFromHealth(body) : 'down') }).catch(() => setReadyState('unavailable'))
    await Promise.all([versionRequest, liveRequest, readyRequest]); window.clearTimeout(timeout); setLastChecked(new Date().toLocaleTimeString())
  }, [])
  useEffect(() => { void refresh() }, [refresh])
  return <main className="workbench">
    <header className="hero-header"><div><p className="eyebrow">TEST365ALM · ENGINEERING FOUNDATION</p><h1>{version?.productName ?? 'Test365Alm'} 工作台</h1><p className="lede">查看当前应用构建信息与运行状态，数据来自真实后端接口。</p></div><button className="refresh-button" type="button" onClick={() => void refresh()}>刷新状态</button></header>
    <section className="version-panel" aria-label="构建信息"><div><span className="panel-label">应用版本</span><strong>{version?.version ?? (versionState === 'loading' ? '读取中…' : '不可用')}</strong></div><div><span className="panel-label">构建提交</span><code>{version?.commit ?? (versionState === 'loading' ? '读取中…' : 'unknown')}</code></div></section>
    <section className="status-grid" aria-label="服务状态"><HealthCard label="应用存活" state={liveState} detail="不依赖数据库连接" /><HealthCard label="应用就绪" state={readyState} detail={readyDetail || '数据库与迁移检查'} /><HealthCard label="版本接口" state={versionState} detail="GET /api/v1/version" /></section>
    <footer className="workbench-footer"><span>{lastChecked ? `最近检查：${lastChecked}` : '正在检查服务…'}</span><span>开发环境 · localhost</span></footer>
  </main>
}
export default App
