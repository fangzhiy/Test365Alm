import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TestExecutionPanel from './TestExecutionPanel'
import { executionApi } from './testExecution'
import { testsApi } from './tests'
import type { Run, RunAttempt, RunStep, TestInstance, TestSet } from './testExecution'

vi.mock('./testExecution', async () => {
  const actual = await vi.importActual<typeof import('./testExecution')>('./testExecution')
  return { ...actual, executionApi: { listSets: vi.fn(), pageSets: vi.fn(), createSet: vi.fn(), getSet: vi.fn(), listInstances: vi.fn(), pageInstances: vi.fn(), addInstance: vi.fn(), createRun: vi.fn(), listRuns: vi.fn(), pageRuns: vi.fn(), summary: vi.fn(), getRun: vi.fn(), listAttempts: vi.fn(), pageAttempts: vi.fn(), getAttempt: vi.fn(), saveStep: vi.fn(), pause: vi.fn(), resume: vi.fn(), finish: vi.fn(), rerun: vi.fn() } }
})
vi.mock('./tests', async () => {
  const actual = await vi.importActual<typeof import('./tests')>('./tests')
  return { ...actual, testsApi: { ...actual.testsApi, list: vi.fn(), revisions: vi.fn() } }
})

const access = { tenantId: 'tenant-1', projectId: 'project-1', principalId: 'principal-1', roles: ['PROJECT_MEMBER'] as const, permissions: ['test:read', 'test:create', 'test:update'] }
const viewer = { ...access, roles: ['PROJECT_VIEWER'] as const, permissions: ['test:read'] as const }
const set: TestSet = { id: 'set-1', projectId: 'project-1', name: '登录冒烟', description: '核心登录流' }
const instance: TestInstance = { id: 'instance-1', projectId: 'project-1', setId: 'set-1', testCaseId: 'test-1', testRevisionId: 'rev-1', title: '登录', revisionNumber: 1, displayNumber: 'TC-1' }
const step: RunStep = { stepKey: 'step-1', ordinal: 1, action: '打开登录页', expected: '显示登录表单', actual: '', outcome: 'NOT_RUN', rowVersion: 7 }
const attempt: RunAttempt = { id: 'attempt-1', attemptNo: 1, state: 'RUNNING', outcome: null, rowVersion: 42, steps: [step] }
const run: Run = { id: 'run-1', projectId: 'project-1', instanceId: instance.id, manifestId: 'manifest-1', attemptId: attempt.id, state: 'RUNNING', outcome: null, rowVersion: 1, attempt }

afterEach(() => { cleanup(); vi.resetAllMocks() })

