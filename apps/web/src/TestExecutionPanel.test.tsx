import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TestExecutionPanel from './TestExecutionPanel'
import { executionApi } from './testExecution'
import { testsApi } from './tests'
import type { Run, RunAttempt, RunStep, TestInstance, TestSet } from './testExecution'

vi.mock('./testExecution', async () => {
  const actual = await vi.importActual<typeof import('./testExecution')>('./testExecution')
  return { ...actual, executionApi: { listSets: vi.fn(), createSet: vi.fn(), getSet: vi.fn(), listInstances: vi.fn(), addInstance: vi.fn(), createRun: vi.fn(), listRuns: vi.fn(), getRun: vi.fn(), listAttempts: vi.fn(), getAttempt: vi.fn(), saveStep: vi.fn(), pause: vi.fn(), resume: vi.fn(), finish: vi.fn(), rerun: vi.fn() } }
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

afterEach(() => { cleanup(); vi.clearAllMocks() })

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

  it('chooses a saved manual case and revision from real API lists instead of requiring UUID entry', async () => {
    vi.mocked(executionApi.listSets).mockResolvedValue([set]); vi.mocked(executionApi.getSet).mockResolvedValue(set); vi.mocked(executionApi.listInstances).mockResolvedValue([]); vi.mocked(executionApi.listRuns).mockResolvedValue([])
    vi.mocked(testsApi.list).mockResolvedValue({ items: [{ id: 'test-1', projectId: 'project-1', displayNumber: 'TC-1', testType: 'MANUAL', rowVersion: 1, currentRevisionId: 'rev-1', revisionNumber: 1, title: '登录', description: '', preconditions: '', steps: [], createdAt: '2026-01-01T00:00:00Z', createdBy: 'principal-1' }], nextCursor: null })
    vi.mocked(testsApi.revisions).mockResolvedValue([{ id: 'rev-1', revisionNumber: 1, title: '登录', description: '', preconditions: '', createdAt: '2026-01-01T00:00:00Z', createdBy: 'principal-1', steps: [] }])
    render(<TestExecutionPanel projectId="project-1" access={access} />); fireEvent.click(await screen.findByRole('button', { name: /登录冒烟/ })); fireEvent.click(await screen.findByRole('button', { name: '加载已保存用例' })); expect(await screen.findByRole('option', { name: /TC-1 · 登录/ })).toBeVisible(); fireEvent.change(screen.getByLabelText('已保存用例'), { target: { value: 'test-1' } }); expect(await screen.findByRole('option', { name: /修订 1 · 登录/ })).toBeVisible()
  })
})
