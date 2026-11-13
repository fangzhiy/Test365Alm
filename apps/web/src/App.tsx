import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import { ConfigProvider, Breadcrumb, Tag } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { AppstoreOutlined, ExperimentOutlined, ReadOutlined, SafetyCertificateOutlined, SettingOutlined } from '@ant-design/icons'
import ProjectOverview from './ProjectOverview'
import './Workspace.css'
import AuthPanel from './AuthPanel'
import ProjectAccessPanel from './ProjectAccessPanel'
import RequirementsPanel from './RequirementsPanel'
import TestCasesPanel from './TestCasesPanel'
import TestExecutionPanel from './TestExecutionPanel'
import FieldMetadataPanel from './FieldMetadataPanel'
import DefectsPanel from './DefectsPanel'
import ReleasesPanel from './ReleasesPanel'
import type { ProjectAccess } from './projectAccess'

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

const modules = [
  { group: '工作台', id: 'overview', label: '项目概览', icon: <AppstoreOutlined /> },
  { group: '测试管理', id: 'plan', label: '测试计划', icon: <ReadOutlined /> },
  { group: '测试管理', id: 'lab', label: '测试实验室', icon: <ExperimentOutlined /> },
  { group: '质量与合规', id: 'defects', label: '偏差管理', icon: <SafetyCertificateOutlined /> },
  { group: '质量与合规', id: 'requirements', label: '需求管理', icon: <ReadOutlined /> },
  { group: '质量与合规', id: 'releases', label: '发布与质量快照', icon: <SafetyCertificateOutlined /> },
  { group: '管理', id: 'fields', label: '业务字段', icon: <SettingOutlined /> },
  { group: '管理', id: 'settings', label: '项目与成员', icon: <SettingOutlined /> },
  { group: '管理', id: 'system', label: '系统状态', icon: <SettingOutlined /> },
]
const readModule = () => modules.find(m => m.id === window.location.hash.slice(1))?.id ?? 'overview'

function App() {
  const [module, setModule] = useState(readModule)
  useEffect(() => {
    const changed = () => setModule(readModule())
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  const navigate = (id: string) => { window.history.pushState(null, '', `#${id}`); setModule(id) }

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
  const [sessionGeneration, setSessionGeneration] = useState(0)
  const [projectName, setProjectName] = useState('')
  const [selectedProjectId, setSelectedProjectId] = useState('')
  const [selectedProjectAccess, setSelectedProjectAccess] = useState<ProjectAccess | null>(null)
  const handleProjectSelection = useCallback(({ projectId, access, projectName }: { projectId: string; access: ProjectAccess | null; projectName?: string }) => {
    setProjectName(projectName ?? '')
    setSelectedProjectId(projectId)
    setSelectedProjectAccess(access)
  }, [])

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

  const scope = { projectId: selectedProjectId, access: selectedProjectAccess, resetSignal: sessionGeneration }
  const current = modules.find(m => m.id === module)!
  return <ConfigProvider locale={zhCN} theme={{token:{colorPrimary:'#1677ff',borderRadius:8,fontFamily:'"Segoe UI", "Microsoft YaHei", sans-serif'}}}>
    <div className="app-shell">
      <a className="skip-link" href="#module-content">跳到主要内容</a>
      <aside className="app-sidebar">
        <div className="app-brand"><span className="brand-mark">T</span><div>Test365<span>质量管理工作台</span></div></div>
        <nav aria-label="功能导航">{['工作台','测试管理','质量与合规','管理'].map(group => <div className="nav-group" key={group}>
          <p>{group}</p>{modules.filter(m=>m.group===group).map(m=><button key={m.id} type="button" aria-current={module===m.id?'page':undefined} onClick={()=>navigate(m.id)}><span aria-hidden="true">{m.icon}</span><span>{m.label}</span></button>)}
        </div>)}</nav>
        <div className="sidebar-foot">不可变历史 · 可追溯执行</div>
      </aside>
      <div className="app-main">
        <header className="app-topbar"><Breadcrumb items={[{title:'工作空间'},{title:current.label}]} /><AuthPanel onLogout={() => setSessionGeneration(g=>g+1)} /></header>
        <div className="project-switcher"><ProjectAccessPanel compact={module!=='settings'} resetSignal={sessionGeneration} onSelectionChange={handleProjectSelection} /></div>
        <main id="module-content" className="module-content" tabIndex={-1}>
          <div className="module-heading"><div><p>{current.group}</p><h1>{current.label}</h1></div><Tag color="blue">{projectName.includes('SYNTHETIC') ? 'SYNTHETIC · 合成演示' : 'Test365Alm'}</Tag></div>
          {module==='overview' && <ProjectOverview key={`${selectedProjectId}:${sessionGeneration}:${JSON.stringify(selectedProjectAccess)}`} {...scope} onDenied={()=>setSessionGeneration(g=>g+1)} onNavigate={navigate} />}
          {module==='fields' && <FieldMetadataPanel {...scope} />}
          {module==='requirements' && <RequirementsPanel {...scope} />}
          {module==='plan' && <TestCasesPanel {...scope} />}
          {module==='lab' && <TestExecutionPanel {...scope} />}
          {module==='defects' && <DefectsPanel {...scope} />}
          {module==='releases' && <ReleasesPanel {...scope} />}
          {module==='settings' && <p className="scope-note">项目配置与成员权限位于上方。对象审计从用例、运行、偏差的“历史”入口查看；报告从执行与偏差页面进入。</p>}
          {module==='system' && <>
            <button className="refresh-button" type="button" onClick={()=>void refresh()}>刷新状态</button>
            <section className="version-panel" aria-label="构建信息"><div><span className="panel-label">应用版本</span><strong>{version?.version ?? (versionState === 'loading' ? '读取中…' : '不可用')}</strong></div><div><span className="panel-label">构建提交</span><code>{version?.commit ?? (versionState === 'loading' ? '读取中…' : 'unknown')}</code></div></section>
            <section className="status-grid" aria-label="服务状态"><HealthCard label="应用存活" state={liveState} detail="不依赖数据库连接" /><HealthCard label="应用就绪" state={readyState} detail={readyDetail || '数据库与迁移检查'} /><HealthCard label="版本接口" state={versionState} detail="GET /api/v1/version" /></section>
            <footer className="workbench-footer">{lastChecked ? `最近检查：${lastChecked}` : '正在检查服务…'}</footer>
          </>}
        </main>
      </div>
    </div>
  </ConfigProvider>
}
export default App