describe('TestExecutionPanel', () => {
  it('loads test sets, instances and starts a manual run', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt])
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('登录冒烟')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /登录冒烟/ }))
    expect(await screen.findByText('TC-1 · 登录')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '启动手工运行' }))
    expect(await screen.findByText('运行中')).toBeVisible()
    expect(executionApi.createRun).toHaveBeenCalledWith('project-1', { instanceId: 'instance-1', mode: 'MANUAL' }, expect.objectContaining({ signal: expect.any(AbortSignal), idempotencyKey: expect.any(String) }))
  })

  it('saves a step result, pauses, resumes and finishes an attempt', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockResolvedValue({ ...attempt, steps: [{ ...step, actual: '表单显示', outcome: 'PASS' }] }); vi.mocked(executionApi.pause).mockResolvedValue({ ...attempt, state: 'PAUSED' }); vi.mocked(executionApi.resume).mockResolvedValue(attempt); vi.mocked(executionApi.finish).mockResolvedValue({ ...attempt, state: 'FINISHED', outcome: 'PASS', steps: [{ ...step, actual: '表单显示', outcome: 'PASS' }] })
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' })); await screen.findByText('运行中')
    fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '表单显示' } }); fireEvent.change(screen.getByLabelText('步骤 1 结论'), { target: { value: 'PASS' } }); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalled()); expect(vi.mocked(executionApi.saveStep).mock.calls[0]?.[4]).toEqual(expect.objectContaining({ rowVersion: 7 })); fireEvent.click(screen.getByRole('button', { name: '暂停' })); await waitFor(() => expect(executionApi.pause).toHaveBeenCalledWith('project-1', 'run-1', 'attempt-1', 42, expect.anything())); fireEvent.click(screen.getByRole('button', { name: '继续' })); await waitFor(() => expect(executionApi.resume).toHaveBeenCalled()); fireEvent.click(screen.getByRole('button', { name: '完成运行' })); await waitFor(() => expect(executionApi.finish).toHaveBeenCalled())
  })

  it('blocks a step write when the target step has no row version', async () => {
    const unversioned = { ...step, rowVersion: undefined }
    const unversionedAttempt = { ...attempt, steps: [unversioned] }
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run]); vi.mocked(executionApi.getRun).mockResolvedValue({ ...run, attempt: unversionedAttempt }); vi.mocked(executionApi.listAttempts).mockResolvedValue([unversionedAttempt])
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ })); expect(await screen.findByText('运行中')).toBeVisible(); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); expect(executionApi.saveStep).not.toHaveBeenCalled(); expect(await screen.findByRole('status')).toHaveTextContent('步骤版本缺失')
  })

  it('submits the values displayed from the saved step when the user does not edit them', async () => {
    const storedStep = { ...step, actual: '已有结果', outcome: 'PASS' as const }
    const storedAttempt = { ...attempt, steps: [storedStep] }
    const storedRun = { ...run, attempt: storedAttempt }
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(storedRun); vi.mocked(executionApi.getRun).mockResolvedValue(storedRun); vi.mocked(executionApi.listAttempts).mockResolvedValue([storedAttempt]); vi.mocked(executionApi.saveStep).mockResolvedValue(storedAttempt)
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' })); await screen.findByText('运行中'); expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('已有结果'); expect(screen.getByLabelText('步骤 1 结论')).toHaveValue('PASS'); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalled()); expect(vi.mocked(executionApi.saveStep).mock.calls[0]?.[4]).toEqual(expect.objectContaining({ actual: '已有结果', outcome: 'PASS', rowVersion: 7 }))
  })

  it('keeps a step draft and the original version after a stale-version response', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockRejectedValue({ code: 'STALE_VERSION', message: '步骤版本已过期' })
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' }))
    await screen.findByText('运行中')
    fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '本地未提交结果' } })
    fireEvent.change(screen.getByLabelText('步骤 1 结论'), { target: { value: 'FAIL' } })
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('步骤版本已过期'))
    expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('本地未提交结果')
    expect(screen.getByLabelText('步骤 1 结论')).toHaveValue('FAIL')
    expect(screen.getByRole('button', { name: '保存步骤结果' })).toBeEnabled()
    expect(vi.mocked(executionApi.saveStep).mock.calls[0]?.[4]?.rowVersion).toBe(7)
  })

  it('keeps a conflicted draft and saved result visible when refreshing run details fails', async () => {
    let getRunCalls = 0
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run]);
    vi.mocked(executionApi.getRun).mockImplementation(async () => { getRunCalls += 1; if (getRunCalls > 1) throw { code: 'HTTP_503', message: '运行详情暂时不可用' }; return run })
    vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockRejectedValue({ code: 'STALE_VERSION', message: '步骤版本已过期' })
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ }))
    await screen.findByText('运行中')
    fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '冲突后仍保留' } })
    fireEvent.change(screen.getByLabelText('步骤 1 结论'), { target: { value: 'FAIL' } })
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('步骤版本已过期'))
    fireEvent.click(screen.getByRole('button', { name: '刷新运行详情' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('运行详情暂时不可用'))
    expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('冲突后仍保留')
    expect(screen.getByLabelText('步骤 1 结论')).toHaveValue('FAIL')
    expect(screen.getByRole('button', { name: '刷新运行详情' })).toBeEnabled()
  })

  it('keeps a successful step result visible when the follow-up detail read fails', async () => {
    let getRunCalls = 0
    const saved = { ...attempt, steps: [{ ...step, actual: '服务器已保存', outcome: 'PASS' as const, rowVersion: 8 }] }
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run]);
    vi.mocked(executionApi.getRun).mockImplementation(async () => { getRunCalls += 1; if (getRunCalls > 1) throw { code: 'HTTP_503', message: '运行详情暂时不可用' }; return run })
    vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockResolvedValue(saved)
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ }))
    await screen.findByText('运行中')
    fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '服务器已保存' } })
    fireEvent.change(screen.getByLabelText('步骤 1 结论'), { target: { value: 'PASS' } })
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('步骤结果已保存'))
    fireEvent.click(screen.getByRole('button', { name: '刷新运行详情' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('运行详情暂时不可用'))
    expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('服务器已保存')
    expect(screen.getByLabelText('步骤 1 结论')).toHaveValue('PASS')
  })

  it('clears run data after an API access denial and allows a fresh read retry', async () => {
    let getRunCalls = 0
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run]);
    vi.mocked(executionApi.getRun).mockImplementation(async () => { getRunCalls += 1; if (getRunCalls > 1) throw { code: 'FORBIDDEN', status: 403, message: '项目访问已撤销' }; return run })
    vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt])
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ }))
    await screen.findByText('运行中')
    fireEvent.click(screen.getByRole('button', { name: '刷新运行详情' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取测试集和运行记录的权限')
    expect(screen.queryByText('运行详情 · 尝试 1')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试读取' }))
    expect(await screen.findByText('登录冒烟')).toBeVisible()
    expect(screen.queryByText('运行详情 · 尝试 1')).not.toBeInTheDocument()
  })

  it('disables step controls during a save and re-enables them after the result arrives', async () => {
    let resolveSave!: (value: RunAttempt) => void
    const deferred = new Promise<RunAttempt>((resolve) => { resolveSave = resolve })
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockReturnValue(deferred)
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' })); await screen.findByText('运行中'); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(screen.getByRole('button', { name: '保存步骤结果' })).toBeDisabled()); resolveSave({ ...attempt, steps: [{ ...step, rowVersion: 8 }] }); await waitFor(() => expect(screen.getByRole('button', { name: '保存步骤结果' })).toBeEnabled())
  })

  it('reuses the same step idempotency key when an interrupted write is retried', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockRejectedValueOnce({ name: 'AbortError', message: 'timeout' }).mockResolvedValueOnce(attempt)
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' })); await screen.findByText('运行中'); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(screen.getByRole('button', { name: '保存步骤结果' })).toBeEnabled()); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalledTimes(2)); const firstOptions = vi.mocked(executionApi.saveStep).mock.calls[0]?.[5]; const secondOptions = vi.mocked(executionApi.saveStep).mock.calls[1]?.[5]; expect(firstOptions?.idempotencyKey).toBeTruthy(); expect(secondOptions?.idempotencyKey).toBe(firstOptions?.idempotencyKey)
  })

  it('does not let a late run response overwrite the newer selected run', async () => {
    let resolveFirstRun!: (value: Run) => void
    let resolveFirstAttempts!: (value: RunAttempt[]) => void
    const firstRun = new Promise<Run>((resolve) => { resolveFirstRun = resolve })
    const firstAttempts = new Promise<RunAttempt[]>((resolve) => { resolveFirstAttempts = resolve })
    const secondAttempt: RunAttempt = { ...attempt, id: 'attempt-2', attemptNo: 2 }
    const secondRun: Run = { ...run, id: 'run-2', attemptId: secondAttempt.id, attempt: secondAttempt }
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run, secondRun]); vi.mocked(executionApi.getRun).mockImplementationOnce(() => firstRun).mockResolvedValueOnce(secondRun); vi.mocked(executionApi.listAttempts).mockImplementationOnce(() => firstAttempts).mockResolvedValueOnce([secondAttempt])
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ })); fireEvent.click(await screen.findByRole('button', { name: /运行 run-2/ })); expect(await screen.findByText('运行详情 · 尝试 2')).toBeVisible(); resolveFirstRun(run); resolveFirstAttempts([attempt]); await Promise.resolve(); expect(screen.getByText('运行详情 · 尝试 2')).toBeVisible(); expect(screen.queryByText('运行详情 · 尝试 1')).not.toBeInTheDocument()
  })

  it('keeps viewer read-only and shows attempt history', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.listRuns).mockResolvedValue([run]); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt, { ...attempt, id: 'attempt-2', attemptNo: 2, state: 'FINISHED', outcome: 'PASS' }])
    render(<TestExecutionPanel projectId="project-1" access={viewer} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); await screen.findByText('TC-1 · 登录'); expect(screen.queryByRole('button', { name: '启动手工运行' })).not.toBeInTheDocument(); fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ })); expect(await screen.findByText('尝试 2 · PASS')).toBeVisible(); expect(screen.queryByRole('button', { name: '完成运行' })).not.toBeInTheDocument()
  })

  it('shows a retryable error when set loading fails', async () => { vi.mocked(executionApi.listSets).mockRejectedValue(new Error('服务不可用')); render(<TestExecutionPanel projectId="project-1" access={access} />); expect(await screen.findByRole('alert')).toHaveTextContent('服务不可用'); expect(screen.getByRole('button', { name: '重试' })).toBeEnabled() })

  it('aborts a stalled read at the bounded timeout and leaves the panel retryable', async () => {
    vi.useFakeTimers()
    try {
      let resolveSets!: (value: TestSet[]) => void
      const stalled = new Promise<TestSet[]>((resolve) => { resolveSets = resolve })
      vi.mocked(executionApi.listSets).mockImplementation((_projectId, options) => {
        options?.signal?.addEventListener('abort', () => undefined)
        return stalled
      })
      render(<TestExecutionPanel projectId="project-1" access={access} />)
      await act(async () => { await Promise.resolve(); await vi.advanceTimersByTimeAsync(5000); await Promise.resolve() })
      expect(screen.getByRole('alert')).toHaveTextContent('测试集请求超时')
      expect(screen.getByRole('button', { name: '重试' })).toBeEnabled()
      resolveSets([set])
      await act(async () => { await Promise.resolve() })
      expect(screen.queryByText('登录冒烟')).not.toBeInTheDocument()
    } finally { vi.useRealTimers() }
  })

  it('cancels both StrictMode probe and active requests when unmounted', async () => {
    let resolveSets!: (value: TestSet[]) => void
    const stalled = new Promise<TestSet[]>((resolve) => { resolveSets = resolve })
    vi.mocked(executionApi.listSets).mockImplementation((_projectId, options) => {
      options?.signal?.addEventListener('abort', () => undefined)
      return stalled
    })
    const view = render(<StrictMode><TestExecutionPanel projectId="project-1" access={access} /></StrictMode>)
    await waitFor(() => expect(executionApi.listSets).toHaveBeenCalledTimes(2))
    const signals = vi.mocked(executionApi.listSets).mock.calls.map((call) => call[1]?.signal)
    expect(signals[0]?.aborted).toBe(true)
    expect(signals[1]?.aborted).toBe(false)
    view.unmount()
    expect(signals[1]?.aborted).toBe(true)
    resolveSets([set])
    await act(async () => { await Promise.resolve() })
  })

  it('chooses a saved manual case and revision from real API lists instead of requiring UUID entry', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([]); vi.mocked(executionApi.listRuns).mockResolvedValue([])
    vi.mocked(testsApi.list).mockResolvedValue({ items: [{ id: 'test-1', projectId: 'project-1', displayNumber: 'TC-1', testType: 'MANUAL', rowVersion: 1, currentRevisionId: 'rev-1', revisionNumber: 1, title: '登录', description: '', preconditions: '', steps: [], createdAt: '2026-01-01T00:00:00Z', createdBy: 'principal-1' }], nextCursor: null })
    vi.mocked(testsApi.revisions).mockResolvedValue([{ id: 'rev-1', revisionNumber: 1, title: '登录', description: '', preconditions: '', createdAt: '2026-01-01T00:00:00Z', createdBy: 'principal-1', steps: [] }])
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '加载已保存用例' })); expect(await screen.findByRole('option', { name: /TC-1 · 登录/ })).toBeVisible(); fireEvent.change(screen.getByLabelText('已保存用例'), { target: { value: 'test-1' } }); expect(await screen.findByRole('option', { name: /修订 1 · 登录/ })).toBeVisible()
  })

  it('keeps two-step results and version progression through pause, resume, finish and rerun', async () => {
    const secondStep: RunStep = { stepKey: 'step-2', ordinal: 2, action: '提交凭据', expected: '显示工作台', actual: '', outcome: 'NOT_RUN', rowVersion: 11 }
    const twoStepAttempt: RunAttempt = { ...attempt, steps: [step, secondStep] }
    const twoStepRun: Run = { ...run, attempt: twoStepAttempt }
    const finished: RunAttempt = { ...twoStepAttempt, state: 'FINISHED', outcome: 'FAIL', steps: [
      { ...step, actual: '表单可见', outcome: 'PASS', rowVersion: 8 },
      { ...secondStep, actual: '提交后错误', outcome: 'FAIL', rowVersion: 12 },
    ] }
    const retry: RunAttempt = { ...twoStepAttempt, id: 'attempt-2', attemptNo: 2, rowVersion: 43 }
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(twoStepRun); vi.mocked(executionApi.getRun).mockResolvedValue(twoStepRun); vi.mocked(executionApi.listAttempts).mockResolvedValue([twoStepAttempt])
    const savedRunning: RunAttempt = { ...twoStepAttempt, steps: [{ ...step, actual: '表单可见', outcome: 'PASS', rowVersion: 8 }, { ...secondStep, actual: '提交后错误', outcome: 'FAIL', rowVersion: 12 }] }
    vi.mocked(executionApi.saveStep)
      .mockResolvedValueOnce({ ...savedRunning, steps: [savedRunning.steps[0], secondStep] })
      .mockResolvedValueOnce(savedRunning)
    vi.mocked(executionApi.pause).mockResolvedValue({ ...twoStepAttempt, state: 'PAUSED', rowVersion: 43 })
    vi.mocked(executionApi.resume).mockResolvedValue({ ...twoStepAttempt, rowVersion: 44 })
    vi.mocked(executionApi.finish).mockResolvedValue(finished)
    vi.mocked(executionApi.rerun).mockResolvedValue(retry)

    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' }))
    expect(await screen.findByLabelText('步骤 2 实际结果')).toBeVisible()
    fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '表单可见' } })
    fireEvent.change(screen.getByLabelText('步骤 1 结论'), { target: { value: 'PASS' } })
    fireEvent.click(screen.getAllByRole('button', { name: '保存步骤结果' })[0])
    await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByLabelText('步骤 2 实际结果'), { target: { value: '提交后错误' } })
    fireEvent.change(screen.getByLabelText('步骤 2 结论'), { target: { value: 'FAIL' } })
    fireEvent.click(screen.getAllByRole('button', { name: '保存步骤结果' })[1])
    await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalledTimes(2))
    expect(vi.mocked(executionApi.saveStep).mock.calls.map((call) => call[4]?.rowVersion)).toEqual([7, 11])
    fireEvent.click(screen.getByRole('button', { name: '暂停' }))
    await waitFor(() => expect(executionApi.pause).toHaveBeenCalledWith('project-1', 'run-1', 'attempt-1', 42, expect.anything()))
    fireEvent.click(screen.getByRole('button', { name: '继续' }))
    await waitFor(() => expect(executionApi.resume).toHaveBeenCalledWith('project-1', 'run-1', 'attempt-1', 43, expect.anything()))
    fireEvent.click(screen.getByRole('button', { name: '完成运行' }))
    await waitFor(() => expect(screen.getByText('已完成 · FAIL')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: '重新运行' }))
    await waitFor(() => expect(screen.getByText('运行详情 · 尝试 2')).toBeVisible())
    expect(screen.getByRole('button', { name: '尝试 1 · FAIL' })).toBeVisible()
    expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('')
    expect(screen.getByLabelText('步骤 2 实际结果')).toHaveValue('')
  })

  it('uses bounded page cursors and a set-scoped summary in the workbench', async () => {
    const secondSet: TestSet = { ...set, id: 'set-2', name: '回归集合' }
    const secondInstance: TestInstance = { ...instance, id: 'instance-2', title: '回归' }
    vi.mocked(executionApi.pageSets).mockResolvedValueOnce({ items: [set], nextCursor: 'sets-next' }).mockResolvedValueOnce({ items: [secondSet], nextCursor: null })
    vi.mocked(executionApi.getSet).mockResolvedValue(set)
    vi.mocked(executionApi.pageInstances).mockResolvedValueOnce({ items: [instance], nextCursor: 'instances-next' }).mockResolvedValueOnce({ items: [secondInstance], nextCursor: null })
    vi.mocked(executionApi.pageRuns).mockResolvedValueOnce({ items: [run], nextCursor: 'runs-next' }).mockResolvedValueOnce({ items: [], nextCursor: null })
    vi.mocked(executionApi.summary).mockResolvedValue({ totalInstances: 1, unrunInstances: 0, activeAttempts: 1, latestCompletedPass: 0, latestCompletedFail: 0, latestCompletedBlocked: 0 })
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('登录冒烟')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '加载更多测试集' }))
    expect(await screen.findByText('回归集合')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /登录冒烟/ }))
    expect(await screen.findByText('当前测试集汇总')).toBeVisible()
    expect(executionApi.pageRuns).toHaveBeenCalledWith('project-1', { setId: 'set-1' }, null, 25, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    fireEvent.click(screen.getByRole('button', { name: '加载更多实例' }))
    await waitFor(() => expect(executionApi.pageInstances).toHaveBeenCalledWith('project-1', 'set-1', 'instances-next', 25, expect.objectContaining({ signal: expect.any(AbortSignal) })))
    fireEvent.click(screen.getByRole('button', { name: '加载更多运行' }))
    await waitFor(() => expect(executionApi.pageRuns).toHaveBeenCalledWith('project-1', { setId: 'set-1' }, 'runs-next', 25, expect.objectContaining({ signal: expect.any(AbortSignal) })))
  })

  it('cancels direct access loss and ignores a late set response, then reloads after access recovery', async () => {
    let resolveSets!: (value: TestSet[]) => void
    const deferred = new Promise<TestSet[]>((resolve) => { resolveSets = resolve })
    vi.mocked(executionApi.listSets).mockReturnValueOnce(deferred).mockResolvedValueOnce([set])
    const view = render(<TestExecutionPanel projectId="project-1" access={access} />)
    await waitFor(() => expect(executionApi.listSets).toHaveBeenCalledTimes(1))
    const firstSignal = vi.mocked(executionApi.listSets).mock.calls[0]?.[1]?.signal
    expect(firstSignal?.aborted).toBe(false)
    view.rerender(<TestExecutionPanel projectId="project-1" access={null} />)
    await waitFor(() => expect(firstSignal?.aborted).toBe(true))
    resolveSets([set])
    await Promise.resolve()
    expect(screen.getByRole('alert')).toHaveTextContent('没有读取测试集')
    expect(screen.queryByText('登录冒烟')).not.toBeInTheDocument()
    view.rerender(<TestExecutionPanel projectId="project-1" access={access} />)
    expect(await screen.findByText('登录冒烟')).toBeVisible()
    expect(executionApi.listSets).toHaveBeenCalledTimes(2)
  })

  it('invalidates a pending step write when the same project loses write access directly', async () => {
    let resolveSave!: (value: RunAttempt) => void
    const pendingSave = new Promise<RunAttempt>((resolve) => { resolveSave = resolve })
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockReturnValue(pendingSave)
    const view = render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' }))
    await screen.findByText('运行中')
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '刷新测试集' })).toBeDisabled())
    const writeSignal = vi.mocked(executionApi.saveStep).mock.calls[0]?.[5]?.signal
    expect(writeSignal?.aborted).toBe(false)
    view.rerender(<TestExecutionPanel projectId="project-1" access={{ ...access, permissions: ['test:read'] }} />)
    await waitFor(() => expect(writeSignal?.aborted).toBe(true))
    resolveSave({ ...attempt, steps: [{ ...step, actual: 'late success', outcome: 'PASS', rowVersion: 8 }] })
    await Promise.resolve()
    expect(screen.queryByRole('button', { name: '保存步骤结果' })).not.toBeInTheDocument()
    expect(screen.queryByText('late success')).not.toBeInTheDocument()
  })

  it('clears the scope when a real step write returns a forbidden response', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockRejectedValue({ code: 'FORBIDDEN', status: 403, message: '项目访问已撤销' })
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' }))
    await screen.findByText('运行中')
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取测试集和运行记录的权限')
    expect(screen.queryByText('运行详情 · 尝试 1')).not.toBeInTheDocument()
  })

  it('locks case discovery while a step write is pending instead of aborting an unknown result', async () => {
    let resolveSave!: (value: RunAttempt) => void
    const pendingSave = new Promise<RunAttempt>((resolve) => { resolveSave = resolve })
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([instance]); vi.mocked(executionApi.createRun).mockResolvedValue(run); vi.mocked(executionApi.getRun).mockResolvedValue(run); vi.mocked(executionApi.listAttempts).mockResolvedValue([attempt]); vi.mocked(executionApi.saveStep).mockReturnValue(pendingSave)
    render(<TestExecutionPanel projectId="project-1" access={access} />)
    fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ }))
    fireEvent.click(await screen.findByRole('button', { name: '启动手工运行' }))
    await screen.findByText('运行中')
    fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '加载已保存用例' })).toBeDisabled())
    expect(testsApi.list).not.toHaveBeenCalled()
    resolveSave(attempt)
    await waitFor(() => expect(screen.getByRole('button', { name: '加载已保存用例' })).toBeEnabled())
  })

  it('loads bounded pages and the selected test-set summary through the page API', async () => {
    const secondSet: TestSet = { ...set, id: 'set-2', name: '回归集' }
    vi.mocked(executionApi.pageSets).mockResolvedValue({ items: [set], nextCursor: 'set-next' })
    vi.mocked(executionApi.pageInstances).mockResolvedValue({ items: [instance], nextCursor: 'instance-next' })
    vi.mocked(executionApi.pageRuns).mockResolvedValue({ items: [run], nextCursor: 'run-next' })
    vi.mocked(executionApi.summary).mockResolvedValue({ totalInstances: 4, unrunInstances: 1, activeAttempts: 1, latestCompletedPass: 1, latestCompletedFail: 1, latestCompletedBlocked: 1 })
    vi.mocked(executionApi.pageSets).mockResolvedValueOnce({ items: [set], nextCursor: 'set-next' }).mockResolvedValueOnce({ items: [secondSet], nextCursor: null })
    vi.mocked(executionApi.pageInstances).mockResolvedValue({ items: [instance], nextCursor: null })
    vi.mocked(executionApi.pageRuns).mockResolvedValue({ items: [run], nextCursor: null })
    vi.mocked(executionApi.getSet).mockResolvedValue(set)
    render(<TestExecutionPanel projectId="project-1" access={viewer} />)
    expect(await screen.findByText('登录冒烟')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '加载更多测试集' }))
    expect(await screen.findByText('回归集')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /登录冒烟/ }))
    await waitFor(() => expect(executionApi.pageInstances).toHaveBeenCalledWith('project-1', 'set-1', null, 25, expect.anything()))
    expect(executionApi.pageRuns).toHaveBeenCalledWith('project-1', { setId: 'set-1' }, null, 25, expect.anything())
    expect(executionApi.summary).toHaveBeenCalledWith('project-1', { setId: 'set-1' }, expect.anything())
    expect(await screen.findByText('实例总数：4')).toBeVisible()
  })

  it('continues an instance page without downloading an unbounded list', async () => {
    const secondInstance: TestInstance = { ...instance, id: 'instance-2', title: '支付' }
    vi.mocked(executionApi.pageSets).mockResolvedValue({ items: [set], nextCursor: null })
    vi.mocked(executionApi.pageInstances).mockResolvedValueOnce({ items: [instance], nextCursor: 'instance-next' }).mockResolvedValueOnce({ items: [secondInstance], nextCursor: null })
    vi.mocked(executionApi.pageRuns).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(executionApi.summary).mockResolvedValue({ totalInstances: 2, unrunInstances: 2, activeAttempts: 0, latestCompletedPass: 0, latestCompletedFail: 0, latestCompletedBlocked: 0 }); vi.mocked(executionApi.getSet).mockResolvedValue(set)
    render(<TestExecutionPanel projectId="project-1" access={viewer} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); expect(await screen.findByText('TC-1 · 登录')).toBeVisible(); fireEvent.click(screen.getByRole('button', { name: '加载更多实例' })); await waitFor(() => expect(executionApi.pageInstances).toHaveBeenLastCalledWith('project-1', 'set-1', 'instance-next', 25, expect.anything())); expect(screen.getAllByRole('article')[1]).toHaveTextContent('支付')
  })

  it('reads a selected history attempt from its detail endpoint instead of the cached page', async () => {
    const historyAttempt: RunAttempt = { ...attempt, id: 'attempt-2', attemptNo: 2, state: 'FINISHED', outcome: 'PASS' }
    vi.mocked(executionApi.pageSets).mockResolvedValue({ items: [set], nextCursor: null }); vi.mocked(executionApi.pageInstances).mockResolvedValue({ items: [instance], nextCursor: null }); vi.mocked(executionApi.pageRuns).mockResolvedValue({ items: [run], nextCursor: null }); vi.mocked(executionApi.summary).mockResolvedValue({ totalInstances: 1, unrunInstances: 0, activeAttempts: 0, latestCompletedPass: 1, latestCompletedFail: 0, latestCompletedBlocked: 0 }); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.getRun).mockResolvedValue({ ...run, attempt }); vi.mocked(executionApi.pageAttempts).mockResolvedValue({ items: [attempt, historyAttempt], nextCursor: null }); vi.mocked(executionApi.getAttempt).mockResolvedValue(historyAttempt)
    render(<TestExecutionPanel projectId="project-1" access={viewer} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ })); await screen.findByText('运行详情 · 尝试 1'); fireEvent.click(screen.getByRole('button', { name: '尝试 2 · PASS' })); await waitFor(() => expect(executionApi.getAttempt).toHaveBeenCalledWith('project-1', 'run-1', 'attempt-2', expect.anything())); expect(await screen.findByText('运行详情 · 尝试 2')).toBeVisible()
  })

  it('keeps a stale draft on refresh and only sends the new version after explicit conflict resolution', async () => {
    const latestStep: RunStep = { ...step, rowVersion: 8, actual: '服务器版本', outcome: 'PASS' }
    const latestAttempt: RunAttempt = { ...attempt, steps: [latestStep], rowVersion: 43 }
    const latestRun: Run = { ...run, attempt: latestAttempt }
    vi.mocked(executionApi.pageSets).mockResolvedValue({ items: [set], nextCursor: null }); vi.mocked(executionApi.pageInstances).mockResolvedValue({ items: [instance], nextCursor: null }); vi.mocked(executionApi.pageRuns).mockResolvedValue({ items: [run], nextCursor: null }); vi.mocked(executionApi.summary).mockResolvedValue({ totalInstances: 1, unrunInstances: 0, activeAttempts: 1, latestCompletedPass: 0, latestCompletedFail: 0, latestCompletedBlocked: 0 }); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.getRun).mockResolvedValueOnce(run).mockResolvedValueOnce(latestRun); vi.mocked(executionApi.pageAttempts).mockResolvedValue({ items: [attempt], nextCursor: null }); vi.mocked(executionApi.getAttempt).mockResolvedValue(latestAttempt); vi.mocked(executionApi.saveStep).mockRejectedValueOnce({ code: 'HTTP_412', status: 412, message: '步骤版本已过期' }).mockResolvedValueOnce(latestAttempt)
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: /运行 run-1/ })); await screen.findByText('运行详情 · 尝试 1'); fireEvent.change(screen.getByLabelText('步骤 1 实际结果'), { target: { value: '草稿 A' } }); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await screen.findByText('步骤版本已过期'); fireEvent.click(screen.getByRole('button', { name: '刷新运行详情' })); await screen.findByText('运行版本已变化，草稿仍保留。请读取最新状态后确认继续编辑。'); expect(screen.getByLabelText('步骤 1 实际结果')).toHaveValue('草稿 A'); fireEvent.click(screen.getByRole('button', { name: '采用最新版本继续编辑' })); fireEvent.click(screen.getByRole('button', { name: '保存步骤结果' })); await waitFor(() => expect(executionApi.saveStep).toHaveBeenCalledTimes(2)); expect(vi.mocked(executionApi.saveStep).mock.calls[1]?.[4]).toEqual(expect.objectContaining({ rowVersion: 8 }))
  })
})
