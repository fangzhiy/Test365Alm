import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { loadReport, reportScopeDenied } from './reportsApi'
import type { Report, ReportTarget } from './reportsApi'
import { executionApi } from './testExecution'
import { ReportBody } from './ReportContent'
import { label } from './reportFormat'
import './reports.css'
export { ReportBody } from './ReportContent'

type Props = { projectId: string; target: ReportTarget; onClose: () => void; onDenied?: () => void }
type SetOption = { id: string; name: string }

function ReportFilters({ kind, draft, setDraft, sets, more, loadMore, loading, apply }: {
  kind: 'SET' | 'DEFECTS'; draft: Record<string, string>; setDraft: (v: Record<string, string>) => void;
  sets: SetOption[]; more: boolean; loadMore: () => void; loading: boolean; apply: () => void
}) {
  return <form onSubmit={e => { e.preventDefault(); apply() }}>
    <label>报告状态<select value={draft.status ?? ''} onChange={e => setDraft({ ...draft, status: e.target.value })}>
      <option value="">全部</option>{(kind === 'SET' ? ['NOT_RUN', 'RUNNING', 'PAUSED', 'PASS', 'FAIL', 'BLOCKED'] : ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'REOPENED']).map(s => <option key={s} value={s}>{label(s)}</option>)}
    </select></label>
    {kind === 'DEFECTS' && <>
      <label>报告严重程度<select value={draft.severity ?? ''} onChange={e => setDraft({ ...draft, severity: e.target.value })}>
        <option value="">全部</option>{['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map(s => <option key={s} value={s}>{label(s)}</option>)}
      </select></label>
      <label>报告标题关键词<input value={draft.q ?? ''} maxLength={200} onChange={e => setDraft({ ...draft, q: e.target.value })} /></label>
      <label>测试集范围<select value={draft.testSetId ?? ''} onChange={e => setDraft({ ...draft, testSetId: e.target.value })}>
        <option value="">项目范围（全部测试集及手工偏差）</option>{sets.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
      </select></label>
      {more && <button type="button" disabled={loading} onClick={loadMore}>更多测试集名称</button>}
    </>}
    <button>应用报告筛选</button>
  </form>
}
function ReportDialog({ projectId, target: initial, onClose, onDenied }: Props) {
  const dialog = useRef<HTMLDivElement>(null), deniedCallback = useRef(onDenied)
  useEffect(() => { deniedCallback.current = onDenied }, [onDenied])
  const [target, setTarget] = useState(initial)
  const [filters, setFilters] = useState<Record<string, string>>({})
  const [draft, setDraft] = useState<Record<string, string>>(initial.kind === 'DEFECTS' ? { testSetId: initial.setId ?? '' } : {})
  const [report, setReport] = useState<Report | null>(null), [error, setError] = useState(''), [loading, setLoading] = useState(true)
  const [denied, setDenied] = useState(false), [sets, setSets] = useState<SetOption[]>([])
  const [cursor, setCursor] = useState<string | null>(null), [setPage, setSetPage] = useState<string | null>(null)
  const [setsLoading, setSetsLoading] = useState(false), [setsError, setSetsError] = useState('')
  const deniedRef = useRef(false)
  const deny = useCallback(() => { deniedRef.current = true; setDenied(true); setReport(null); setSets([]); setError('当前报告范围不可访问，旧内容已清理'); deniedCallback.current?.() }, [])
  useEffect(() => {
    const root = document.getElementById('root'), wasInert = root?.inert ?? false, opener = document.activeElement
    if (root) root.inert = true
    dialog.current?.querySelector('button')?.focus()
    return () => { if (root) root.inert = wasInert; if (opener instanceof HTMLElement && opener.isConnected) opener.focus() }
  }, [])
  useEffect(() => {
    let current = true
    const c = new AbortController()
    setReport(null); setError(''); setLoading(true)
    if (denied) { setLoading(false); return }
    void loadReport(projectId, target, filters, c.signal).then(v => {
      if (current && !deniedRef.current) setReport(v)
    }).catch((e: unknown) => {
      if (!current || deniedRef.current) return
      if (reportScopeDenied(e)) deny()
      else setError((e as Error).message || '报告读取失败，请缩小范围后重新打开')
    }).finally(() => { if (current) setLoading(false) })
    return () => { current = false; c.abort() }
  }, [projectId, target, filters, denied, deny])
  useEffect(() => {
    if (target.kind !== 'DEFECTS' || denied) return
    let current = true
    const c = new AbortController()
    setSetsLoading(true); setSetsError('')
    void executionApi.pageSets(projectId, setPage, 50, { signal: c.signal }).then(page => {
      if (!current || deniedRef.current) return
      setSets(old => setPage ? [...old, ...page.items.filter(i => !old.some(s => s.id === i.id))] : page.items)
      setCursor(page.nextCursor)
    }).catch((e: unknown) => {
      if (!current || deniedRef.current) return
      if (reportScopeDenied(e)) deny()
      else setSetsError('测试集名称读取失败，可返回业务页面后重试')
    }).finally(() => { if (current) setSetsLoading(false) })
    return () => { current = false; c.abort() }
  }, [projectId, target.kind, setPage, denied, deny])
  const navigate = (t: ReportTarget) => {
    setReport(null); setDraft(t.kind === 'DEFECTS' ? { testSetId: t.setId ?? '' } : {}); setFilters({}); setTarget(t)
  }
  return createPortal(<div ref={dialog} role="dialog" aria-modal="true" aria-label="业务报告" className={`report-overlay ${target.kind === 'RUN' ? 'portrait' : 'landscape'}`}
    onKeyDown={e => {
      if (e.key === 'Escape') { e.preventDefault(); onClose() }
      if (e.key === 'Tab') {
        const controls = [...e.currentTarget.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),select:not([disabled]),a[href],summary')].filter(x => x.offsetParent !== null)
        const first = controls[0], last = controls.at(-1)
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus() }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus() }
      }
    }}>
    <div className="report-toolbar no-print"><button onClick={onClose}>返回业务页面</button>
      <button disabled={!report || loading || !!error || denied} onClick={() => window.print()}>打印 / 另存为 PDF</button>
      {!denied && target.kind !== 'RUN' && <ReportFilters kind={target.kind} draft={draft} setDraft={setDraft} sets={sets} more={!!cursor}
        loadMore={() => setSetPage(cursor)} loading={setsLoading} apply={() => { setReport(null); setFilters({ ...draft }) }} />}
      {setsError && <p role="alert">{setsError}</p>}
    </div>
    {loading && <p role="status">正在完整读取报告…</p>}
    {error && <p role="alert">报告未生成：{error}。可返回业务页面并缩小范围，不能打印不完整数据。</p>}
    {report && !loading && !error && !denied && <main className="report-document"><ReportBody report={report} onTarget={navigate} /></main>}
  </div>, document.body)
}
export default function ReportLauncher({ projectId, target, label: title, onDenied }: Omit<Props, 'onClose'> & { label: string }) {
  const [open, setOpen] = useState(false)
  return <><button type="button" onClick={() => setOpen(true)}>{title}</button>
    {open && <ReportDialog key={projectId} projectId={projectId} target={target} onClose={() => setOpen(false)} onDenied={onDenied} />}</>
}
