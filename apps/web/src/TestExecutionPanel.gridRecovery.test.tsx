import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import TestExecutionPanel from './TestExecutionPanel'
import { executionApi, type TestInstance } from './testExecution'

vi.mock('./testExecution', async () => ({
 ...await vi.importActual<typeof import('./testExecution')>('./testExecution'),
 executionApi: { pageSets:vi.fn(), getSet:vi.fn(), executionGrid:vi.fn(), pageRuns:vi.fn(), summary:vi.fn() },
}))
vi.mock('./tests',()=>({testsApi:{revision:async()=>({id:'rev1',revisionNumber:1,title:'冻结用例',description:'冻结原修订',steps:[]})}}))
vi.mock('./FolderTree',()=>({default:()=>null}))
vi.mock('./ReviewPanel',()=>({default:()=>null}))
vi.mock('./SignaturePanel',()=>({default:()=>null}))
vi.mock('./HistoryPanel',()=>({default:()=>null}))

afterEach(()=>{cleanup();vi.useRealTimers();vi.resetAllMocks();vi.unstubAllGlobals()})
it('a timed out grid cannot regain old report eligibility through set-list retry, only a verified grid restores it',async()=>{
 const access={tenantId:'t',projectId:'p',principalId:'u',roles:['PROJECT_VIEWER'] as const,permissions:['test:read']}
 const set={id:'s',projectId:'p',name:'恢复网格',description:''}
 const a1:TestInstance={id:'i',projectId:'p',setId:'s',testCaseId:'c',testRevisionId:'rev1',title:'冻结用例',revisionNumber:1,runId:'r1',attemptId:'a1',status:'FINISHED',conclusion:'FAIL'}
 const a2={...a1,runId:'r2',attemptId:'a2',status:'RUNNING' as const,conclusion:null}
 vi.mocked(executionApi.pageSets).mockResolvedValue({items:[set],nextCursor:null})
 vi.mocked(executionApi.getSet).mockResolvedValue(set)
 vi.mocked(executionApi.pageRuns).mockResolvedValue({items:[],nextCursor:null})
 vi.mocked(executionApi.executionGrid).mockResolvedValue({items:[a1],nextCursor:null})
 const request=vi.fn().mockResolvedValue(Response.json({code:'SERVICE_UNAVAILABLE'},{status:503}))
 vi.stubGlobal('fetch',request)
 render(<TestExecutionPanel projectId="p" access={access}/> )
 fireEvent.click(await screen.findByRole('button',{name:/恢复网格/}))
 fireEvent.click(await screen.findByRole('button',{name:'查看绑定修订 冻结用例'}))
 const nav=()=>within(screen.getByRole('region',{name:'实例修订与闭环导航'}))
 await screen.findByText('冻结原修订')
 expect(nav().getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).toBeVisible()
 let late!:(v:{items:TestInstance[];nextCursor:null})=>void
 vi.mocked(executionApi.executionGrid).mockImplementationOnce(()=>new Promise(r=>{late=r}))
 vi.useFakeTimers()
 fireEvent.click(screen.getByRole('button',{name:'刷新实例网格'}))
 const signal=vi.mocked(executionApi.executionGrid).mock.calls.at(-1)![5]!.signal
 // Controlled timer reproduces the real host timeout; no wall-clock sleep.
 await act(async()=>{vi.advanceTimersByTime(5000)})
 vi.useRealTimers()
 expect(signal?.aborted).toBe(true)
 fireEvent.click(screen.getByRole('button',{name:'重试'}))
 await screen.findByRole('button',{name:/恢复网格/})
 expect(executionApi.executionGrid).toHaveBeenCalledTimes(2)
 expect(screen.queryByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).not.toBeInTheDocument()
 await act(async()=>late({items:[a1],nextCursor:null}))
 expect(screen.queryByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).not.toBeInTheDocument()
 for(const status of ['RUNNING','PAUSED','FINISHED'] as const){
  vi.mocked(executionApi.executionGrid).mockResolvedValue({items:[{...a2,status,conclusion:status==='FINISHED'?'PASS':null}],nextCursor:null})
  fireEvent.click(screen.getByRole('button',{name:'刷新实例网格'}))
  await screen.findByText(new RegExp(status==='RUNNING'?'执行进度：运行中':status==='PAUSED'?'执行进度：已暂停':'执行进度：已完成'))
  if(status!=='FINISHED')expect(nav().queryByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).not.toBeInTheDocument()
 }
 fireEvent.click(nav().getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'}))
 await screen.findByText(/报告未生成/)
 expect(request.mock.calls.some(([url])=>String(url).includes('/runs/r2/attempts/a2'))).toBe(true)
 expect(request.mock.calls.some(([url])=>String(url).includes('/attempts/a1'))).toBe(false)
})
