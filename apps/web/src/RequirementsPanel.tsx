import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { requirementsApi, newIdempotencyKey } from './requirements'
import type { Requirement, RequirementInput, RequirementRevision } from './requirements'
import type { ProjectAccess } from './projectAccess'

type PanelState = 'idle' | 'loading' | 'ready' | 'error'
type RequirementsPanelProps = { projectId: string; access: ProjectAccess | null; resetSignal?: number }
type Context = { projectId: string; resetSignal: number }
type SelectOptions = { preserveDraft?: boolean; keepWrite?: boolean }

const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '需求请求失败，请稍后重试'
const codeFor = (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : ''
const can = (access: ProjectAccess | null, permission: string) => access?.permissions.includes(permission) === true
const isInvalidAccess = (error: unknown) => /^(HTTP_401|HTTP_403|HTTP_404)$/.test(codeFor(error)) || ['UNAUTHENTICATED', 'PROJECT_NOT_FOUND', 'PROJECT_ACCESS_DENIED', 'REQUIREMENT_NOT_FOUND'].includes(codeFor(error))

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
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailReady, setDetailReady] = useState(false)
  const readRoundRef = useRef(0)
  const writeRoundRef = useRef(0)
  const readControllerRef = useRef<AbortController | null>(null)
  const writeControllerRef = useRef<AbortController | null>(null)
  const readTimeoutRef = useRef<number | null>(null)
  const writeTimeoutRef = useRef<number | null>(null)
  const contextRef = useRef<Context>({ projectId, resetSignal })
  const lastResetRef = useRef(resetSignal)
  const createIntentRef = useRef<string | null>(null)
  const saveIntentRef = useRef<{ key: string; requirementId: string; etag: string; input: RequirementInput } | null>(null)
  contextRef.current = { projectId, resetSignal }

  const cancelRead = () => {
    readControllerRef.current?.abort()
    readControllerRef.current = null
    if (readTimeoutRef.current !== null) { window.clearTimeout(readTimeoutRef.current); readTimeoutRef.current = null }
  }
  const cancelWrite = () => {
    writeControllerRef.current?.abort()
    writeControllerRef.current = null
    if (writeTimeoutRef.current !== null) { window.clearTimeout(writeTimeoutRef.current); writeTimeoutRef.current = null }
  }
  const cancelAll = () => { cancelRead(); cancelWrite() }
  const isCurrentContext = (context: Context) => contextRef.current.projectId === context.projectId && contextRef.current.resetSignal === context.resetSignal
  const clearInvalidData = (message: string) => {
    setItems([]); setSelected(null); setRevisions([]); setSelectedRevision(null); setTitle(''); setBody(''); setDraft(null); setConflict(false); setDetailReady(false); setState('error'); setNotice(message)
  }
  const resetData = () => { setState('idle'); setItems([]); setSelected(null); setRevisions([]); setSelectedRevision(null); setTitle(''); setBody(''); setCreateTitle(''); setCreateBody(''); setDraft(null); setNotice(''); setConflict(false); setSaving(false); setDetailLoading(false); setDetailReady(false); createIntentRef.current = null; saveIntentRef.current = null }

  useEffect(() => () => { readRoundRef.current += 1; writeRoundRef.current += 1; cancelAll() }, [])
  useEffect(() => {
    if (lastResetRef.current === resetSignal) return
    lastResetRef.current = resetSignal
    readRoundRef.current += 1; writeRoundRef.current += 1; cancelAll(); resetData()
  }, [resetSignal])

  const load = async (nextProjectId = projectId) => {
    const context = contextRef.current
    const round = ++readRoundRef.current
    writeRoundRef.current += 1
    cancelAll()
    if (!nextProjectId) { resetData(); return }
    const controller = new AbortController()
    readControllerRef.current = controller
    readTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setState('loading'); setNotice(''); setConflict(false); setDetailLoading(false); setDetailReady(false); setItems([]); setSelected(null); setRevisions([]); setSelectedRevision(null); setTitle(''); setBody(''); setDraft(null); createIntentRef.current = null; saveIntentRef.current = null
    try {
      const page = await requirementsApi.list(nextProjectId, { signal: controller.signal })
      if (readRoundRef.current !== round || !isCurrentContext(context)) return
      setItems(page.items); setState('ready'); if (page.items.length === 0) setNotice('当前项目还没有需求')
    } catch (error) {
      if (readRoundRef.current !== round || !isCurrentContext(context)) return
      if (isInvalidAccess(error)) { clearInvalidData('当前项目不可访问，请重新选择项目'); return }
      setItems([]); setState('error'); setNotice((error as { name?: string }).name === 'AbortError' ? '需求请求超时，请稍后重试' : messageFor(error))
    } finally {
      if (readRoundRef.current === round && isCurrentContext(context)) { readControllerRef.current = null; if (readTimeoutRef.current !== null) { window.clearTimeout(readTimeoutRef.current); readTimeoutRef.current = null } }
    }
  }

  useEffect(() => { void load(projectId) }, [projectId])

  const selectRequirement = async (requirement: Requirement, options: SelectOptions = {}): Promise<boolean> => {
    const context = contextRef.current
    const round = ++readRoundRef.current
    if (!options.keepWrite) { writeRoundRef.current += 1; cancelWrite(); setSaving(false) }
    cancelRead()
    const preserveDraft = options.preserveDraft === true
    const preserved = preserveDraft ? (draft ?? { title, body }) : null
    const controller = new AbortController(); readControllerRef.current = controller; readTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setDetailLoading(true); setDetailReady(false); setNotice(''); if (!preserveDraft) { setConflict(false); setDraft(null) }
    setSelected(requirement); setRevisions([]); setSelectedRevision(null); if (!preserveDraft) { setTitle(requirement.title); setBody(requirement.body) }
    try {
      const [detail, history] = await Promise.all([requirementsApi.get(context.projectId, requirement.id, { signal: controller.signal }), requirementsApi.revisions(context.projectId, requirement.id, { signal: controller.signal })])
      if (readRoundRef.current !== round || !isCurrentContext(context)) return false
      setSelected(detail); setItems((current) => current.map((item) => item.id === detail.id ? detail : item)); setRevisions(history); setDetailReady(true)
      if (preserveDraft && preserved) { setTitle(preserved.title); setBody(preserved.body) } else { setTitle(detail.title); setBody(detail.body) }
      if (preserveDraft) setNotice('已加载最新版本，草稿仍保留，请确认后再保存。')
      return true
    } catch (error) {
      if (readRoundRef.current !== round || !isCurrentContext(context)) return false
      if (isInvalidAccess(error)) { clearInvalidData('当前需求不可访问，请重新选择项目'); return false }
      setNotice((error as { name?: string }).name === 'AbortError' ? '需求请求超时，请稍后重试' : messageFor(error)); setState('ready'); setDetailReady(false)
      return false
    } finally {
      if (readRoundRef.current === round && isCurrentContext(context)) { readControllerRef.current = null; setDetailLoading(false); if (readTimeoutRef.current !== null) { window.clearTimeout(readTimeoutRef.current); readTimeoutRef.current = null } }
    }
  }

  const create = async (event: FormEvent) => {
    event.preventDefault()
    if (!projectId || !can(access, 'requirement:create') || !createTitle.trim() || saving) return
    const context = contextRef.current; const operation = ++writeRoundRef.current
    cancelRead(); cancelWrite()
    const input = { title: createTitle.trim(), body: createBody }; const key = createIntentRef.current ?? (createIntentRef.current = newIdempotencyKey()); const controller = new AbortController()
    writeControllerRef.current = controller; writeTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000); setSaving(true); setNotice('')
    try {
      const created = await requirementsApi.create(context.projectId, input, { signal: controller.signal, idempotencyKey: key })
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      createIntentRef.current = null; setCreateTitle(''); setCreateBody(''); setItems((current) => [...current, created]); setSelected(created); setDraft(null); setTitle(created.title); setBody(created.body); const detailLoaded = await selectRequirement(created, { keepWrite: true }); if (detailLoaded && writeRoundRef.current === operation && isCurrentContext(context)) setNotice('需求已创建')
    } catch (error) {
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      if (isInvalidAccess(error)) { clearInvalidData('当前项目不可访问，请重新选择项目'); return }
      setNotice((error as { name?: string }).name === 'AbortError' ? '需求请求超时，请稍后重试' : messageFor(error))
    } finally {
      if (writeRoundRef.current === operation && isCurrentContext(context)) { setSaving(false); writeControllerRef.current = null; if (writeTimeoutRef.current !== null) { window.clearTimeout(writeTimeoutRef.current); writeTimeoutRef.current = null } }
    }
  }

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !detailReady || !can(access, 'requirement:update') || saving) return
    const context = contextRef.current; const requirementAtStart = selected; const input = { title: title.trim(), body }; const operation = ++writeRoundRef.current
    const prior = saveIntentRef.current
    const key = prior && prior.requirementId === requirementAtStart.id && prior.etag === (requirementAtStart.etag ?? '') && prior.input.title === input.title && prior.input.body === input.body ? prior.key : newIdempotencyKey()
    saveIntentRef.current = { key, requirementId: requirementAtStart.id, etag: requirementAtStart.etag ?? '', input }
    cancelRead(); cancelWrite(); const controller = new AbortController(); writeControllerRef.current = controller; writeTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000); setSaving(true); setNotice('')
    try {
      const updated = await requirementsApi.update(context.projectId, requirementAtStart, input, { signal: controller.signal, idempotencyKey: key })
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      saveIntentRef.current = null; setItems((current) => current.map((item) => item.id === updated.id ? updated : item)); setSelected(updated); setDraft(null); setConflict(false); const detailLoaded = await selectRequirement(updated, { keepWrite: true }); if (detailLoaded && writeRoundRef.current === operation && isCurrentContext(context)) setNotice('需求已保存')
    } catch (error) {
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      if (isInvalidAccess(error)) { clearInvalidData('当前需求不可访问，请重新选择项目'); return }
      if (codeFor(error) === 'HTTP_412' || codeFor(error) === 'STALE_VERSION' || codeFor(error) === 'PRECONDITION_FAILED') { setDraft(input); setConflict(true); setNotice('需求已被其他人更新。草稿已保留，请查看最新版本后再决定如何修改。') } else setNotice((error as { name?: string }).name === 'AbortError' ? '保存请求超时，请稍后重试' : messageFor(error))
    } finally {
      if (writeRoundRef.current === operation && isCurrentContext(context)) { setSaving(false); writeControllerRef.current = null; if (writeTimeoutRef.current !== null) { window.clearTimeout(writeTimeoutRef.current); writeTimeoutRef.current = null } }
    }
  }

  const writable = Boolean(can(access, 'requirement:update'))
  const creator = Boolean(can(access, 'requirement:create'))
  if (!projectId) return <section className="requirements-panel" aria-labelledby="requirements-heading"><div className="access-panel-header"><div><span className="panel-label">需求管理</span><h2 id="requirements-heading">需求</h2></div></div><p className="access-empty" role="status">请选择一个项目以查看需求。</p></section>
  return <section className="requirements-panel" aria-labelledby="requirements-heading">
    <div className="access-panel-header"><div><span className="panel-label">M07 · 需求第一条闭环</span><h2 id="requirements-heading">需求</h2><p>当前项目的需求、修订和历史正文。</p></div><button type="button" className="secondary-button" onClick={() => void load()} disabled={state === 'loading' || saving}>{state === 'loading' ? '加载中…' : '刷新需求'}</button></div>
    {state === 'error' && <p className="access-error" role="alert">{notice}</p>}
    {notice && state !== 'error' && <p className={conflict ? 'requirements-conflict' : 'access-notice'} role={conflict ? 'alert' : 'status'}>{notice}{conflict && selected && <button type="button" className="secondary-button" onClick={() => void selectRequirement(selected, { preserveDraft: true })}>查看最新版本</button>}</p>}
    {creator && <form className="requirement-create-form" onSubmit={(event) => void create(event)}><strong>新建需求</strong><label>标题<input aria-label="需求标题" value={createTitle} onChange={(event) => { createIntentRef.current = null; setCreateTitle(event.target.value) }} maxLength={500} required /></label><label>正文<textarea aria-label="需求正文" value={createBody} onChange={(event) => { createIntentRef.current = null; setCreateBody(event.target.value) }} rows={3} /></label><button type="submit" className="primary-button" disabled={saving}>{saving ? '保存中…' : '创建需求'}</button></form>}
    {state !== 'loading' && state !== 'error' && items.length === 0 && <p className="access-empty" role="status">当前项目还没有需求。</p>}
    {items.length > 0 && <div className="requirements-layout">
      <div className="requirement-list" aria-label="需求列表">
        <div className="member-list-heading"><strong>需求列表</strong><span>{items.length} 条</span></div>
        {items.map((item) => <button type="button" className={`requirement-row${selected?.id === item.id ? ' is-selected' : ''}`} key={item.id} onClick={() => void selectRequirement(item)}><span><strong>{item.displayNumber}</strong><span>{item.title}</span></span><small>修订 {item.revisionNumber}</small></button>)}
      </div>
      {selected && <div className="requirement-detail" aria-label="需求详情">
        <div className="requirement-detail-heading"><div><span className="panel-label">需求详情 · {selected.displayNumber}</span><h3>{selected.title}</h3><p>修订 {selected.revisionNumber} · 版本 {selected.rowVersion} · 固定优先级 {selected.priority}</p></div>{draft && <span className="requirements-draft-badge">草稿已保留</span>}</div>
        <form onSubmit={(event) => void save(event)}>
          {writable ? <>
            <label>标题<input aria-label="编辑需求标题" value={title} onChange={(event) => { saveIntentRef.current = null; setTitle(event.target.value) }} maxLength={500} required disabled={detailLoading || !detailReady || saving} /></label>
            <label>正文<textarea aria-label="编辑需求正文" value={body} onChange={(event) => { saveIntentRef.current = null; setBody(event.target.value) }} rows={6} disabled={detailLoading || !detailReady || saving} /></label>
            <button className="primary-button" type="submit" disabled={saving || detailLoading || !detailReady}>{saving ? '保存中…' : '保存需求'}</button>
          </> : <><p className="requirement-body">{selected.body || '（无正文）'}</p><p className="access-empty">只读用户可以查看需求和历史，但不能创建或保存。</p></>}
        </form>
        <section className="requirement-history" aria-label="修订历史">
          <div className="member-list-heading"><strong>不可变修订历史</strong><span>{revisions.length} 个修订</span></div>
          {revisions.length === 0 ? <p className="access-empty">暂无修订历史。</p> : <div className="revision-list">{revisions.map((revision) => <button type="button" className="revision-row" key={revision.id} onClick={() => setSelectedRevision(revision)}><span>修订 {revision.revisionNumber}</span><small>{revision.createdBy} · {revision.createdAt}</small></button>)}</div>}
          {selectedRevision && <article className="revision-readonly"><strong>修订 {selectedRevision.revisionNumber}（只读）</strong><h4>{selectedRevision.title}</h4><p>{selectedRevision.body || '（无正文）'}</p><small>{selectedRevision.createdBy} · {selectedRevision.createdAt}</small></article>}
        </section>
      </div>}
    </div>}
  </section>
}
