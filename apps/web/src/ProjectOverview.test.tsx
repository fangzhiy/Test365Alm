import { afterEach,expect,it,vi } from 'vitest'
import { act,cleanup,fireEvent,render,screen,waitFor } from '@testing-library/react'
import ProjectOverview from './ProjectOverview'
import { readOverview,type OverviewData } from './overviewData'
import type { ProjectAccess } from './projectAccess'
vi.mock('./overviewData',()=>({readOverview:vi.fn()}))
const access:ProjectAccess={tenantId:'t',projectId:'p',principalId:'u',roles:['PROJECT_VIEWER'],permissions:['test:read']}
const empty:OverviewData={cases:[],instances:[],counts:{NOT_RUN:0,RUNNING:0,PAUSED:0,PASS:0,FAIL:0,BLOCKED:0},history:[],defects:[],reviews:[]}
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('uses zero-denominator — and marks unsupported activity as unavailable without fabricated events',async()=>{
 vi.mocked(readOverview).mockResolvedValue(empty)
 render(<ProjectOverview projectId="p" access={access} onDenied={vi.fn()} onNavigate={vi.fn()}/>)
 await screen.findByRole('table',{name:'用例执行进度'});expect(screen.getByText('—',{exact:true})).toBeVisible()
 expect(screen.getByText('暂无项目级动态接口')).toBeVisible();expect(screen.queryByText('100%')).not.toBeInTheDocument()
})
it('project changes and unmount cancel reads and ignore late responses',async()=>{
 let finish!:(data:OverviewData)=>void
 vi.mocked(readOverview).mockImplementationOnce(()=>new Promise(r=>{finish=r})).mockResolvedValue(empty)
 const props={projectId:'p',access,onDenied:vi.fn(),onNavigate:vi.fn()},view=render(<ProjectOverview {...props}/>)
 const signal=vi.mocked(readOverview).mock.calls[0][1]
 view.rerender(<ProjectOverview {...props} projectId="other" access={{...access,projectId:'other'}}/>);expect(signal.aborted).toBe(true)
 await screen.findByRole('table',{name:'用例执行进度'})
 await act(async()=>finish({...empty,counts:{...empty.counts,PASS:99}}));expect(screen.queryByText(/通过 99/)).not.toBeInTheDocument()
 view.unmount();expect(vi.mocked(readOverview).mock.calls[1][1].aborted).toBe(true)
})
it.each([{status:401,code:'UNAUTHENTICATED'},{status:403,code:'FORBIDDEN'}])('denial clears old overview instead of retaining counters',async error=>{
 vi.mocked(readOverview).mockResolvedValueOnce(empty).mockRejectedValueOnce(error)
 const denied=vi.fn();render(<ProjectOverview projectId="p" access={access} onDenied={denied} onNavigate={vi.fn()}/>)
 await screen.findByRole('table',{name:'用例执行进度'});fireEvent.click(screen.getByRole('button',{name:'刷新概览'}))
 await waitFor(()=>expect(denied).toHaveBeenCalledOnce());expect(screen.queryByRole('table')).not.toBeInTheDocument()
})
it('ordinary read failure clears aggregate data and shows a normal error without scope denial',async()=>{
 vi.mocked(readOverview).mockRejectedValue(new Error('网络中断'));const denied=vi.fn()
 render(<ProjectOverview projectId="p" access={access} onDenied={denied} onNavigate={vi.fn()}/>)
 expect(await screen.findByRole('alert')).toHaveTextContent('网络中断');expect(denied).not.toHaveBeenCalled();expect(screen.queryByRole('table')).not.toBeInTheDocument()
})
