import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { ProjectAccess } from './projectAccess'
import { newIdempotencyKey } from './requirements'
import { testsApi, type TestCase, type TestRevision } from './tests'
import { executionApi, type ExecutionState, type Run, type RunAttempt, type StepOutcome, type TestInstance, type TestSet } from './testExecution'

type Props = { projectId: string; access: ProjectAccess | null; resetSignal?: number }
const can = (access: ProjectAccess | null, permission: string) => access?.permissions.includes(permission) === true
const canWrite = (access: ProjectAccess | null) => ['run:write', 'test:run', 'test:create', 'test:update'].some((permission) => can(access, permission))
const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '执行请求失败，请稍后重试'
const isAbort = (error: unknown) => typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'

export default function TestExecutionPanel({ projectId, access, resetSignal = 0 }: Props) {
  const [sets, setSets] = useState<TestSet[]>([])
  const [selectedSet, setSelectedSet] = useState<TestSet | null>(null)
  const [instances, setInstances] = useState<TestInstance[]>([])
  const [runs, setRuns] = useState<Run[]>([])
  const [selectedRun, setSelectedRun] = useState<Run | null>(null)
  const [attempts, setAttempts] = useState<RunAttempt[]>([])
  const [selectedAttempt, setSelectedAttempt] = useState<RunAttempt | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [notice, setNotice] = useState('')
  const [setName, setSetName] = useState('')
  const [setDescription, setSetDescription] = useState('')
  const [testCaseId, setTestCaseId] = useState('')
  const [testRevisionId, setTestRevisionId] = useState('')
  const [availableCases, setAvailableCases] = useState<TestCase[]>([])
  const [availableRevisions, setAvailableRevisions] = useState<TestRevision[]>([])
  const [actuals, setActuals] = useState<Record<string, string>>({})
  const [outcomes, setOutcomes] = useState<Record<string, StepOutcome>>({})
  const [pending, setPending] = useState<string | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const roundRef = useRef(0)
  const activeProjectRef = useRef(projectId)
  const pendingIntentKeysRef = useRef(new Map<string, string>())
  const accessKey = access ? `${access.tenantId}:${access.principalId}:${access.roles.join('|')}:${access.permissions.join('|')}` : ''

  const cancel = () => { controllerRef.current?.abort(); controllerRef.current = null }
  const begin = () => { roundRef.current += 1; cancel(); setPending(null); const controller = new AbortController(); controllerRef.current = controller; return { round: roundRef.current, controller } }
  const current = (round: number, controller: AbortController) => roundRef.current === round && controllerRef.current === controller && activeProjectRef.current === projectId
  const reset = () => { setSets([]); setSelectedSet(null); setInstances([]); setRuns([]); setSelectedRun(null); setAttempts([]); setSelectedAttempt(null); setActuals({}); setOutcomes({}); setAvailableCases([]); setAvailableRevisions([]); setTestCaseId(''); setTestRevisionId(''); setNotice(''); setPending(null); pendingIntentKeysRef.current.clear() }
  const operationKey = (scope: string) => { const existing = pendingIntentKeysRef.current.get(scope); if (existing) return existing; const key = newIdempotencyKey(); pendingIntentKeysRef.current.set(scope, key); return key }
  const completeOperation = (scope: string) => { pendingIntentKeysRef.current.delete(scope) }

  const loadSets = async () => {
    const { round, controller } = begin(); setState('loading'); setNotice('')
    try { const result = await executionApi.listSets(projectId, { signal: controller.signal }); if (!current(round, controller)) return; setSets(result); setState('ready') }
    catch (error) { if (!current(round, controller)) return; setState('error'); setNotice(isAbort(error) ? '测试集请求超时，请稍后重试' : messageFor(error)) }
    finally { if (current(round, controller)) controllerRef.current = null }
  }
  const loadSet = async (set: TestSet) => {
    const { round, controller } = begin(); setSelectedSet(set); setInstances([]); setRuns([]); setSelectedRun(null); setAttempts([]); setSelectedAttempt(null); setState('loading'); setNotice('')
    try { const [detail, listed, listedRuns] = await Promise.all([executionApi.getSet(projectId, set.id, { signal: controller.signal }), executionApi.listInstances(projectId, set.id, { signal: controller.signal }), executionApi.listRuns(projectId, { signal: controller.signal })]); if (!current(round, controller)) return; setSelectedSet(detail); setInstances(listed); setRuns((listedRuns ?? []).filter((run) => !run.instanceId || listed.some((item) => item.id === run.instanceId))); setState('ready') }
    catch (error) { if (!current(round, controller)) return; setState('error'); setNotice(isAbort(error) ? '测试集详情请求超时，请稍后重试' : messageFor(error)) }
    finally { if (current(round, controller)) controllerRef.current = null }
  }
  const loadRun = async (run: Run) => {
    const { round, controller } = begin(); setSelectedRun(run); setAttempts([]); setSelectedAttempt(null); setActuals({}); setOutcomes({}); setState('loading')
    try { const [detail, history] = await Promise.all([executionApi.getRun(projectId, run.id, { signal: controller.signal }), executionApi.listAttempts(projectId, run.id, { signal: controller.signal })]); if (!current(round, controller)) return; setSelectedRun(detail); setAttempts(history); setSelectedAttempt(detail.attempt ?? history[history.length - 1] ?? null); setState('ready') }
    catch (error) { if (!current(round, controller)) return; setState('error'); setNotice(isAbort(error) ? '运行详情请求超时，请稍后重试' : messageFor(error)) }
    finally { if (current(round, controller)) controllerRef.current = null }
  }
  const startRun = async (instance: TestInstance) => {
    const { round, controller } = begin(); setPending('start'); setNotice('')
    const intent = `start:${projectId}:${instance.id}`
    try { const created = await executionApi.createRun(projectId, { instanceId: instance.id, mode: 'MANUAL' }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (!current(round, controller)) return; completeOperation(intent); setRuns((value) => [created, ...value.filter((item) => item.id !== created.id)]); await loadRun(created); if (current(round, controller)) setNotice('手工运行已启动') }
    catch (error) { if (!current(round, controller)) return; setPending(null); setNotice(isAbort(error) ? '启动请求超时，请保留原意图后重试' : messageFor(error)) }
    finally { if (current(round, controller)) setPending(null) }
  }
  const updateAttempt = (next: RunAttempt) => { setSelectedAttempt(next); setAttempts((value) => value.map((item) => item.id === next.id ? next : item)); setSelectedRun((value) => value ? { ...value, state: next.state, outcome: next.outcome } : value) }
  const saveStep = async (stepKey: string) => {
    if (!selectedRun || !selectedAttempt || !canWrite(access)) return
    const target = selectedAttempt.steps.find((step) => step.stepKey === stepKey)
    if (!target || target.rowVersion === undefined) { setNotice('步骤版本缺失，无法安全保存，请刷新运行详情'); return }
    const { round, controller } = begin(); setPending(`step:${stepKey}`)
    const actual = actuals[stepKey] ?? target.actual
    const outcome = outcomes[stepKey] ?? target.outcome
    const intent = `step:${projectId}:${selectedRun.id}:${selectedAttempt.id}:${stepKey}:${target.rowVersion}:${actual}:${outcome}`
    try { const next = await executionApi.saveStep(projectId, selectedRun.id, selectedAttempt.id, stepKey, { actual, outcome, rowVersion: target.rowVersion }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); updateAttempt(next); setNotice('步骤结果已保存') } }
    catch (error) { if (current(round, controller)) { setPending(null); setNotice(isAbort(error) ? '步骤保存超时，请保留原意图后重试' : messageFor(error)) } }
    finally { if (current(round, controller)) setPending(null) }
  }
  const attemptAction = async (action: 'pause' | 'resume' | 'finish') => {
    if (!selectedRun || !selectedAttempt || !canWrite(access)) return
    const version = selectedAttempt.rowVersion
    if (version === undefined) { setNotice('尝试版本缺失，无法安全更新，请刷新运行详情'); return }
    const { round, controller } = begin(); setPending(action)
    const intent = `${action}:${projectId}:${selectedRun.id}:${selectedAttempt.id}:${version}`
    try { const next = await executionApi[action](projectId, selectedRun.id, selectedAttempt.id, version, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); updateAttempt(next); setNotice(action === 'finish' ? '运行已完成' : action === 'pause' ? '运行已暂停' : '运行已继续') } }
    catch (error) { if (current(round, controller)) { setPending(null); setNotice(isAbort(error) ? '操作请求超时，请稍后重试' : messageFor(error)) } }
    finally { if (current(round, controller)) setPending(null) }
  }
  const rerun = async () => { if (!selectedRun || !canWrite(access)) return; const { round, controller } = begin(); setPending('rerun'); const intent = `rerun:${projectId}:${selectedRun.id}`; try { const next = await executionApi.rerun(projectId, selectedRun.id, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setAttempts((value) => [...value, next]); setSelectedAttempt(next); setActuals({}); setOutcomes({}); setNotice('已创建新的运行尝试') } } catch (error) { if (current(round, controller)) { setPending(null); setNotice(isAbort(error) ? '重新运行请求超时，请保留原意图后重试' : messageFor(error)) } } finally { if (current(round, controller)) setPending(null) } }
  const createSet = async (event: FormEvent) => { event.preventDefault(); if (!setName.trim() || !canWrite(access)) return; const { round, controller } = begin(); setPending('create-set'); const name = setName.trim(); const intent = `create-set:${projectId}:${name}:${setDescription}`; try { const created = await executionApi.createSet(projectId, { name, description: setDescription }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setSets((value) => [...value, created]); setSetName(''); setSetDescription(''); setNotice('测试集已创建') } } catch (error) { if (current(round, controller)) { setPending(null); setNotice(isAbort(error) ? '创建请求超时，请保留原意图后重试' : messageFor(error)) } } finally { if (current(round, controller)) setPending(null) } }
  const addInstance = async (event: FormEvent) => { event.preventDefault(); if (!selectedSet || !testCaseId.trim() || !testRevisionId.trim() || !canWrite(access)) return; const { round, controller } = begin(); setPending('add-instance'); const caseId = testCaseId.trim(); const revisionId = testRevisionId.trim(); const intent = `add-instance:${projectId}:${selectedSet.id}:${caseId}:${revisionId}`; try { const created = await executionApi.addInstance(projectId, selectedSet.id, { testCaseId: caseId, testRevisionId: revisionId }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setInstances((value) => [...value, created]); setTestCaseId(''); setTestRevisionId(''); setNotice('测试实例已加入') } } catch (error) { if (current(round, controller)) { setPending(null); setNotice(isAbort(error) ? '加入请求超时，请保留原意图后重试' : messageFor(error)) } } finally { if (current(round, controller)) setPending(null) } }
  const loadAvailableCases = async () => { const { round, controller } = begin(); setNotice(''); try { const page = await testsApi.list(projectId, '', undefined, { signal: controller.signal }); if (current(round, controller)) { setAvailableCases(page.items); setAvailableRevisions([]); setTestCaseId(''); setTestRevisionId(''); setNotice(page.items.length ? '请选择要加入的已保存用例' : '当前项目没有可加入的手工用例') } } catch (error) { if (current(round, controller)) setNotice(isAbort(error) ? '用例列表请求超时，请稍后重试' : messageFor(error)) } }
  const selectAvailableCase = async (id: string) => { setTestCaseId(id); setTestRevisionId(''); setAvailableRevisions([]); if (!id) return; const { round, controller } = begin(); try { const revisions = await testsApi.revisions(projectId, id, { signal: controller.signal }); if (current(round, controller)) setAvailableRevisions(revisions) } catch (error) { if (current(round, controller)) setNotice(isAbort(error) ? '修订列表请求超时，请稍后重试' : messageFor(error)) } }

  useEffect(() => { activeProjectRef.current = projectId; roundRef.current += 1; cancel(); reset(); if (!projectId || !can(access, 'test:read')) { setState('idle'); return } void loadSets(); return () => { roundRef.current += 1; cancel() } }, [projectId, accessKey, resetSignal])

  if (!projectId) return <section className="execution-panel" aria-labelledby="execution-heading"><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2><p className="access-empty">请选择一个项目以查看测试集。</p></section>
  if (!access || !can(access, 'test:read')) return <section className="execution-panel" aria-labelledby="execution-heading"><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2><p className="access-error" role="alert">当前会话没有读取测试集和运行记录的权限。</p></section>
  const selectedSteps = selectedAttempt?.steps ?? []
  const selectedState: ExecutionState | null = selectedAttempt?.state ?? null
  const editable = canWrite(access) && selectedState === 'RUNNING'
  return <section className="execution-panel" aria-labelledby="execution-heading">
    <div className="access-panel-header"><div><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2></div><button className="secondary-button" type="button" onClick={() => void loadSets()} disabled={state === 'loading' || pending !== null}>刷新测试集</button></div>
    {state === 'loading' && <p role="status">加载中…</p>}{state === 'error' && <div className="access-error" role="alert"><p>{notice}</p><button className="secondary-button" type="button" onClick={() => void loadSets()}>重试</button></div>}{notice && state !== 'error' && <p className="access-notice" role="status">{notice}</p>}
    {canWrite(access) && <form className="execution-create-form" onSubmit={createSet}><strong>创建测试集</strong><label>名称<input aria-label="测试集名称" value={setName} onChange={(event) => setSetName(event.target.value)} required disabled={pending !== null} /></label><label>说明<input aria-label="测试集说明" value={setDescription} onChange={(event) => setSetDescription(event.target.value)} disabled={pending !== null} /></label><button className="primary-button" type="submit" disabled={!setName.trim() || pending !== null}>创建测试集</button></form>}
    <div className="execution-layout"><div className="execution-set-list" role="list" aria-label="测试集列表">{sets.length === 0 && state === 'ready' && <p className="access-empty">当前项目还没有测试集。</p>}{sets.map((item) => <button type="button" className={selectedSet?.id === item.id ? 'execution-set is-selected' : 'execution-set'} key={item.id} onClick={() => void loadSet(item)}>{item.name}<small>{item.description || '无说明'}</small></button>)}</div>
      {selectedSet && <div className="execution-detail">
        <h3>{selectedSet.name}</h3><p>{selectedSet.description || '无说明'}</p>
        {canWrite(access) && <form className="execution-add-form" onSubmit={addInstance}><strong>加入已保存用例修订</strong><label>用例 ID<input aria-label="用例 ID" value={testCaseId} onChange={(event) => setTestCaseId(event.target.value)} required disabled={pending !== null} /></label><label>修订 ID<input aria-label="修订 ID" value={testRevisionId} onChange={(event) => setTestRevisionId(event.target.value)} required disabled={pending !== null} /></label><button className="primary-button" type="submit" disabled={pending !== null}>加入实例</button></form>}
        <h4>测试实例</h4>{instances.length === 0 && <p className="access-empty">尚未加入实例。</p>}{instances.map((item) => <article className="execution-instance" key={item.id}><div><strong>{item.displayNumber ? `${item.displayNumber} · ` : ''}{item.title}</strong><small>修订 {item.revisionNumber}</small></div>{canWrite(access) && <button className="secondary-button" type="button" onClick={() => void startRun(item)} disabled={pending !== null}>启动手工运行</button>}</article>)}
        <h4>运行记录</h4>{runs.length === 0 && <p className="access-empty">选择实例启动后，运行记录将在此显示。</p>}{runs.map((item) => <button className="execution-run" type="button" key={item.id} onClick={() => void loadRun(item)} disabled={pending !== null}>运行 {item.id} · {item.state}</button>)}
        {selectedAttempt && <div className="execution-attempt" aria-label="运行详情"><div className="execution-attempt-heading"><h4>运行详情 · 尝试 {selectedAttempt.attemptNo}</h4><span>{selectedState === 'RUNNING' ? '运行中' : selectedState === 'PAUSED' ? '已暂停' : '已完成'}{selectedAttempt.outcome ? ` · ${selectedAttempt.outcome}` : ''}</span></div>
          {selectedSteps.map((item) => <article className="execution-step" key={item.stepKey}><strong>步骤 {item.ordinal}</strong><p>{item.action}</p><small>预期：{item.expected}</small><label>实际结果<textarea aria-label={`步骤 ${item.ordinal} 实际结果`} value={actuals[item.stepKey] ?? item.actual} onChange={(event) => setActuals((value) => ({ ...value, [item.stepKey]: event.target.value }))} disabled={!editable || pending !== null} /></label><label>结论<select aria-label={`步骤 ${item.ordinal} 结论`} value={outcomes[item.stepKey] ?? item.outcome} onChange={(event) => setOutcomes((value) => ({ ...value, [item.stepKey]: event.target.value as StepOutcome }))} disabled={!editable || pending !== null}><option value="NOT_RUN">未执行</option><option value="PASS">通过</option><option value="FAIL">失败</option><option value="BLOCKED">阻塞</option></select></label>{editable && <button className="secondary-button" type="button" onClick={() => void saveStep(item.stepKey)} disabled={pending !== null}>保存步骤结果</button>}</article>)}
          <div className="execution-actions">{selectedState === 'RUNNING' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void attemptAction('pause')} disabled={pending !== null}>暂停</button>}{selectedState === 'PAUSED' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void attemptAction('resume')} disabled={pending !== null}>继续</button>}{selectedState === 'RUNNING' && canWrite(access) && <button className="primary-button" type="button" onClick={() => void attemptAction('finish')} disabled={pending !== null}>完成运行</button>}{selectedState === 'FINISHED' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void rerun()} disabled={pending !== null}>重新运行</button>}</div>
          {attempts.length > 0 && <div className="execution-history" aria-label="尝试历史"><h4>尝试历史</h4>{attempts.map((item) => <button type="button" className="execution-attempt-row" key={item.id} onClick={() => { setSelectedAttempt(item); setActuals({}); setOutcomes({}); setNotice('') }} disabled={pending !== null}>尝试 {item.attemptNo} · {item.outcome ?? item.state}</button>)}</div>}
        </div>}
      </div>}
      {selectedSet && canWrite(access) && <div className="execution-selector"><button className="secondary-button" type="button" onClick={() => void loadAvailableCases()}>加载已保存用例</button>{availableCases.length > 0 && <><label>用例<select aria-label="已保存用例" value={testCaseId} onChange={(event) => void selectAvailableCase(event.target.value)}><option value="">请选择用例</option>{availableCases.map((item) => <option key={item.id} value={item.id}>{item.displayNumber} · {item.title}</option>)}</select></label><label>修订<select aria-label="已保存修订" value={testRevisionId} onChange={(event) => setTestRevisionId(event.target.value)} disabled={!availableRevisions.length}><option value="">请选择修订</option>{availableRevisions.map((revision) => <option key={revision.id} value={revision.id}>修订 {revision.revisionNumber} · {revision.title}</option>)}</select></label></>}</div>}
    </div>
  </section>
}
