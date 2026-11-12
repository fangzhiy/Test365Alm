import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, vi, it, expect } from 'vitest'
import InstanceNavigator from './InstanceNavigator'
import { testsApi } from './tests'
vi.mock('./tests',()=>({testsApi:{revision:vi.fn()}}))
vi.mock('./ReviewPanel',()=>({default:({revisionId}:{revisionId:string})=><div>复核绑定 {revisionId}</div>}))
vi.mock('./SignaturePanel',()=>({default:({snapshotId}:{snapshotId:string})=><div>签署绑定 {snapshotId}</div>}))
vi.mock('./HistoryPanel',()=>({default:()=>null}))
afterEach(cleanup)
const instance={id:'i',setId:'set',testCaseId:'case',testRevisionId:'old',title:'旧用例',revisionNumber:1}
it('reads the bound revision and keeps review and signature separate from the latest revision',async()=>{
 vi.mocked(testsApi.revision).mockResolvedValue({id:'old',revisionNumber:1,title:'旧修订',description:'冻结描述',preconditions:'准备',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]})
 render(<InstanceNavigator projectId="p" instance={instance} onDenied={()=>{}} onClose={()=>{}}/>)
 await screen.findByText('冻结描述')
 expect(testsApi.revision).toHaveBeenCalledWith('p','case','old',expect.objectContaining({signal:expect.any(AbortSignal)}))
 expect(screen.getByText('复核绑定 old')).toBeVisible();expect(screen.getByText('签署绑定 old')).toBeVisible()
})
it('notifies the host on denial and clears protected content',async()=>{
 const denied=vi.fn();vi.mocked(testsApi.revision).mockRejectedValue({status:401,code:'UNAUTHENTICATED'})
 render(<InstanceNavigator projectId="p" instance={instance} onDenied={denied} onClose={()=>{}}/>)
 await waitFor(()=>expect(denied).toHaveBeenCalledOnce());expect(screen.queryByText('复核绑定 old')).not.toBeInTheDocument()
})
it('aborts an old project request and ignores its late response after navigation',async()=>{
 let finish!:(value:Awaited<ReturnType<typeof testsApi.revision>>)=>void
 vi.mocked(testsApi.revision).mockImplementationOnce(()=>new Promise(r=>{finish=r})).mockResolvedValueOnce({id:'new',revisionNumber:2,title:'新范围',description:'新的描述',preconditions:'',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]})
 const props={projectId:'p',instance,onDenied:vi.fn(),onClose:vi.fn()}
 const view=render(<InstanceNavigator {...props}/>),signal=vi.mocked(testsApi.revision).mock.calls.at(-1)?.[3]?.signal
 view.rerender(<InstanceNavigator {...props} projectId="other" instance={{...instance,testRevisionId:'new',revisionNumber:2}}/>)
 await screen.findByText('新的描述');expect(signal?.aborted).toBe(true)
 await act(async()=>finish({id:'old',revisionNumber:1,title:'旧范围',description:'不能恢复',preconditions:'',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]}))
 expect(screen.queryByText('不能恢复')).not.toBeInTheDocument();view.unmount()
})
it('rejects a mismatched revision instead of presenting unrelated approval',async()=>{
 vi.mocked(testsApi.revision).mockResolvedValue({id:'latest',revisionNumber:2,title:'新修订',description:'不应显示',preconditions:'',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]})
 render(<InstanceNavigator projectId="p" instance={instance} onDenied={()=>{}} onClose={()=>{}}/>)
 await screen.findByRole('alert');expect(screen.queryByText('不应显示')).not.toBeInTheDocument()
})
