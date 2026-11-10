import '@testing-library/jest-dom/vitest'
import { cleanup,fireEvent,render,screen,waitFor,act } from '@testing-library/react'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import ReviewPanel from './ReviewPanel'
import { reviewApi } from './reviewApi'
import type { ReviewView } from './reviewApi'
vi.mock('./reviewApi',()=>({reviewApi:{get:vi.fn(),act:vi.fn()}}))
vi.mock('./ReviewerPicker',()=>({default:({value}:{value:string|null})=><span>{value?'有效复核人':'未指定'}</span>}))
const draft:ReviewView={testCaseId:'test',revisionId:'revision',revisionNo:1,currentRevisionId:'revision',rowVersion:1,contentDigest:'a'.repeat(64),reviewerId:'reviewer',state:'DRAFT',canSubmit:true,canDecide:false,latest:null,items:[],nextCursor:null}
const submitted:ReviewView={...draft,rowVersion:2,state:'SUBMITTED',canSubmit:false,canDecide:true}
const onChanged=vi.fn(),onLock=vi.fn(),onDenied=vi.fn()
const panel=(overrides:Partial<React.ComponentProps<typeof ReviewPanel>>={})=><ReviewPanel projectId="p" testCaseId="test" revisionId="revision" rowVersion={1} contextKey="identity" onChanged={onChanged} onLock={onLock} onDenied={onDenied} {...overrides}/>
beforeEach(()=>{vi.mocked(reviewApi.get).mockResolvedValue(draft)})
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('submits exact immutable revision and uses server abilities for designated review actions',async()=>{
 vi.mocked(reviewApi.act).mockResolvedValue(submitted)
 render(panel());fireEvent.click(await screen.findByRole('button',{name:'提交复核'}))
 await screen.findByRole('button',{name:'批准本修订'})
 expect(reviewApi.act).toHaveBeenCalledWith('p','test','revision',{action:'SUBMIT',reason:''},'"1"',expect.any(String),expect.objectContaining({signal:expect.any(AbortSignal)}))
 expect(onChanged).toHaveBeenCalledWith(submitted)
 fireEvent.click(screen.getByRole('button',{name:'拒绝本修订'}));expect(await screen.findByRole('alert')).toHaveTextContent('请填写拒绝理由')
 expect(reviewApi.act).toHaveBeenCalledTimes(1)
})
it('keeps unknown outcomes locked and retries the original command version and key',async()=>{
 vi.mocked(reviewApi.act).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(submitted)
 render(panel());fireEvent.change(await screen.findByLabelText('复核说明或拒绝理由'),{target:{value:'original'}})
 fireEvent.click(screen.getByRole('button',{name:'提交复核'}));await screen.findByRole('button',{name:'重试原复核意图'})
 expect(screen.getByLabelText('复核说明或拒绝理由')).toBeDisabled();expect(onLock).toHaveBeenLastCalledWith(true)
 const original=vi.mocked(reviewApi.act).mock.calls[0]
 fireEvent.click(screen.getByRole('button',{name:'重试原复核意图'}));await waitFor(()=>expect(reviewApi.act).toHaveBeenCalledTimes(2))
 expect(vi.mocked(reviewApi.act).mock.calls[1].slice(0,6)).toEqual(original.slice(0,6))
 await waitFor(()=>expect(onLock).toHaveBeenLastCalledWith(false))
})
it('keeps viewer and historical approvals read only and appends history using opaque cursors',async()=>{
 const event={id:'e',revisionId:'revision',revisionNo:1,rowVersion:3,action:'APPROVE' as const,state:'APPROVED' as const,reviewerId:'reviewer',actorId:'reviewer',contentDigest:draft.contentDigest,reason:'checked',occurredAt:'2026-11-11T00:00:00Z'}
 vi.mocked(reviewApi.get).mockResolvedValueOnce({...draft,state:'APPROVED',canSubmit:false,latest:event,items:[event],nextCursor:'opaque'})
  .mockResolvedValueOnce({...draft,state:'APPROVED',canSubmit:false,latest:event,items:[{...event,id:'older',action:'SUBMIT',state:'SUBMITTED',rowVersion:2}],nextCursor:null})
 render(panel({readOnly:true}));fireEvent.click(await screen.findByRole('button',{name:'更多复核记录'}))
 await waitFor(()=>expect(reviewApi.get).toHaveBeenLastCalledWith('p','test','revision','opaque',expect.any(Object)))
 expect(screen.queryByRole('button',{name:'提交复核'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'批准本修订'})).not.toBeInTheDocument()
 expect(await screen.findAllByText('checked')).toHaveLength(2);expect(screen.getAllByRole('row')).toHaveLength(3)
})
it('preserves stale-version reason until explicit refresh and does not automatically retry writes',async()=>{
 vi.mocked(reviewApi.act).mockRejectedValue({status:412,code:'STALE_VERSION',message:'stale'})
 render(panel());fireEvent.change(await screen.findByLabelText('复核说明或拒绝理由'),{target:{value:'my reason'}})
 fireEvent.click(screen.getByRole('button',{name:'提交复核'}));expect(await screen.findByRole('alert')).toHaveTextContent('版本已变化')
 expect(screen.getByLabelText('复核说明或拒绝理由')).toHaveValue('my reason');expect(reviewApi.act).toHaveBeenCalledTimes(1)
})
it('clears denied records and ignores an old project response after context switches',async()=>{
 let resolve!:(v:ReviewView)=>void
 vi.mocked(reviewApi.get).mockImplementationOnce(()=>new Promise(r=>{resolve=r})).mockRejectedValueOnce({status:404})
 const rendered=render(panel());rendered.rerender(panel({projectId:'other',contextKey:'new'}))
 await screen.findByRole('alert');await act(async()=>resolve(draft))
 expect(screen.queryByRole('button',{name:'提交复核'})).not.toBeInTheDocument();expect(onDenied).toHaveBeenCalled()
})
