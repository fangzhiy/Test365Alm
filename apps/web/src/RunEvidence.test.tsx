import { afterEach,expect,it,vi } from 'vitest'
import { act,cleanup,fireEvent,render,screen } from '@testing-library/react'
import RunEvidence from './RunEvidence'
import { loadReport,type Report } from './reportsApi'
vi.mock('./reportsApi',async()=>({...await vi.importActual<typeof import('./reportsApi')>('./reportsApi'),loadReport:vi.fn()}))
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('changing selected Attempt cancels the exact old report and cannot restore late signing facts',async()=>{
 let finish!:(report:Report)=>void
 vi.mocked(loadReport).mockImplementationOnce(()=>new Promise(r=>{finish=r}))
 const props={projectId:'p',runId:'run',attemptId:'a1',onDenied:vi.fn()},view=render(<RunEvidence {...props}/>)
 fireEvent.click(screen.getByRole('button',{name:'核验关联事实'}))
 expect(loadReport).toHaveBeenCalledWith('p',{kind:'RUN',runId:'run',attemptId:'a1'},{},expect.any(AbortSignal))
 const signal=vi.mocked(loadReport).mock.calls[0][3]
 view.rerender(<RunEvidence {...props} attemptId="a2"/>);expect(signal.aborted).toBe(true)
 await act(async()=>finish({reviews:[],items:[],signatures:[{id:'s',kind:'RUN_COMPLETION',displayName:'旧尝试签署人'}]} as unknown as Report))
 expect(screen.queryByText('旧尝试签署人')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'核验关联事实'})).toBeVisible()
})
it('real scope error classification calls host cleanup while a service failure stays a read error',async()=>{
 vi.mocked(loadReport).mockRejectedValueOnce({code:'FORBIDDEN',status:403})
 const denied=vi.fn(),view=render(<RunEvidence projectId="p" runId="run" attemptId="a1" onDenied={denied}/>)
 fireEvent.click(screen.getByRole('button',{name:'核验关联事实'}));await vi.waitFor(()=>expect(denied).toHaveBeenCalledOnce())
 view.rerender(<RunEvidence projectId="p" runId="run" attemptId="a2" onDenied={denied}/>);vi.mocked(loadReport).mockRejectedValueOnce({code:'HTTP_500',status:500})
 fireEvent.click(screen.getByRole('button',{name:'核验关联事实'}));expect(await screen.findByRole('alert')).toHaveTextContent('关联事实读取失败');expect(denied).toHaveBeenCalledOnce()
})
