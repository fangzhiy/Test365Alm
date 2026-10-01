import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { requirementsApi } from './requirements'
import type { Requirement, RequirementInput, RequirementRevision } from './requirements'
import type { ProjectAccess } from './projectAccess'

type PanelState = 'idle' | 'loading' | 'ready' | 'error'
type RequirementsPanelProps = { projectId: string; access: ProjectAccess | null; resetSignal?: number }

const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '需求请求失败，请稍后重试'
const codeFor = (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : ''
const can = (access: ProjectAccess | null, permission: string) => access?.permissions.includes(permission) === true

export default function RequirementsPanel({ projectId, access, resetSignal = 0 }: RequirementsPanelProps) {
  const [state, setState] = useState<PanelState>('idle')
  const [items, setItems] = useState<Requirement[]>([])
  const [selected, setSelected] = useState<Requirement | null>(null)
  const [revisions, setRevisions] = useState<RequirementRevision[]>([])
  const [selectedRevision, setSelectedRevision] = useState<RequirementRevision | null>(null)
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [createTitle, setCreateTitle] = useState('')
  const [createBody, setCreateBody] = useState('')
  const [draft, setDraft] = useState<RequirementInput | null>(null)
  const [notice, setNotice] = useState('')
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const roundRef = useRef(0)
  const controllerRef = useRef<AbortController | null>(null)
  const timeoutRef = useRef<number | null>(null)
  const lastResetRef = useRef(resetSignal)

  const cancel = () => { controllerRef.current?.abort(); controllerRef.current = null; if (timeoutRef.current !== null) { window.clearTimeout(timeoutRef.current); timeoutRef.current = null } }
  const resetData = () => { setState('idle'); setItems([]); setSelected(null); setRevisions([]); setSelectedRevision(null); setTitle(''); setBody(''); setCreateTitle(''); setCreateBody(''); setDraft(null); setNotice(''); setConflict(false); setSaving(false) }

  useEffect(() => () => { roundRef.current += 1; cancel() }, [])
  useEffect(() => {
    if (lastResetRef.current === resetSignal) return
    lastResetRef.current = resetSignal
    roundRef.current += 1
    cancel()
    resetData()
  }, [resetSignal])

  const load = async (nextProjectId = projectId) => {
    roundRef.current += 1
    const round = roundRef.current
    cancel()
    if (!nextProjectId) { resetData(); return }
    const controller = new AbortController()
    controllerRef.current = controller
    timeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setState('loading'); setNotice(''); setConflict(false); setItems([]); setSelected(null); setRevisions([]); setSelectedRevision(null); setTitle(''); setBody(''); setDraft(null)
    try {
      const page = await requirementsApi.list(nextProjectId, { signal: controller.signal })
      if (roundRef.current !== round) return
      setItems(page.items)
      setState('ready')
      if (page.items.length === 0) setNotice('当前项目还没有需求')
    } catch (error) {
      if (roundRef.current !== round) return
      if ((error as { name?: string }).name === 'AbortError') { setState('error'); setNotice('需求请求超时，请稍后重试'); return }
      setItems([]); setState('error'); setNotice(messageFor(error))
    } finally { if (roundRef.current === round) { controllerRef.current = null; if (timeoutRef.current !== null) { window.clearTimeout(timeoutRef.current); timeoutRef.current = null } } }
  }

  useEffect(() => { void load(projectId) }, [projectId])

  const selectRequirement = async (requirement: Requirement) => {
    const round = ++roundRef.current
    cancel()
    const controller = new AbortController(); controllerRef.current = controller; timeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setNotice(''); setConflict(false); setSelected(requirement); setRevisions([]); setSelectedRevision(null); setTitle(requirement.title); setBody(requirement.body); setDraft(null)
    try {
      const [detail, history] = await Promise.all([requirementsApi.get(projectId, requirement.id, { signal: controller.signal }), requirementsApi.revisions(projectId, requirement.id, { signal: controller.signal })])
      if (roundRef.current !== round) return
      setSelected(detail); setItems((current) => current.map((item) => item.id === detail.id ? detail : item)); setTitle(detail.title); setBody(detail.body); setRevisions(history)
    } catch (error) { if (roundRef.current === round && (error as { name?: string }).name !== 'AbortError') setNotice(messageFor(error)); else if (roundRef.current === round && (error as { name?: string }).name === 'AbortError') { setState('ready'); setNotice('需求请求超时，请稍后重试') } } finally { if (roundRef.current === round) { controllerRef.current = null; if (timeoutRef.current !== null) { window.clearTimeout(timeoutRef.current); timeoutRef.current = null } } }
  }

  const create = async (event: FormEvent) => {
    event.preventDefault()
    if (!projectId || !can(access, 'requirement:create') || !createTitle.trim() || saving) return
    const input = { title: createTitle.trim(), body: createBody }; if (!input.title) return; setSaving(true); setNotice('');
    try { const created = await requirementsApi.create(projectId, input); setCreateTitle(''); setCreateBody(''); setItems((current) => [...current, created]); setSelected(created); setDraft(null); setTitle(created.title); setBody(created.body); await selectRequirement(created); setNotice('需求已创建') }
    catch (error) { setNotice(messageFor(error)) } finally { setSaving(false) }
  }

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !can(access, 'requirement:update') || saving) return
    const input = { title: title.trim(), body }; setSaving(true); setNotice('');
    try { const updated = await requirementsApi.update(projectId, selected, input); setItems((current) => current.map((item) => item.id === updated.id ? updated : item)); setSelected(updated); setDraft(null); setConflict(false); await selectRequirement(updated); setNotice('需求已保存') }
    catch (error) { if (codeFor(error) === 'HTTP_412' || codeFor(error) === 'STALE_VERSION' || codeFor(error) === 'PRECONDITION_FAILED') { setDraft(input); setConflict(true); setNotice('需求已被其他人更新。草稿已保留，请查看最新版本后再决定如何修改。') } else setNotice(messageFor(error)) }
    finally { setSaving(false) }
  }

  const writable = Boolean(can(access, 'requirement:update'))
  const creator = Boolean(can(access, 'requirement:create'))
  if (!projectId) return <section className="requirements-panel" aria-labelledby="requirements-heading"><div className="access-panel-header"><div><span className="panel-label">需求管理</span><h2 id="requirements-heading">需求</h2></div></div><p className="access-empty" role="status">请选择一个项目以查看需求。</p></section>
  return <section className="requirements-panel" aria-labelledby="requirements-heading">
    <div className="access-panel-header"><div><span className="panel-label">M07 · 需求第一条闭环</span><h2 id="requirements-heading">需求</h2><p>当前项目的需求、修订和历史正文。</p></div><button type="button" className="secondary-button" onClick={() => void load()} disabled={state === 'loading' || saving}>{state === 'loading' ? '加载中…' : '刷新需求'}</button></div>
    {state === 'error' && <p className="access-error" role="alert">{notice}</p>}
    {notice && state !== 'error' && <p className={conflict ? 'requirements-conflict' : 'access-notice'} role={conflict ? 'alert' : 'status'}>{notice}</p>}
    {creator && <form className="requirement-create-form" onSubmit={(event) => void create(event)}><strong>新建需求</strong><label>标题<input aria-label="需求标题" value={createTitle} onChange={(event) => setCreateTitle(event.target.value)} maxLength={500} required /></label><label>正文<textarea aria-label="需求正文" value={createBody} onChange={(event) => setCreateBody(event.target.value)} rows={3} /></label><button type="submit" className="primary-button" disabled={saving}>{saving ? '保存中…' : '创建需求'}</button></form>}
    {state !== 'loading' && items.length === 0 && <p className="access-empty" role="status">当前项目还没有需求。</p>}
    {items.length > 0 && <div className="requirements-layout"><div className="requirement-list" aria-label="需求列表"><div className="member-list-heading"><strong>需求列表</strong><span>{items.length} 条</span></div>{items.map((item) => <button type="button" className={`requirement-row${selected?.id === item.id ? ' is-selected' : ''}`} key={item.id} onClick={() => void selectRequirement(item)}><span><strong>{item.displayNumber}</strong><span>{item.title}</span></span><small>修订 {item.revisionNumber}</small></button>)}</div>
      {selected && <div className="requirement-detail" aria-label="需求详情"><div className="requirement-detail-heading"><div><span className="panel-label">需求详情 · {selected.displayNumber}</span><h3>{selected.title}</h3><p>修订 {selected.revisionNumber} · 版本 {selected.rowVersion} · 固定优先级 {selected.priority}</p></div>{draft && <span className="requirements-draft-badge">草稿已保留</span>}</div><form onSubmit={(event) => void save(event)}>{writable ? <><label>标题<input aria-label="编辑需求标题" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={500} required /></label><label>正文<textarea aria-label="编辑需求正文" value={body} onChange={(event) => setBody(event.target.value)} rows={6} /></label><button className="primary-button" type="submit" disabled={saving}>{saving ? '保存中…' : '保存需求'}</button></> : <><p className="requirement-body">{selected.body || '（无正文）'}</p><p className="access-empty">只读用户可以查看需求和历史，但不能创建或保存。</p></>}</form><section className="requirement-history" aria-label="修订历史"><div className="member-list-heading"><strong>不可变修订历史</strong><span>{revisions.length} 个修订</span></div>{revisions.length === 0 ? <p className="access-empty">暂无修订历史。</p> : <div className="revision-list">{revisions.map((revision) => <button type="button" className="revision-row" key={revision.id} onClick={() => setSelectedRevision(revision)}><span>修订 {revision.revisionNumber}</span><small>{revision.createdBy} · {revision.createdAt}</small></button>)}</div>}{selectedRevision && <article className="revision-readonly"><strong>修订 {selectedRevision.revisionNumber}（只读）</strong><h4>{selectedRevision.title}</h4><p>{selectedRevision.body || '（无正文）'}</p><small>{selectedRevision.createdBy} · {selectedRevision.createdAt}</small></article>}</section></div>}
    </div>}
  </section>
}
