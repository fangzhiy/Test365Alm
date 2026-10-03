import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { newIdempotencyKey } from './requirements'
import { testsApi } from './tests'
import type { TestCase, TestCaseInput, TestRevision, TestStep } from './tests'
import type { ProjectAccess } from './projectAccess'

type PanelState = 'idle' | 'loading' | 'ready' | 'error'
type Context = { projectId: string; resetSignal: number; accessKey: string }
type SelectOptions = { keepWrite?: boolean; preserveDraft?: boolean }
type TestCasesPanelProps = { projectId: string; access: ProjectAccess | null; resetSignal?: number }

const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '测试用例请求失败，请稍后重试'
const codeFor = (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : ''
const can = (access: ProjectAccess | null, permission: string) => access?.permissions.includes(permission) === true
const isAccessError = (error: unknown, write = false) => {
  const code = codeFor(error)
  if (code === 'UNAUTHENTICATED' || code === 'IDENTITY_DISABLED' || code === 'PROJECT_NOT_FOUND' || code === 'PROJECT_ACCESS_DENIED' || code === 'NOT_FOUND' || code === 'TEST_NOT_FOUND' || code === 'HTTP_401' || code === 'HTTP_404') return true
  return !write && (code === 'FORBIDDEN' || code === 'HTTP_403')
}
const isAbort = (error: unknown) => typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
const accessKeyFor = (access: ProjectAccess | null) => access === null ? 'none' : `${access.tenantId}:${access.principalId}:${access.roles.join('|')}:${access.permissions.join('|')}`
const cloneSteps = (steps: TestStep[]) => steps.map((step) => ({ ...step }))
const newStep = (): TestStep => ({ stepKey: `draft-${newIdempotencyKey()}`, ordinal: 1, action: '', expected: '' })
const normalizeSteps = (steps: TestStep[]) => steps.map((step, index) => ({ ...step, ordinal: index + 1 }))

export default function TestCasesPanel({ projectId, access, resetSignal = 0 }: TestCasesPanelProps) {
  const [state, setState] = useState<PanelState>('idle')
  const [items, setItems] = useState<TestCase[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [selected, setSelected] = useState<TestCase | null>(null)
  const [revisions, setRevisions] = useState<TestRevision[]>([])
  const [selectedRevision, setSelectedRevision] = useState<TestRevision | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [preconditions, setPreconditions] = useState('')
  const [steps, setSteps] = useState<TestStep[]>([])
  const [createTitle, setCreateTitle] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createPreconditions, setCreatePreconditions] = useState('')
  const [createSteps, setCreateSteps] = useState<TestStep[]>([])
  const [search, setSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [notice, setNotice] = useState('')
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailReady, setDetailReady] = useState(false)
  const [scopeInvalid, setScopeInvalid] = useState(false)
  const [writeDenied, setWriteDenied] = useState(false)
  const readRoundRef = useRef(0)
  const writeRoundRef = useRef(0)
  const readControllerRef = useRef<AbortController | null>(null)
  const writeControllerRef = useRef<AbortController | null>(null)
  const readTimeoutRef = useRef<number | null>(null)
  const writeTimeoutRef = useRef<number | null>(null)
  const contextRef = useRef<Context>({ projectId, resetSignal, accessKey: accessKeyFor(access) })
  const lastResetRef = useRef(resetSignal)
  const activeProjectRef = useRef(projectId)
  const createIntentRef = useRef<{ key: string; input: TestCaseInput } | null>(null)
  const saveIntentRef = useRef<{ key: string; testId: string; etag: string; input: TestCaseInput } | null>(null)
  contextRef.current = { projectId, resetSignal, accessKey: accessKeyFor(access) }

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
  const invalidateOperations = () => { readRoundRef.current += 1; writeRoundRef.current += 1; cancelAll() }
  const isCurrentContext = (context: Context) => contextRef.current.projectId === context.projectId && contextRef.current.resetSignal === context.resetSignal && contextRef.current.accessKey === context.accessKey
  const clearInvalidData = (message: string) => {
    invalidateOperations()
    setItems([]); setNextCursor(null); setSelected(null); setRevisions([]); setSelectedRevision(null)
    setTitle(''); setDescription(''); setPreconditions(''); setSteps([])
    setCreateTitle(''); setCreateDescription(''); setCreatePreconditions(''); setCreateSteps([])
    setConflict(false); setSaving(false); setDetailLoading(false); setDetailReady(false); setScopeInvalid(true); setWriteDenied(false); createIntentRef.current = null; saveIntentRef.current = null
    setState('error'); setNotice(message)
  }
  const resetData = () => {
    setState('idle'); setItems([]); setNextCursor(null); setSelected(null); setRevisions([]); setSelectedRevision(null)
    setTitle(''); setDescription(''); setPreconditions(''); setSteps([])
    setCreateTitle(''); setCreateDescription(''); setCreatePreconditions(''); setCreateSteps([])
    setNotice(''); setConflict(false); setSaving(false); setDetailLoading(false); setDetailReady(false); setScopeInvalid(false); setWriteDenied(false); createIntentRef.current = null; saveIntentRef.current = null
  }

  useEffect(() => () => { invalidateOperations() }, [])
  useEffect(() => {
    if (lastResetRef.current === resetSignal) return
    lastResetRef.current = resetSignal
    invalidateOperations(); resetData()
  }, [resetSignal])

  const load = async (cursor: string | null = null, query = search) => {
    const context = contextRef.current
    const round = ++readRoundRef.current
    writeRoundRef.current += 1
    cancelAll()
    if (!context.projectId || !can(access, 'test:read')) { resetData(); return }
    const controller = new AbortController()
    readControllerRef.current = controller
    readTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setState('loading'); setSaving(false); setScopeInvalid(false); setWriteDenied(false); setNotice(''); setConflict(false); setDetailLoading(false); setDetailReady(false); setSelected(null); setRevisions([]); setSelectedRevision(null); setItems(cursor ? items : []); setNextCursor(cursor ? nextCursor : null)
    if (!cursor) { setTitle(''); setDescription(''); setPreconditions(''); setSteps([]); saveIntentRef.current = null }
    try {
      const page = await testsApi.list(context.projectId, query, cursor, { signal: controller.signal })
      if (readRoundRef.current !== round || !isCurrentContext(context)) return
      setItems((current) => cursor ? [...current, ...page.items.filter((item) => !current.some((existing) => existing.id === item.id))] : page.items)
      setNextCursor(page.nextCursor ?? null); setState('ready'); if (!cursor && page.items.length === 0) setNotice('当前项目还没有手工测试用例')
    } catch (error) {
      if (readRoundRef.current !== round || !isCurrentContext(context)) return
      if (isAccessError(error)) { clearInvalidData('当前项目不可访问，请重新选择项目'); return }
      if (!cursor) setItems([])
      setState('error'); setNotice(isAbort(error) ? '测试用例请求超时，请稍后重试' : messageFor(error))
    } finally {
      if (readRoundRef.current === round && isCurrentContext(context)) { readControllerRef.current = null; if (readTimeoutRef.current !== null) { window.clearTimeout(readTimeoutRef.current); readTimeoutRef.current = null } }
    }
  }

  useEffect(() => {
    const changed = activeProjectRef.current !== projectId
    activeProjectRef.current = projectId
    if (changed) {
      invalidateOperations(); resetData()
    }
    if (!projectId || !access || !can(access, 'test:read')) { invalidateOperations(); resetData(); return }
    void load(null, changed ? '' : search)
  }, [projectId, access?.permissions.join('|')])

  const selectTest = async (testCase: TestCase, options: SelectOptions = {}): Promise<boolean> => {
    const context = contextRef.current
    const round = ++readRoundRef.current
    if (!options.keepWrite) { writeRoundRef.current += 1; cancelWrite(); setSaving(false) }
    cancelRead()
    const preserveDraft = options.preserveDraft === true
    const saved = preserveDraft ? { title, description, preconditions, steps: cloneSteps(steps) } : null
    const controller = new AbortController(); readControllerRef.current = controller; readTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000)
    setDetailLoading(true); setDetailReady(false); setSelected(testCase); setRevisions([]); setSelectedRevision(null); setNotice(''); if (!preserveDraft) { setConflict(false); setTitle(testCase.title); setDescription(testCase.description); setPreconditions(testCase.preconditions); setSteps(cloneSteps(testCase.steps)) }
    try {
      const [detail, history] = await Promise.all([testsApi.get(context.projectId, testCase.id, { signal: controller.signal }), testsApi.revisions(context.projectId, testCase.id, { signal: controller.signal })])
      if (readRoundRef.current !== round || !isCurrentContext(context)) return false
      setSelected(detail); setItems((current) => current.map((item) => item.id === detail.id ? detail : item)); setRevisions(history); setDetailReady(true)
      if (saved) { setTitle(saved.title); setDescription(saved.description); setPreconditions(saved.preconditions); setSteps(saved.steps) } else { setTitle(detail.title); setDescription(detail.description); setPreconditions(detail.preconditions); setSteps(cloneSteps(detail.steps)) }
      setState('ready')
      if (preserveDraft) setNotice('已加载最新版本，草稿仍保留，请确认后再保存。')
      return true
    } catch (error) {
      if (readRoundRef.current !== round || !isCurrentContext(context)) return false
      if (isAccessError(error)) { clearInvalidData('当前测试用例不可访问，请重新选择项目'); return false }
      setState('ready'); setNotice(isAbort(error) ? '测试用例详情加载超时，请稍后重试' : messageFor(error)); setDetailReady(false)
      return false
    } finally {
      if (readRoundRef.current === round && isCurrentContext(context)) { readControllerRef.current = null; setDetailLoading(false); if (readTimeoutRef.current !== null) { window.clearTimeout(readTimeoutRef.current); readTimeoutRef.current = null } }
    }
  }

  const createInput = (): TestCaseInput => ({ title: createTitle.trim(), description: createDescription, preconditions: createPreconditions, steps: normalizeSteps(createSteps) })
  const editInput = (): TestCaseInput => ({ title: title.trim(), description, preconditions, steps: normalizeSteps(steps) })

  const create = async (event: FormEvent) => {
    event.preventDefault()
    if (!projectId || !can(access, 'test:create') || !createTitle.trim() || saving) return
    const context = contextRef.current; const operation = ++writeRoundRef.current; readRoundRef.current += 1; cancelRead(); cancelWrite()
    const input = createInput(); const existingIntent = createIntentRef.current
    const key = existingIntent && JSON.stringify(existingIntent.input) === JSON.stringify(input) ? existingIntent.key : newIdempotencyKey()
    createIntentRef.current = { key, input }
    const controller = new AbortController()
    writeControllerRef.current = controller; writeTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000); setSaving(true); setNotice('')
    try {
      const created = await testsApi.create(context.projectId, input, { signal: controller.signal, idempotencyKey: key })
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      createIntentRef.current = null; setCreateTitle(''); setCreateDescription(''); setCreatePreconditions(''); setCreateSteps([]); setItems((current) => current.some((item) => item.id === created.id) ? current.map((item) => item.id === created.id ? created : item) : [...current, created]); setSelected(created); setState('ready')
      const loaded = await selectTest(created, { keepWrite: true })
      if (loaded && writeRoundRef.current === operation && isCurrentContext(context)) setNotice('测试用例已创建')
    } catch (error) {
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      if (isAccessError(error, true)) { clearInvalidData('当前项目不可访问，请重新选择项目'); return }
      if (codeFor(error) === 'FORBIDDEN' || codeFor(error) === 'HTTP_403') { setWriteDenied(true); setState('ready'); setNotice('当前主体没有修改该项目测试用例的权限。'); return }
      setState('ready'); setNotice(isAbort(error) ? '创建请求超时，请保留原意图后重试' : messageFor(error))
    } finally {
      if (writeRoundRef.current === operation && isCurrentContext(context)) { setSaving(false); writeControllerRef.current = null; if (writeTimeoutRef.current !== null) { window.clearTimeout(writeTimeoutRef.current); writeTimeoutRef.current = null } }
    }
  }

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !detailReady || !can(access, 'test:update') || saving) return
    const context = contextRef.current; const current = selected; const input = editInput(); const operation = ++writeRoundRef.current; readRoundRef.current += 1; cancelRead(); cancelWrite()
    const prior = saveIntentRef.current
    const key = prior && prior.testId === current.id && prior.etag === (current.etag ?? '') && JSON.stringify(prior.input) === JSON.stringify(input) ? prior.key : newIdempotencyKey()
    saveIntentRef.current = { key, testId: current.id, etag: current.etag ?? '', input }
    const controller = new AbortController(); writeControllerRef.current = controller; writeTimeoutRef.current = window.setTimeout(() => controller.abort(), 5000); setSaving(true); setNotice('')
    try {
      const updated = await testsApi.appendRevision(context.projectId, current, input, { signal: controller.signal, idempotencyKey: key })
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      saveIntentRef.current = null; setSelected(updated); setItems((currentItems) => currentItems.map((item) => item.id === updated.id ? updated : item)); setConflict(false); setState('ready'); const loaded = await selectTest(updated, { keepWrite: true }); if (loaded && writeRoundRef.current === operation && isCurrentContext(context)) setNotice('测试用例已保存为新修订')
    } catch (error) {
      if (writeRoundRef.current !== operation || !isCurrentContext(context)) return
      if (isAccessError(error, true)) { clearInvalidData('当前测试用例不可访问，请重新选择项目'); return }
      if (codeFor(error) === 'FORBIDDEN' || codeFor(error) === 'HTTP_403') { setWriteDenied(true); setState('ready'); setNotice('当前主体没有修改该项目测试用例的权限。'); return }
      setState('ready')
      if (codeFor(error) === 'HTTP_412' || codeFor(error) === 'STALE_VERSION' || codeFor(error) === 'PRECONDITION_FAILED') { setConflict(true); setNotice('测试用例已被其他人更新。步骤和正文草稿已保留，请查看最新版本后再决定如何修改。') }
      else setNotice(isAbort(error) ? '保存请求超时，请保留原意图后重试' : messageFor(error))
    } finally {
      if (writeRoundRef.current === operation && isCurrentContext(context)) { setSaving(false); writeControllerRef.current = null; if (writeTimeoutRef.current !== null) { window.clearTimeout(writeTimeoutRef.current); writeTimeoutRef.current = null } }
    }
  }

  const updateStep = (index: number, field: 'action' | 'expected', value: string) => setSteps((current) => current.map((step, position) => position === index ? { ...step, [field]: value } : step))
  const updateCreateStep = (index: number, field: 'action' | 'expected', value: string) => setCreateSteps((current) => current.map((step, position) => position === index ? { ...step, [field]: value } : step))
  const moveStep = (index: number, direction: -1 | 1) => setSteps((current) => { const target = index + direction; if (target < 0 || target >= current.length) return current; const next = [...current]; [next[index], next[target]] = [next[target], next[index]]; return normalizeSteps(next) })
  const moveCreateStep = (index: number, direction: -1 | 1) => setCreateSteps((current) => { const target = index + direction; if (target < 0 || target >= current.length) return current; const next = [...current]; [next[index], next[target]] = [next[target], next[index]]; return normalizeSteps(next) })

  const renderSteps = (values: TestStep[], editable: boolean, update: (index: number, field: 'action' | 'expected', value: string) => void, remove: (index: number) => void, move: (index: number, direction: -1 | 1) => void, interactionDisabled = false) => <div className="test-steps" aria-label={editable ? '编辑步骤' : '步骤快照'}>
    {values.length === 0 && <p className="access-empty">暂无步骤（仅表示测试定义尚未可执行）。</p>}
    {values.map((step, index) => <article className="test-step" key={step.stepKey}>
      <div className="test-step-heading"><strong>步骤 {index + 1}</strong><code>{step.stepKey}</code>{editable && <span><button type="button" className="link-button" onClick={() => move(index, -1)} disabled={interactionDisabled || saving || index === 0}>上移</button><button type="button" className="link-button" onClick={() => move(index, 1)} disabled={interactionDisabled || saving || index === values.length - 1}>下移</button><button type="button" className="link-button" onClick={() => remove(index)} disabled={interactionDisabled || saving}>移除</button></span>}</div>
      {editable ? <div className="test-step-fields"><label>操作<textarea aria-label={`步骤 ${index + 1} 操作`} value={step.action} onChange={(event) => update(index, 'action', event.target.value)} rows={2} maxLength={5000} disabled={interactionDisabled || saving} required /></label><label>预期结果<textarea aria-label={`步骤 ${index + 1} 预期结果`} value={step.expected} onChange={(event) => update(index, 'expected', event.target.value)} rows={2} maxLength={5000} disabled={interactionDisabled || saving} required /></label></div> : <div className="test-step-fields"><div><span className="panel-label">操作</span><p>{step.action || '（空）'}</p></div><div><span className="panel-label">预期结果</span><p>{step.expected || '（空）'}</p></div></div>}
    </article>)}
  </div>

  if (!projectId) return <section className="test-cases-panel" aria-labelledby="test-cases-heading"><div className="access-panel-header"><div><span className="panel-label">M08 · 手工测试用例</span><h2 id="test-cases-heading">测试用例</h2></div></div><p className="access-empty" role="status">请选择一个项目以查看测试用例。</p></section>
  if (!access || !can(access, 'test:read')) return <section className="test-cases-panel" aria-labelledby="test-cases-heading"><div className="access-panel-header"><div><span className="panel-label">M08 · 手工测试用例</span><h2 id="test-cases-heading">测试用例</h2></div></div><p className="access-error" role="alert">当前会话没有读取该项目测试用例的权限。</p></section>
  const creator = can(access, 'test:create') && !writeDenied; const writable = can(access, 'test:update') && !writeDenied; const historyReadable = can(access, 'test:history:read') || can(access, 'test:read')
  return <section className="test-cases-panel" aria-labelledby="test-cases-heading">
    <div className="access-panel-header"><div><span className="panel-label">M08 · 手工测试用例</span><h2 id="test-cases-heading">测试用例</h2><p>项目内手工用例、步骤和不可变修订。</p></div><button type="button" className="secondary-button" onClick={() => void load()} disabled={state === 'loading' || saving}>{state === 'loading' ? '加载中…' : '刷新用例'}</button></div>
    <form className="test-search-form" onSubmit={(event) => { event.preventDefault(); setSearch(searchInput); void load(null, searchInput) }}><label>查询<input aria-label="查询测试用例" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} placeholder="标题或编号" maxLength={200} /></label><button type="submit" className="secondary-button" disabled={state === 'loading' || saving}>查询</button></form>
    {state === 'error' && <p className="access-error" role="alert">{notice}</p>}
    {notice && state !== 'error' && <p className={conflict ? 'requirements-conflict' : 'access-notice'} role={conflict ? 'alert' : 'status'}>{notice}{conflict && selected && <button type="button" className="secondary-button" onClick={() => void selectTest(selected, { preserveDraft: true })}>查看最新版本</button>}</p>}
    {creator && !scopeInvalid && <form className="test-create-form" onSubmit={(event) => void create(event)}><strong>新建手工用例</strong><label>标题<input aria-label="测试用例标题" value={createTitle} onChange={(event) => { createIntentRef.current = null; setCreateTitle(event.target.value) }} maxLength={500} required disabled={saving} /></label><label>说明<textarea aria-label="测试用例说明" value={createDescription} onChange={(event) => { createIntentRef.current = null; setCreateDescription(event.target.value) }} rows={2} maxLength={10000} disabled={saving} /></label><label>前置条件<textarea aria-label="测试用例前置条件" value={createPreconditions} onChange={(event) => { createIntentRef.current = null; setCreatePreconditions(event.target.value) }} rows={2} maxLength={10000} disabled={saving} /></label><button type="submit" className="primary-button" disabled={saving}>{saving ? '保存中…' : '创建用例'}</button><button type="button" className="secondary-button" onClick={() => setCreateSteps((current) => normalizeSteps([...current, newStep()]))} disabled={saving}>新增步骤</button>{renderSteps(createSteps, true, updateCreateStep, (index) => setCreateSteps((current) => normalizeSteps(current.filter((_step, position) => position !== index))), moveCreateStep)}</form>}
    {state !== 'loading' && state !== 'error' && items.length === 0 && <p className="access-empty" role="status">当前项目还没有手工测试用例。</p>}
    {items.length > 0 && <div className="test-cases-layout"><div className="test-case-list" aria-label="测试用例列表"><div className="member-list-heading"><strong>用例列表</strong><span>{items.length} 条</span></div>{items.map((item) => <button type="button" className={`requirement-row${selected?.id === item.id ? ' is-selected' : ''}`} key={item.id} onClick={() => void selectTest(item)}><span><strong>{item.displayNumber}</strong><span>{item.title}</span></span><small>修订 {item.revisionNumber}</small></button>)}{nextCursor && <button type="button" className="secondary-button" onClick={() => void load(nextCursor)} disabled={state === 'loading' || saving}>加载更多用例</button>}</div>
      {selected && <div className="test-case-detail" aria-label="测试用例详情"><div className="requirement-detail-heading"><div><span className="panel-label">用例详情 · {selected.displayNumber}</span><h3>{selected.title}</h3><p>MANUAL · 修订 {selected.revisionNumber} · 版本 {selected.rowVersion}</p></div>{conflict && <span className="requirements-draft-badge">草稿已保留</span>}</div>{writable ? <form onSubmit={(event) => void save(event)}><label>标题<input aria-label="编辑测试用例标题" value={title} onChange={(event) => { saveIntentRef.current = null; setTitle(event.target.value) }} maxLength={500} required disabled={detailLoading || !detailReady || saving} /></label><label>说明<textarea aria-label="编辑测试用例说明" value={description} onChange={(event) => { saveIntentRef.current = null; setDescription(event.target.value) }} rows={3} maxLength={10000} disabled={detailLoading || !detailReady || saving} /></label><label>前置条件<textarea aria-label="编辑测试用例前置条件" value={preconditions} onChange={(event) => { saveIntentRef.current = null; setPreconditions(event.target.value) }} rows={3} maxLength={10000} disabled={detailLoading || !detailReady || saving} /></label>{renderSteps(steps, true, updateStep, (index) => setSteps((current) => normalizeSteps(current.filter((_step, position) => position !== index))), moveStep, detailLoading || !detailReady)}<div className="test-detail-actions"><button type="button" className="secondary-button" onClick={() => setSteps((current) => normalizeSteps([...current, newStep()]))} disabled={detailLoading || !detailReady || saving}>新增步骤</button><button className="primary-button" type="submit" disabled={saving || detailLoading || !detailReady}>{saving ? '保存中…' : '保存为新修订'}</button></div></form> : <><p className="requirement-body">{selected.description || '（无说明）'}</p><p className="access-empty">只读用户可以查看用例和历史，但不能创建或保存。</p>{renderSteps(selected.steps, false, updateStep, () => undefined, moveStep)}</>}
        {historyReadable && <section className="requirement-history" aria-label="测试用例修订历史"><div className="member-list-heading"><strong>不可变修订历史</strong><span>{revisions.length} 个修订</span></div>{revisions.length === 0 ? <p className="access-empty">暂无修订历史。</p> : <div className="revision-list">{revisions.map((revision) => <button type="button" className="revision-row" key={revision.id} onClick={() => setSelectedRevision(revision)}><span>修订 {revision.revisionNumber}</span><small>{revision.createdBy} · {revision.createdAt}</small></button>)}</div>}{selectedRevision && <article className="revision-readonly"><strong>修订 {selectedRevision.revisionNumber}（只读）</strong><h4>{selectedRevision.title}</h4><p>{selectedRevision.description || '（无说明）'}</p><p>{selectedRevision.preconditions || '（无前置条件）'}</p>{renderSteps(selectedRevision.steps, false, updateStep, () => undefined, moveStep)}</article>}</section>}
      </div>}
    </div>}
  </section>
}
