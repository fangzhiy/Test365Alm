import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import ExecutionGrid from './ExecutionGrid'
import { executionApi, type TestInstance } from './testExecution'
vi.mock('./testExecution',async()=>({...await vi.importActual<typeof import('./testExecution')>('./testExecution'),executionApi:{executionAssignees:vi.fn(),assignInstance:vi.fn()}}))
afterEach(()=>{cleanup();vi.resetAllMocks()})
const instance:TestInstance={id:'i',projectId:'p',setId:'s',testCaseId:'c',testRevisionId:'r',title:'OQ case',revisionNumber:2,assigneeId:'member',assigneeName:'Operator',assignmentVersion:3,runId:'run',attemptId:'a',status:'PAUSED',conclusion:null,executionDate:'2026-01-01T10:00:00Z'}
const props=()=>({projectId:'p',instances:[instance],writable:true,disabled:false,nextCursor:'opaque',filters:{},onFilter:vi.fn(),onMore:vi.fn(),onRefresh:vi.fn(),onOpen:vi.fn(),onStart:vi.fn(),onAssigned:vi.fn(),onLock:vi.fn(),onDenied:vi.fn()})
it('renders a real instance grid and opens its exact run or follows the next cursor',()=>{
 const p=props();render(<ExecutionGrid {...p}/>);expect(screen.getByRole('table',{name:'实例执行网格'})).toBeVisible()
 expect(screen.getByText('Operator')).toBeVisible();expect(screen.getByText('2026-01-01 10:00 UTC')).toHaveAttribute('datetime',instance.executionDate)
 fireEvent.click(screen.getByRole('button',{name:'进入运行 OQ case'}));expect(p.onOpen).toHaveBeenCalledWith(instance)
 fireEvent.click(screen.getByRole('button',{name:'加载更多实例'}));expect(p.onMore).toHaveBeenCalledOnce()
 fireEvent.change(screen.getByLabelText('执行状态筛选'),{target:{value:'PAUSED'}});fireEvent.click(screen.getByRole('button',{name:'筛选实例'}));expect(p.onFilter).toHaveBeenCalledWith({status:'PAUSED'})
})
it('retains the original assignment version and key after unknown outcome',async()=>{
 const p=props();vi.mocked(executionApi.executionAssignees).mockResolvedValue({items:[{id:'other',displayName:'Other operator'}],nextCursor:null})
 vi.mocked(executionApi.assignInstance).mockRejectedValueOnce({message:'unknown'}).mockResolvedValueOnce({instanceId:'i',assigneeId:'other',assigneeName:'Other operator',assignmentVersion:4})
 render(<ExecutionGrid {...p}/>);fireEvent.click(screen.getByRole('button',{name:'分配负责人 OQ case'}));await screen.findAllByRole('option',{name:'Other operator'})
 fireEvent.change(screen.getByLabelText('实例负责人'),{target:{value:'other'}});fireEvent.click(screen.getByRole('button',{name:'保存负责人'}));await screen.findByText('unknown')
 expect(screen.getByLabelText('实例负责人')).toBeDisabled();fireEvent.click(screen.getByRole('button',{name:'重试原负责人操作'}))
 await waitFor(()=>expect(p.onAssigned).toHaveBeenCalledWith({instanceId:'i',assigneeId:'other',assigneeName:'Other operator',assignmentVersion:4}))
 const calls=vi.mocked(executionApi.assignInstance).mock.calls;expect(calls[0].slice(0,4)).toEqual(['p','i','other',3]);expect(calls[0][4]?.idempotencyKey).toBe(calls[1][4]?.idempotencyKey)
})
it('viewer can read execution state and dates without assignment or start controls',()=>{
 render(<ExecutionGrid {...props()} writable={false}/>);expect(screen.getByText('Operator')).toBeVisible()
 expect(screen.queryByRole('button',{name:/分配负责人/})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'启动手工运行'})).not.toBeInTheDocument()
})
