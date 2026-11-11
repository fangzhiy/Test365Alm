import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import ExecutionGrid from './ExecutionGrid'
import { testsApi } from './tests'
import type { TestInstance } from './testExecution'
vi.mock('./tests',()=>({testsApi:{revision:vi.fn()}}))
vi.mock('./ReviewPanel',()=>({default:({revisionId}:{revisionId:string})=><div>复核绑定 {revisionId}</div>}))
vi.mock('./SignaturePanel',()=>({default:({snapshotId}:{snapshotId:string})=><div>签署绑定 {snapshotId}</div>}))
vi.mock('./HistoryPanel',()=>({default:()=>null}))
vi.mock('./Reports',()=>({default:({target}:{target:{kind:string;runId:string;attemptId:string}})=>target.kind==='RUN'?<button onClick={()=>fetch(`/reports/runs/${target.runId}/attempts/${target.attemptId}`)}>实例最新 Attempt 与关联偏差报告</button>:null}))
afterEach(()=>{cleanup();vi.resetAllMocks();vi.unstubAllGlobals()})
const frozen={id:'revision',revisionNumber:2,title:'绑定修订',description:'冻结内容',preconditions:'准备',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]}
const instance:TestInstance={id:'instance',projectId:'p',setId:'set',testCaseId:'case',testRevisionId:'revision',revisionNumber:2,title:'导航用例',runId:'run',attemptId:'a1',status:'FINISHED',conclusion:'FAIL'}
const props=()=>({projectId:'p',instances:[instance],writable:true,disabled:false,nextCursor:null,filters:{},onFilter:vi.fn(),onMore:vi.fn(),onRefresh:vi.fn(),onOpen:vi.fn(),onStart:vi.fn(),onAssigned:vi.fn(),onLock:vi.fn(),onDenied:vi.fn()})
it('an open grid navigator tracks refreshed Attempt 1 → running/paused Attempt 2 → finished Attempt 2',async()=>{
 vi.mocked(testsApi.revision).mockResolvedValue(frozen)
 const request=vi.fn().mockResolvedValue({});vi.stubGlobal('fetch',request)
 const p=props(),view=render(<ExecutionGrid {...p}/>);fireEvent.click(screen.getByRole('button',{name:'查看绑定修订 导航用例'}));await screen.findByText('冻结内容')
 const nav=()=>within(screen.getByRole('region',{name:'实例修订与闭环导航'}))
 fireEvent.click(nav().getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'}));expect(request).toHaveBeenLastCalledWith('/reports/runs/run/attempts/a1')
 view.rerender(<ExecutionGrid {...p} refreshing/>);expect(nav().getByRole('status')).toHaveTextContent('正在重新核对');expect(nav().queryByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).not.toBeInTheDocument()
 for(const status of ['RUNNING','PAUSED'] as const){view.rerender(<ExecutionGrid {...p} instances={[{...instance,attemptId:'a2',status,conclusion:null}]}/>);expect(nav().queryByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).not.toBeInTheDocument()}
 view.rerender(<ExecutionGrid {...p} instances={[{...instance,runId:'run2',attemptId:'a2',status:'FINISHED',conclusion:'PASS'}]}/>);fireEvent.click(screen.getByRole('button',{name:'刷新实例网格'}));expect(p.onRefresh).toHaveBeenCalledOnce()
 fireEvent.click(nav().getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'}));expect(request).toHaveBeenLastCalledWith('/reports/runs/run2/attempts/a2')
 expect(nav().getByText('复核绑定 revision')).toBeVisible()
})
it('removing an instance closes navigation, aborts the read and cannot revive it after a late response',async()=>{
 let finish!:(r:typeof frozen)=>void;vi.mocked(testsApi.revision).mockImplementationOnce(()=>new Promise(r=>{finish=r}))
 const p=props(),view=render(<ExecutionGrid {...p}/>);fireEvent.click(screen.getByRole('button',{name:'查看绑定修订 导航用例'}));await waitFor(()=>expect(testsApi.revision).toHaveBeenCalled())
 const signal=vi.mocked(testsApi.revision).mock.calls[0][3]?.signal
 view.rerender(<ExecutionGrid {...p} instances={[]}/>);expect(screen.queryByRole('region',{name:'实例修订与闭环导航'})).not.toBeInTheDocument();expect(signal?.aborted).toBe(true)
 await act(async()=>finish(frozen));view.rerender(<ExecutionGrid {...p}/>);expect(screen.queryByText('冻结内容')).not.toBeInTheDocument()
})
it.each(['project','set','filter','denied'] as const)('invalidates navigation on %s scope changes',async reason=>{
 vi.mocked(testsApi.revision).mockResolvedValue(frozen)
 const p=props(),view=render(<ExecutionGrid {...p}/>);fireEvent.click(screen.getByRole('button',{name:'查看绑定修订 导航用例'}));await screen.findByText('冻结内容')
 if(reason==='project')view.rerender(<ExecutionGrid {...p} projectId="other"/>)
 if(reason==='set')view.rerender(<ExecutionGrid {...p} instances={[{...instance,setId:'other'}]}/>)
 if(reason==='filter')view.rerender(<ExecutionGrid {...p} filters={{status:'RUNNING'}}/>)
 if(reason==='denied'){
  // A fresh bound revision request encounters revoked access; the host owns scope cleanup.
  vi.mocked(testsApi.revision).mockRejectedValue({code:'UNAUTHENTICATED',status:401})
  view.rerender(<ExecutionGrid {...p} instances={[{...instance,testRevisionId:'changed'}]}/>);await waitFor(()=>expect(p.onDenied).toHaveBeenCalled())
 }
 expect(screen.queryByRole('region',{name:'实例修订与闭环导航'})).not.toBeInTheDocument()
 view.rerender(<ExecutionGrid {...p}/>);expect(screen.queryByText('冻结内容')).not.toBeInTheDocument()
})
