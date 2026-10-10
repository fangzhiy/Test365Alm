import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { ProjectAccess } from './projectAccess'
import { newIdempotencyKey } from './requirements'
import { testsApi, type TestCase, type TestRevision } from './tests'
import { executionApi, type ExecutionState, type Run, type RunAttempt, type RunSummary, type StepOutcome, type TestInstance, type TestSet } from './testExecution'

type Props = { projectId: string; access: ProjectAccess | null; resetSignal?: number }
const can = (access: ProjectAccess | null, permission: string) => access?.permissions.includes(permission) === true
const canWrite = (access: ProjectAccess | null) => ['run:write', 'test:run', 'test:create', 'test:update'].some((permission) => can(access, permission))
const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '执行请求失败，请稍后重试'
const isAbort = (error: unknown) => typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
const isStaleVersion = (error: unknown) => {
  if (typeof error !== 'object' || error === null) return false
  const value = error as { code?: unknown; status?: unknown }
  return value.code === 'STALE_VERSION' || value.code === 'HTTP_412' || value.code === 'PRECONDITION_FAILED' || value.status === 412
}
const isScopeAccessError = (error: unknown) => {
  if (typeof error !== 'object' || error === null) return false
  const value = error as { code?: unknown; status?: unknown }
  if (typeof value.code === 'string') {
    return value.code === 'UNAUTHENTICATED' || value.code === 'FORBIDDEN' || value.code === 'NOT_FOUND'
      || value.code === 'PROJECT_ACCESS_DENIED' || value.code === 'PROJECT_NOT_FOUND'
  }
  // A bare 403 is treated as a scope denial only when the shared error parser
  // did not preserve a more specific write/CSRF business code.  This keeps a
  // CSRF rejection or stale-version response from logging the user out.
  return value.status === 401 || value.status === 404 || value.status === 403
}
type DraftBinding = {
  projectId: string
  runId: string
  attemptId: string
  baseVersions: Record<string, number>
  baseActuals: Record<string, string>
  baseOutcomes: Record<string, StepOutcome>
}

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
  const [scopeDenied, setScopeDenied] = useState(false)
  const [setsCursor, setSetsCursor] = useState<string | null>(null)
  const [instancesCursor, setInstancesCursor] = useState<string | null>(null)
  const [runsCursor, setRunsCursor] = useState<string | null>(null)
  const [attemptsCursor, setAttemptsCursor] = useState<string | null>(null)
  const [summary, setSummary] = useState<RunSummary | null>(null)
  const [conflict, setConflict] = useState<{ runId: string; attemptId: string; stepKeys: string[] } | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const timeoutRef = useRef<number | null>(null)
  const roundRef = useRef(0)
  const activeProjectRef = useRef(projectId)
  const pendingIntentKeysRef = useRef(new Map<string, string>())
  const draftBindingRef = useRef<DraftBinding | null>(null)
  const accessKey = access ? `${access.tenantId}:${access.principalId}:${access.roles.join('|')}:${access.permissions.join('|')}` : ''

  const clearTimeoutRef = () => { if (timeoutRef.current !== null) { window.clearTimeout(timeoutRef.current); timeoutRef.current = null } }
  const cancel = () => { controllerRef.current?.abort(); controllerRef.current = null; clearTimeoutRef() }
  const begin = (timeoutMessage = '执行请求超时，请稍后重试', timeoutSetsError = false) => {
    roundRef.current += 1; cancel(); setPending(null)
    const controller = new AbortController(); const round = roundRef.current
    controllerRef.current = controller
    timeoutRef.current = window.setTimeout(() => {
      if (!current(round, controller)) return
      // Abort is advisory: invalidate this round before notifying so a transport
      // that ignores AbortSignal cannot commit a late response.
      roundRef.current += 1; controllerRef.current = null; clearTimeoutRef(); controller.abort()
      if (timeoutSetsError) setState('error')
      setPending(null); setNotice(timeoutMessage)
    }, 5000)
    return { round, controller }
  }
  const finish = (round: number, controller: AbortController) => { const active = current(round, controller); if (active) { controllerRef.current = null; clearTimeoutRef() } return active }
  const current = (round: number, controller: AbortController) => roundRef.current === round && controllerRef.current === controller && activeProjectRef.current === projectId
  const reset = () => { setSets([]); setSelectedSet(null); setInstances([]); setRuns([]); setSelectedRun(null); setAttempts([]); setSelectedAttempt(null); setActuals({}); setOutcomes({}); draftBindingRef.current = null; setConflict(null); setSummary(null); setSetsCursor(null); setInstancesCursor(null); setRunsCursor(null); setAttemptsCursor(null); setAvailableCases([]); setAvailableRevisions([]); setTestCaseId(''); setTestRevisionId(''); setNotice(''); setPending(null); pendingIntentKeysRef.current.clear() }
  const bindDraftStep = (stepKey: string, baseVersion: number | undefined) => {
    if (!selectedRun || !selectedAttempt || baseVersion === undefined) return
    const currentBinding = draftBindingRef.current
    if (!currentBinding || currentBinding.projectId !== projectId || currentBinding.runId !== selectedRun.id || currentBinding.attemptId !== selectedAttempt.id) {
      draftBindingRef.current = { projectId, runId: selectedRun.id, attemptId: selectedAttempt.id, baseVersions: { [stepKey]: baseVersion }, baseActuals: { [stepKey]: selectedAttempt.steps.find((step) => step.stepKey === stepKey)?.actual ?? '' }, baseOutcomes: { [stepKey]: selectedAttempt.steps.find((step) => step.stepKey === stepKey)?.outcome ?? 'NOT_RUN' } }
      return
    }
    if (currentBinding.baseVersions[stepKey] !== undefined) return
    const base = selectedAttempt.steps.find((step) => step.stepKey === stepKey)
    draftBindingRef.current = { ...currentBinding, baseVersions: { ...currentBinding.baseVersions, [stepKey]: baseVersion }, baseActuals: { ...currentBinding.baseActuals, [stepKey]: base?.actual ?? '' }, baseOutcomes: { ...currentBinding.baseOutcomes, [stepKey]: base?.outcome ?? 'NOT_RUN' } }
  }
  const invalidateScope = () => { roundRef.current += 1; cancel(); reset(); setScopeDenied(true); setState('error'); setNotice('当前会话已失去测试集和运行记录的权限。') }
  const operationKey = (scope: string) => { const existing = pendingIntentKeysRef.current.get(scope); if (existing) return existing; const key = newIdempotencyKey(); pendingIntentKeysRef.current.set(scope, key); return key }
  const completeOperation = (scope: string) => { pendingIntentKeysRef.current.delete(scope) }

  // The array methods remain as a compatibility fallback for older test doubles
  // and development proxies.  Production always uses the bounded page methods.
  const readSetsPage = async (cursor: string | null, signal: AbortSignal) => {
    if (typeof executionApi.pageSets === 'function') { const page = await executionApi.pageSets(projectId, cursor, 25, { signal }); if (page) return page }
    return { items: (await executionApi.listSets(projectId, { signal })) ?? [], nextCursor: null }
  }
  const readInstancesPage = async (setId: string, cursor: string | null, signal: AbortSignal) => {
    if (typeof executionApi.pageInstances === 'function') { const page = await executionApi.pageInstances(projectId, setId, cursor, 25, { signal }); if (page) return page }
    return { items: (await executionApi.listInstances(projectId, setId, { signal })) ?? [], nextCursor: null }
  }
  const readRunsPage = async (setId: string | undefined, cursor: string | null, signal: AbortSignal) => {
    if (typeof executionApi.pageRuns === 'function') { const page = await executionApi.pageRuns(projectId, setId ? { setId } : undefined, cursor, 25, { signal }); if (page) return page }
    return { items: (await executionApi.listRuns(projectId, { signal })) ?? [], nextCursor: null }
  }
  const readAttemptsPage = async (runId: string, cursor: string | null, signal: AbortSignal) => {
    if (typeof executionApi.pageAttempts === 'function') { const page = await executionApi.pageAttempts(projectId, runId, cursor, 25, { signal }); if (page) return page }
    return { items: (await executionApi.listAttempts(projectId, runId, { signal })) ?? [], nextCursor: null }
  }
  const readSummary = async (setId: string | undefined, signal: AbortSignal) => {
    if (typeof executionApi.summary === 'function') return (await executionApi.summary(projectId, setId ? { setId } : undefined, { signal })) ?? null
    return null
  }

  const loadSets = async () => {
    const { round, controller } = begin('测试集请求超时，请稍后重试', true); setState('loading'); setNotice('')
    try { const result = await readSetsPage(null, controller.signal); if (!current(round, controller)) return; setSets(result.items); setSetsCursor(result.nextCursor); setState('ready') }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setState('error'); setNotice(isAbort(error) ? '测试集请求超时，请稍后重试' : messageFor(error)) }
    finally { finish(round, controller) }
  }
  const loadMoreSets = async () => {
    if (!setsCursor || pending !== null) return
    const { round, controller } = begin('测试集分页请求超时，请稍后重试', true); setPending('sets-page')
    try { const result = await readSetsPage(setsCursor, controller.signal); if (!current(round, controller)) return; setSets((value) => [...value, ...result.items]); setSetsCursor(result.nextCursor) }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '测试集分页请求超时，请稍后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const loadSet = async (set: TestSet) => {
    const { round, controller } = begin('测试集详情请求超时，请稍后重试', true); setSelectedSet(set); setInstances([]); setRuns([]); setSelectedRun(null); setAttempts([]); setSelectedAttempt(null); setActuals({}); setOutcomes({}); draftBindingRef.current = null; setState('loading'); setNotice('')
    try { const [detail, listed, listedRuns, runSummary] = await Promise.all([executionApi.getSet(projectId, set.id, { signal: controller.signal }), readInstancesPage(set.id, null, controller.signal), readRunsPage(set.id, null, controller.signal), readSummary(set.id, controller.signal)]); if (!current(round, controller)) return; setSelectedSet(detail); setInstances(listed.items); setInstancesCursor(listed.nextCursor); setRuns(listedRuns.items); setRunsCursor(listedRuns.nextCursor); setSummary(runSummary); setState('ready') }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setState('error'); setNotice(isAbort(error) ? '测试集详情请求超时，请稍后重试' : messageFor(error)) }
    finally { finish(round, controller) }
  }
  const loadMoreInstances = async () => {
    if (!selectedSet || !instancesCursor || pending !== null) return
    const { round, controller } = begin('测试实例分页请求超时，请稍后重试', true); setPending('instances-page')
    try { const result = await readInstancesPage(selectedSet.id, instancesCursor, controller.signal); if (!current(round, controller)) return; setInstances((value) => [...value, ...result.items]); setInstancesCursor(result.nextCursor) }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '测试实例分页请求超时，请稍后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const loadMoreRuns = async () => {
    if (!selectedSet || !runsCursor || pending !== null) return
    const { round, controller } = begin('运行分页请求超时，请稍后重试', true); setPending('runs-page')
    try { const result = await readRunsPage(selectedSet.id, runsCursor, controller.signal); if (!current(round, controller)) return; setRuns((value) => [...value, ...result.items]); setRunsCursor(result.nextCursor) }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '运行分页请求超时，请稍后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const loadRun = async (run: Run, options?: { preserveDraft?: boolean }) => {
    const preserveDraft = options?.preserveDraft === true
    const previousAttemptId = selectedAttempt?.id
    const { round, controller } = begin('运行详情请求超时，请稍后重试', true); setSelectedRun(run)
    if (!preserveDraft) { setAttempts([]); setSelectedAttempt(null); setActuals({}); setOutcomes({}) }
    setState('loading')
    try {
      const [detail, history] = await Promise.all([executionApi.getRun(projectId, run.id, { signal: controller.signal }), readAttemptsPage(run.id, null, controller.signal)])
      if (!current(round, controller)) return
      const preferredAttemptId = preserveDraft && previousAttemptId ? previousAttemptId : detail.attempt?.id
      const listedAttempt = preferredAttemptId ? history.items.find((item) => item.id === preferredAttemptId) : undefined
      // History pages contain summaries, not step snapshots. Prefer the full
      // current detail; fetch an exact detail when selecting another attempt.
      const nextAttempt = detail.attempt && detail.attempt.id === preferredAttemptId ? detail.attempt : listedAttempt ?? detail.attempt ?? history.items[history.items.length - 1] ?? null
      let selectedDetails = nextAttempt
      const detailAttemptId = preserveDraft && previousAttemptId ? previousAttemptId : nextAttempt?.id !== detail.attempt?.id ? nextAttempt?.id : undefined
      if (detailAttemptId && typeof executionApi.getAttempt === 'function') {
        // A refresh must keep a historical attempt selected even when it is
        // outside the first bounded history page; never silently jump to the
        // run's current attempt.
        try {
          const refreshed = await executionApi.getAttempt(projectId, run.id, detailAttemptId, { signal: controller.signal })
          // A compatible test double/proxy may not return a body. Keep the
          // bounded history row rather than replacing the selected attempt
          // with undefined; the real API parser rejects such a response.
          if (refreshed) selectedDetails = refreshed
        } catch (error) { if (!current(round, controller)) return; throw error }
      }
      if (!current(round, controller)) return
      setSelectedRun(detail); setAttempts(history.items); setAttemptsCursor(history.nextCursor); setSelectedAttempt(selectedDetails)
      const selectedAttemptId = selectedDetails?.id ?? nextAttempt?.id
      const draftMatches = draftBindingRef.current?.projectId === projectId && draftBindingRef.current.runId === run.id && draftBindingRef.current.attemptId === selectedAttemptId
      if (!preserveDraft || previousAttemptId !== selectedAttemptId || (draftBindingRef.current && !draftMatches)) { setActuals({}); setOutcomes({}); draftBindingRef.current = null; setConflict(null) }
      setState('ready')
    }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setState('error'); setNotice(isAbort(error) ? '运行详情请求超时，请稍后重试' : messageFor(error)) }
    finally { finish(round, controller) }
  }
  const loadMoreAttempts = async () => {
    if (!selectedRun || !attemptsCursor || pending !== null) return
    const { round, controller } = begin('尝试历史分页请求超时，请稍后重试', true); setPending('attempts-page')
    try { const result = await readAttemptsPage(selectedRun.id, attemptsCursor, controller.signal); if (!current(round, controller)) return; setAttempts((value) => [...value, ...result.items]); setAttemptsCursor(result.nextCursor) }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '尝试历史分页请求超时，请稍后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const selectAttempt = async (item: RunAttempt) => {
    if (!selectedRun || pending !== null) return
    const { round, controller } = begin('尝试详情请求超时，请稍后重试', true); setPending('attempt-read'); setState('loading'); setConflict(null)
    try {
      const detail = typeof executionApi.getAttempt === 'function' ? await executionApi.getAttempt(projectId, selectedRun.id, item.id, { signal: controller.signal }) : item
      if (!current(round, controller)) return
      setSelectedAttempt(detail ?? item); setActuals({}); setOutcomes({}); draftBindingRef.current = null; setNotice(''); setState('ready')
    } catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setState('error'); setNotice(isAbort(error) ? '尝试详情请求超时，请稍后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const rebaseConflict = () => {
    if (!conflict || !selectedRun || !selectedAttempt) return
    const baseVersions: Record<string, number> = {}; const baseActuals: Record<string, string> = {}; const baseOutcomes: Record<string, StepOutcome> = {}
    selectedAttempt.steps.forEach((step) => { if (step.rowVersion !== undefined) { baseVersions[step.stepKey] = step.rowVersion; baseActuals[step.stepKey] = step.actual; baseOutcomes[step.stepKey] = step.outcome } })
    draftBindingRef.current = { projectId, runId: selectedRun.id, attemptId: selectedAttempt.id, baseVersions, baseActuals, baseOutcomes }
    setConflict(null); setNotice('已确认最新运行状态；请检查草稿后再保存')
  }
  const startRun = async (instance: TestInstance) => {
    const { round, controller } = begin(); setPending('start'); setNotice('')
    const intent = `start:${projectId}:${instance.id}`
    try { const created = await executionApi.createRun(projectId, { instanceId: instance.id, mode: 'MANUAL' }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (!current(round, controller)) return; completeOperation(intent); setRuns((value) => [created, ...value.filter((item) => item.id !== created.id)]); await loadRun(created); if (current(round, controller)) setNotice('手工运行已启动') }
    catch (error) { if (!current(round, controller)) return; if (isScopeAccessError(error)) { invalidateScope(); return }; setPending(null); setNotice(isAbort(error) ? '启动请求超时，请保留原意图后重试' : messageFor(error)) }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const updateAttempt = (next: RunAttempt) => { setSelectedAttempt(next); setAttempts((value) => value.map((item) => item.id === next.id ? next : item)); setSelectedRun((value) => value ? { ...value, state: next.state, outcome: next.outcome } : value) }
  const saveStep = async (stepKey: string) => {
    if (conflict) { setNotice('请先采用最新版本继续编辑，再保存草稿'); return }
    if (state !== 'ready') { setNotice('运行详情仍在读取，请稍后再保存'); return }
    if (!selectedRun || !selectedAttempt || !canWrite(access)) return
    const target = selectedAttempt.steps.find((step) => step.stepKey === stepKey)
    if (!target || target.rowVersion === undefined) { setNotice('步骤版本缺失，无法安全保存，请刷新运行详情'); return }
    const { round, controller } = begin(); setPending(`step:${stepKey}`)
    const actual = actuals[stepKey] ?? target.actual
    const outcome = outcomes[stepKey] ?? target.outcome
    bindDraftStep(stepKey, target.rowVersion)
    const boundVersion = draftBindingRef.current?.projectId === projectId && draftBindingRef.current.runId === selectedRun.id && draftBindingRef.current.attemptId === selectedAttempt.id ? draftBindingRef.current.baseVersions[stepKey] : undefined
    const expectedVersion = boundVersion ?? target.rowVersion
    const intent = `step:${projectId}:${selectedRun.id}:${selectedAttempt.id}:${stepKey}:${expectedVersion}:${actual}:${outcome}`
    try { const next = await executionApi.saveStep(projectId, selectedRun.id, selectedAttempt.id, stepKey, { actual, outcome, rowVersion: expectedVersion }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); updateAttempt(next); const saved = next.steps.find((step) => step.stepKey === stepKey); if (saved?.rowVersion !== undefined && draftBindingRef.current?.runId === selectedRun.id && draftBindingRef.current.attemptId === selectedAttempt.id) draftBindingRef.current = { ...draftBindingRef.current, baseVersions: { ...draftBindingRef.current.baseVersions, [stepKey]: saved.rowVersion }, baseActuals: { ...draftBindingRef.current.baseActuals, [stepKey]: saved.actual }, baseOutcomes: { ...draftBindingRef.current.baseOutcomes, [stepKey]: saved.outcome } }; setConflict(null); setNotice('步骤结果已保存') } }
    catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; if (isStaleVersion(error)) { setConflict({ runId: selectedRun.id, attemptId: selectedAttempt.id, stepKeys: [stepKey] }); setPending(null); setNotice(messageFor(error)) } else { setPending(null); setNotice(isAbort(error) ? '步骤保存超时，请保留原意图后重试' : messageFor(error)) } } }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const attemptAction = async (action: 'pause' | 'resume' | 'finish') => {
    if (!selectedRun || !selectedAttempt || !canWrite(access)) return
    const version = selectedAttempt.rowVersion
    if (version === undefined) { setNotice('尝试版本缺失，无法安全更新，请刷新运行详情'); return }
    const { round, controller } = begin(); setPending(action)
    const intent = `${action}:${projectId}:${selectedRun.id}:${selectedAttempt.id}:${version}`
    try { const next = await executionApi[action](projectId, selectedRun.id, selectedAttempt.id, version, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); updateAttempt(next); setNotice(action === 'finish' ? '运行已完成' : action === 'pause' ? '运行已暂停' : '运行已继续') } }
    catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setPending(null); setNotice(isAbort(error) ? '操作请求超时，请稍后重试' : messageFor(error)) } }
    finally { if (finish(round, controller)) setPending(null) }
  }
  const rerun = async () => { if (!selectedRun || !canWrite(access)) return; const { round, controller } = begin(); setPending('rerun'); const intent = `rerun:${projectId}:${selectedRun.id}`; try { const next = await executionApi.rerun(projectId, selectedRun.id, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setAttempts((value) => [...value, next]); setSelectedAttempt(next); setActuals({}); setOutcomes({}); draftBindingRef.current = null; setNotice('已创建新的运行尝试') } } catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setPending(null); setNotice(isAbort(error) ? '重新运行请求超时，请稍后重试' : messageFor(error)) } } finally { if (finish(round, controller)) setPending(null) } }
  const createSet = async (event: FormEvent) => { event.preventDefault(); if (!setName.trim() || !canWrite(access)) return; const { round, controller } = begin(); setPending('create-set'); const name = setName.trim(); const intent = `create-set:${projectId}:${name}:${setDescription}`; try { const created = await executionApi.createSet(projectId, { name, description: setDescription }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setSets((value) => [...value, created]); setSetName(''); setSetDescription(''); setNotice('测试集已创建') } } catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setPending(null); setNotice(isAbort(error) ? '创建请求超时，请保留原意图后重试' : messageFor(error)) } } finally { if (finish(round, controller)) setPending(null) } }
  const addInstance = async (event: FormEvent) => { event.preventDefault(); if (!selectedSet || !testCaseId.trim() || !testRevisionId.trim() || !canWrite(access)) return; const { round, controller } = begin(); setPending('add-instance'); const caseId = testCaseId.trim(); const revisionId = testRevisionId.trim(); const intent = `add-instance:${projectId}:${selectedSet.id}:${caseId}:${revisionId}`; try { const created = await executionApi.addInstance(projectId, selectedSet.id, { testCaseId: caseId, testRevisionId: revisionId }, { signal: controller.signal, idempotencyKey: operationKey(intent) }); if (current(round, controller)) { completeOperation(intent); setInstances((value) => [...value, created]); setTestCaseId(''); setTestRevisionId(''); setNotice('测试实例已加入') } } catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setPending(null); setNotice(isAbort(error) ? '加入请求超时，请保留原意图后重试' : messageFor(error)) } } finally { if (finish(round, controller)) setPending(null) } }
  const loadAvailableCases = async () => { const { round, controller } = begin(); setNotice(''); try { const page = await testsApi.list(projectId, '', undefined, { signal: controller.signal }); if (current(round, controller)) { setAvailableCases(page.items); setAvailableRevisions([]); setTestCaseId(''); setTestRevisionId(''); setNotice(page.items.length ? '请选择要加入的已保存用例' : '当前项目没有可加入的手工用例') } } catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '用例列表请求超时，请稍后重试' : messageFor(error)) } } finally { finish(round, controller) } }
  const selectAvailableCase = async (id: string) => { setTestCaseId(id); setTestRevisionId(''); setAvailableRevisions([]); if (!id) return; const { round, controller } = begin(); try { const revisions = await testsApi.revisions(projectId, id, { signal: controller.signal }); if (current(round, controller)) setAvailableRevisions(revisions) } catch (error) { if (current(round, controller)) { if (isScopeAccessError(error)) { invalidateScope(); return }; setNotice(isAbort(error) ? '修订列表请求超时，请稍后重试' : messageFor(error)) } } finally { finish(round, controller) } }

  useEffect(() => { activeProjectRef.current = projectId; roundRef.current += 1; cancel(); reset(); setScopeDenied(false); if (!projectId || !can(access, 'test:read')) { setState('idle'); return } void loadSets(); return () => { roundRef.current += 1; cancel() } }, [projectId, accessKey, resetSignal])

  if (!projectId) return <section className="execution-panel" aria-labelledby="execution-heading"><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2><p className="access-empty">请选择一个项目以查看测试集。</p></section>
  if (scopeDenied || !access || !can(access, 'test:read')) return <section className="execution-panel" aria-labelledby="execution-heading"><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2><p className="access-error" role="alert">当前会话没有读取测试集和运行记录的权限。</p>{scopeDenied && <button className="secondary-button" type="button" onClick={() => { setScopeDenied(false); void loadSets() }}>重试读取</button>}</section>
  const selectedSteps = selectedAttempt?.steps ?? []
  const selectedState: ExecutionState | null = selectedAttempt?.state ?? null
  const editable = canWrite(access) && selectedState === 'RUNNING' && state === 'ready' && conflict === null
  return <section className="execution-panel" aria-labelledby="execution-heading">
    <div className="access-panel-header"><div><span className="panel-label">M09 · 测试集与手工运行</span><h2 id="execution-heading">测试集与运行</h2></div><button className="secondary-button" type="button" onClick={() => void loadSets()} disabled={state === 'loading' || pending !== null}>刷新测试集</button></div>
    {state === 'loading' && <p role="status">加载中…</p>}{state === 'error' && <div className="access-error" role="alert"><p>{notice}</p><button className="secondary-button" type="button" onClick={() => void loadSets()}>重试</button></div>}{notice && state !== 'error' && <p className="access-notice" role="status">{notice}</p>}
    {conflict && <div className="access-error" aria-live="polite"><p>运行版本已变化，草稿仍保留。请读取最新状态后确认继续编辑。</p><button className="secondary-button" type="button" onClick={rebaseConflict} disabled={pending !== null}>采用最新版本继续编辑</button></div>}
    {canWrite(access) && <form className="execution-create-form" onSubmit={createSet}><strong>创建测试集</strong><label>名称<input aria-label="测试集名称" value={setName} onChange={(event) => setSetName(event.target.value)} required disabled={pending !== null} /></label><label>说明<input aria-label="测试集说明" value={setDescription} onChange={(event) => setSetDescription(event.target.value)} disabled={pending !== null} /></label><button className="primary-button" type="submit" disabled={!setName.trim() || pending !== null}>创建测试集</button></form>}
    <div className="execution-layout"><div className="execution-set-list" role="list" aria-label="测试集列表">{sets.length === 0 && state === 'ready' && <p className="access-empty">当前项目还没有测试集。</p>}{sets.map((item) => <button type="button" className={selectedSet?.id === item.id ? 'execution-set is-selected' : 'execution-set'} key={item.id} onClick={() => void loadSet(item)} disabled={pending !== null}>{item.name}<small>{item.description || '无说明'}</small></button>)}{setsCursor && <button className="secondary-button" type="button" onClick={() => void loadMoreSets()} disabled={pending !== null}>加载更多测试集</button>}</div>
      {selectedSet && <div className="execution-detail">
        <h3>{selectedSet.name}</h3><p>{selectedSet.description || '无说明'}</p>
        {canWrite(access) && <form className="execution-add-form" onSubmit={addInstance}><strong>加入已保存用例修订</strong><label>用例 ID<input aria-label="用例 ID" value={testCaseId} onChange={(event) => setTestCaseId(event.target.value)} required disabled={pending !== null} /></label><label>修订 ID<input aria-label="修订 ID" value={testRevisionId} onChange={(event) => setTestRevisionId(event.target.value)} required disabled={pending !== null} /></label><button className="primary-button" type="submit" disabled={pending !== null}>加入实例</button></form>}
        <h4>测试实例</h4>{instances.length === 0 && <p className="access-empty">尚未加入实例。</p>}{instances.map((item) => <article className="execution-instance" key={item.id}><div><strong>{item.displayNumber ? `${item.displayNumber} · ` : ''}{item.title}</strong><small>修订 {item.revisionNumber}</small></div>{canWrite(access) && <button className="secondary-button" type="button" onClick={() => void startRun(item)} disabled={pending !== null}>启动手工运行</button>}</article>)}{instancesCursor && <button className="secondary-button" type="button" onClick={() => void loadMoreInstances()} disabled={pending !== null}>加载更多实例</button>}
        {summary && <div className="execution-summary" aria-label="运行汇总"><h4>当前测试集汇总</h4><span>实例总数：{summary.totalInstances}</span><span>未运行：{summary.unrunInstances}</span><span>活动尝试：{summary.activeAttempts}</span><span>最新完成：通过 {summary.latestCompletedPass} / 失败 {summary.latestCompletedFail} / 阻塞 {summary.latestCompletedBlocked}</span></div>}
        <h4>运行记录</h4>{runs.length === 0 && <p className="access-empty">选择实例启动后，运行记录将在此显示。</p>}{runs.map((item) => <button className="execution-run" type="button" key={item.id} onClick={() => void loadRun(item)} disabled={pending !== null}>运行 {item.id} · {item.state}</button>)}{runsCursor && <button className="secondary-button" type="button" onClick={() => void loadMoreRuns()} disabled={pending !== null}>加载更多运行</button>}
        {selectedAttempt && <div className="execution-attempt" aria-label="运行详情"><div className="execution-attempt-heading"><h4>运行详情 · 尝试 {selectedAttempt.attemptNo}</h4><span>{selectedState === 'RUNNING' ? '运行中' : selectedState === 'PAUSED' ? '已暂停' : '已完成'}{selectedAttempt.outcome ? ` · ${selectedAttempt.outcome}` : ''}</span><button className="secondary-button" type="button" onClick={() => selectedRun && void loadRun(selectedRun, { preserveDraft: true })} disabled={pending !== null}>刷新运行详情</button></div>
          {selectedSteps.map((item) => <article className="execution-step" key={item.stepKey}><strong>步骤 {item.ordinal}</strong><p>{item.action}</p><small>预期：{item.expected}</small><label>实际结果<textarea aria-label={`步骤 ${item.ordinal} 实际结果`} value={actuals[item.stepKey] ?? item.actual} onChange={(event) => { bindDraftStep(item.stepKey, item.rowVersion); setActuals((value) => ({ ...value, [item.stepKey]: event.target.value })) }} disabled={!editable || pending !== null} /></label><label>结论<select aria-label={`步骤 ${item.ordinal} 结论`} value={outcomes[item.stepKey] ?? item.outcome} onChange={(event) => { bindDraftStep(item.stepKey, item.rowVersion); setOutcomes((value) => ({ ...value, [item.stepKey]: event.target.value as StepOutcome })) }} disabled={!editable || pending !== null}><option value="NOT_RUN">未执行</option><option value="PASS">通过</option><option value="FAIL">失败</option><option value="BLOCKED">阻塞</option></select></label>{canWrite(access) && selectedState === 'RUNNING' && <button className="secondary-button" type="button" onClick={() => void saveStep(item.stepKey)} disabled={pending !== null}>保存步骤结果</button>}</article>)}
          <div className="execution-actions">{selectedState === 'RUNNING' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void attemptAction('pause')} disabled={pending !== null}>暂停</button>}{selectedState === 'PAUSED' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void attemptAction('resume')} disabled={pending !== null}>继续</button>}{selectedState === 'RUNNING' && canWrite(access) && <button className="primary-button" type="button" onClick={() => void attemptAction('finish')} disabled={pending !== null}>完成运行</button>}{selectedState === 'FINISHED' && canWrite(access) && <button className="secondary-button" type="button" onClick={() => void rerun()} disabled={pending !== null}>重新运行</button>}</div>
          {attempts.length > 0 && <div className="execution-history" aria-label="尝试历史"><h4>尝试历史</h4>{attempts.map((item) => <button type="button" className="execution-attempt-row" key={item.id} onClick={() => void selectAttempt(item)} disabled={pending !== null}>尝试 {item.attemptNo} · {item.outcome ?? item.state}</button>)}{attemptsCursor && <button className="secondary-button" type="button" onClick={() => void loadMoreAttempts()} disabled={pending !== null}>加载更多尝试</button>}</div>}
        </div>}
      </div>}
      {selectedSet && canWrite(access) && <div className="execution-selector"><button className="secondary-button" type="button" onClick={() => void loadAvailableCases()} disabled={pending !== null}>加载已保存用例</button>{availableCases.length > 0 && <><label>用例<select aria-label="已保存用例" value={testCaseId} onChange={(event) => void selectAvailableCase(event.target.value)} disabled={pending !== null}><option value="">请选择用例</option>{availableCases.map((item) => <option key={item.id} value={item.id}>{item.displayNumber} · {item.title}</option>)}</select></label><label>修订<select aria-label="已保存修订" value={testRevisionId} onChange={(event) => setTestRevisionId(event.target.value)} disabled={pending !== null || !availableRevisions.length}><option value="">请选择修订</option>{availableRevisions.map((revision) => <option key={revision.id} value={revision.id}>修订 {revision.revisionNumber} · {revision.title}</option>)}</select></label></>}</div>}
    </div>
  </section>
}
