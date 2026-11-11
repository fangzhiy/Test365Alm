import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DefectsPanel from './DefectsPanel'
import { defectsApi } from './defectsApi'
import type { Defect } from './defectsApi'
import { historyApi } from './historyApi'
import { reviewerApi } from './reviewerApi'
import * as reportReads from './reportsApi'
import { executionApi } from './testExecution'
vi.mock('./historyApi',()=>({historyApi:{list:vi.fn()}}))
vi.mock('./DefectAttempt',()=>({default:()=>null}))
vi.mock('./defectsApi',async()=>({...await vi.importActual<typeof import('./defectsApi')>('./defectsApi'),defectsApi:{list:vi.fn(),create:vi.fn(),get:vi.fn(),history:vi.fn(),verification:vi.fn(),retests:vi.fn(),edit:vi.fn(),transition:vi.fn(),retest:vi.fn()}}))
vi.mock('./reviewerApi',()=>({reviewerApi:{list:vi.fn().mockResolvedValue({items:[{principalId:'reviewer-id',displayName:'张三'}],nextCursor:null})}}))
const access={tenantId:'t',projectId:'p',principalId:'m',roles:['PROJECT_MEMBER'] as const,permissions:['defect:read','defect:create','defect:update','defect:transition','defect:history:read','run:write']}
const c={title:'Bug',description:'reproduce',severity:'HIGH',priority:'MEDIUM'}
const d={id:'d',projectId:'p',displayNumber:1,rowVersion:1,createdBy:'m',createdAt:'2026-10-16',currentRevision:{...c,id:'r1',defectId:'d',revisionNo:1,status:'OPEN' as const,reason:'',createdBy:'m',createdAt:'2026-10-16'},links:[]}
it('report denial clears the previously loaded defect list and detail',async()=>{
 defaults()
 const read=vi.spyOn(reportReads,'loadReport').mockResolvedValue({kind:'DEFECTS',version:'F-REPORT-1',generatedAt:'2026-11-11T00:00:00Z',timeZone:'UTC',projectId:'p',projectName:'项目',filters:{},facts:{matched:0},items:[],steps:[],attachments:[],reviews:[],signatures:[],links:[],retests:[]})
 const sets=vi.spyOn(executionApi,'pageSets').mockResolvedValue({items:[],nextCursor:null})
 try {
  render(<DefectsPanel projectId="p" access={access}/>);fireEvent.click(await screen.findByRole('button',{name:/BUG-1/}));await screen.findByRole('region',{name:'缺陷详情'})
  fireEvent.click(screen.getByRole('button',{name:'偏差清单报告'}));await screen.findByText('数据完整加载',{exact:false})
  read.mockRejectedValue({code:'FORBIDDEN',status:403,message:'revoked'});fireEvent.click(screen.getByRole('button',{name:'应用报告筛选'}))
  await screen.findByText(/缺陷范围不可访问，旧内容已清理/);expect(screen.queryByRole('dialog')).toBeNull();expect(screen.queryByRole('region',{name:'缺陷详情'})).toBeNull();expect(screen.queryByRole('button',{name:/BUG-1/})).toBeNull()
 }finally{read.mockRestore();sets.mockRestore()}
})
function defaults(){vi.mocked(defectsApi.list).mockResolvedValue({items:[d],nextCursor:null});vi.mocked(defectsApi.get).mockResolvedValue(d);vi.mocked(defectsApi.history).mockResolvedValue({items:[d.currentRevision],nextCursor:null});vi.mocked(defectsApi.verification).mockResolvedValue({eligible:false,runId:null,attemptId:null,reason:'No PASS'});vi.mocked(defectsApi.retests).mockResolvedValue({items:[],nextCursor:null})}
beforeEach(()=>{vi.mocked(reviewerApi.list).mockResolvedValue({items:[{principalId:'reviewer-id',displayName:'张三'}],nextCursor:null});vi.mocked(historyApi.list).mockResolvedValue({items:[],nextCursor:null})})
afterEach(()=>{cleanup();vi.resetAllMocks();vi.useRealTimers();vi.unstubAllGlobals()})
it.each([[401,'UNAUTHENTICATED'],[403,'FORBIDDEN']] as const)('clears defect host on raw code-only %s and ignores pending detail and set names',async(status,code)=>{
 defaults()
 const dto={kind:'DEFECTS',version:'F-REPORT-1',generatedAt:'2026-11-11T00:00:00Z',timeZone:'UTC',projectId:'p',projectName:'项目',filters:{testSetId:''},facts:{matched:0,testSetName:''},items:[],steps:[],attachments:[],reviews:[],signatures:[],links:[],retests:[]}
 const reports=vi.fn().mockResolvedValueOnce(Response.json(dto)).mockResolvedValueOnce(Response.json({code},{status}));vi.stubGlobal('fetch',vi.fn((url:RequestInfo|URL)=>String(url).includes('/reports/')?reports():Promise.resolve(Response.json({items:[],nextCursor:null}))))
 let names!:(v:Awaited<ReturnType<typeof executionApi.pageSets>>)=>void,detail!:(v:Defect)=>void
 const sets=vi.spyOn(executionApi,'pageSets').mockImplementation(()=>new Promise(r=>{names=r}))
 try{
  render(<DefectsPanel projectId="p" access={access}/>);fireEvent.click(await screen.findByRole('button',{name:/BUG-1/}));await screen.findByDisplayValue('Bug')
  vi.mocked(defectsApi.get).mockImplementationOnce(()=>new Promise(r=>{detail=r}));fireEvent.click(screen.getByRole('button',{name:'刷新缺陷详情'}))
  const signal=vi.mocked(defectsApi.get).mock.calls.at(-1)?.[2]?.signal
  fireEvent.click(screen.getByRole('button',{name:'偏差清单报告'}));await screen.findByText(/数据完整加载/)
  const nameSignal=sets.mock.calls.at(-1)?.[3]?.signal
  fireEvent.click(screen.getByRole('button',{name:'应用报告筛选'}));await screen.findByText(/缺陷范围不可访问，旧内容已清理/)
  expect(signal?.aborted).toBe(true);expect(nameSignal?.aborted).toBe(true)
  expect(screen.queryByRole('dialog')).toBeNull();expect(screen.queryByRole('button',{name:'打印 / 另存为 PDF'})).toBeNull()
  await act(async()=>{detail(d);names({items:[{id:'late',projectId:'p',name:'迟到测试集',description:''}],nextCursor:null})})
  expect(screen.queryByRole('region',{name:'缺陷详情'})).toBeNull();expect(screen.queryByRole('button',{name:/BUG-1/})).toBeNull();expect(screen.queryByText('迟到测试集')).toBeNull()
 }finally{sets.mockRestore()}
})
it.each(['CSRF_REJECTED','SERVICE','NETWORK'])('retains defect list and detail after ordinary report %s failure',async code=>{
 defaults()
 const dto={kind:'DEFECTS',version:'F-REPORT-1',generatedAt:'2026-11-11T00:00:00Z',timeZone:'UTC',projectId:'p',projectName:'项目',filters:{testSetId:''},facts:{matched:0,testSetName:''},items:[],steps:[],attachments:[],reviews:[],signatures:[],links:[],retests:[]}
 const reports=vi.fn().mockResolvedValueOnce(Response.json(dto))
 if(code==='NETWORK')reports.mockRejectedValueOnce(new TypeError('offline'))
 else reports.mockResolvedValueOnce(Response.json({code},{status:code==='CSRF_REJECTED'?403:503}))
 vi.stubGlobal('fetch',vi.fn((url:RequestInfo|URL)=>String(url).includes('/reports/')?reports():Promise.resolve(Response.json({items:[],nextCursor:null}))))
 const sets=vi.spyOn(executionApi,'pageSets').mockResolvedValue({items:[],nextCursor:null})
 try{
  render(<DefectsPanel projectId="p" access={access}/>);fireEvent.click(await screen.findByRole('button',{name:/BUG-1/}));await screen.findByDisplayValue('Bug')
  fireEvent.click(screen.getByRole('button',{name:'偏差清单报告'}));await screen.findByText(/数据完整加载/)
  fireEvent.click(screen.getByRole('button',{name:'应用报告筛选'}));await screen.findByText(/报告未生成/)
  expect(screen.getByRole('button',{name:'打印 / 另存为 PDF'})).toBeDisabled();fireEvent.click(screen.getByRole('button',{name:'返回业务页面'}))
  expect(screen.getByRole('region',{name:'缺陷详情'})).toBeVisible();expect(screen.getByRole('button',{name:/BUG-1/})).toBeVisible()
 }finally{sets.mockRestore()}
})
it('audit history denial leaves defect detail editable and saving reloads audit history',async()=>{
 defaults();vi.mocked(historyApi.list).mockRejectedValueOnce({status:404}).mockResolvedValue({items:[],nextCursor:null})
 const updated={...d,rowVersion:2,currentRevision:{...d.currentRevision,id:'r2',revisionNo:2,title:'Updated'}}
 vi.mocked(defectsApi.edit).mockResolvedValue(updated)
 const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1.*Bug/}))
 await screen.findByText('无权查看或对象不存在');expect(screen.getByRole('button',{name:'保存缺陷修订'})).toBeEnabled()
 await u.clear(screen.getByLabelText('编辑缺陷标题'));await u.type(screen.getByLabelText('编辑缺陷标题'),'Updated');await u.click(screen.getByRole('button',{name:'保存缺陷修订'}))
 expect(await screen.findByText('暂无历史')).toBeInTheDocument();expect(historyApi.list).toHaveBeenCalledTimes(2)
})
it('retest and explicit refresh reload audit history even when the defect version stays unchanged',async()=>{
 defaults()
 const resolved:Defect={...d,currentRevision:{...d.currentRevision,status:'RESOLVED'},links:[{id:'link',kind:'RUN_STEP',requirementId:null,requirementRevisionId:null,testCaseId:'test',testRevisionId:'revision',runId:'run',attemptId:'old',stepKey:'step',sourceVersion:1,sourceConclusion:'FAIL',actualResult:'bad',expectedResult:'good'}]}
 vi.mocked(defectsApi.get).mockResolvedValue(resolved)
 vi.mocked(defectsApi.retest).mockResolvedValue({runId:'run',attemptId:'new'})
 const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1.*Bug/}))
 await screen.findByText('暂无历史');expect(historyApi.list).toHaveBeenCalledTimes(1)
 await u.click(screen.getByRole('button',{name:'开始复测'}))
 await screen.findByText(/复测已确认创建/);await waitFor(()=>expect(historyApi.list).toHaveBeenCalledTimes(2))
 await u.click(screen.getByRole('button',{name:'刷新缺陷详情'}))
 await screen.findByText(/从服务器重新读取/);await waitFor(()=>expect(historyApi.list).toHaveBeenCalledTimes(3))
 expect(defectsApi.retest).toHaveBeenCalledTimes(1)
})
it('ordinary member opens persisted detail and viewer has no write controls',async()=>{
 defaults();const u=userEvent.setup();const v=render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1.*Bug/}));expect(await screen.findByRole('region',{name:'缺陷详情'})).toHaveTextContent('OPEN')
 v.rerender(<DefectsPanel projectId="p" access={{...access,roles:['PROJECT_VIEWER'],permissions:['defect:read','defect:history:read']}}/>);await u.click(await screen.findByRole('button',{name:/BUG-1.*Bug/}));await screen.findByRole('region',{name:'缺陷详情'});expect(screen.queryByRole('button',{name:'保存缺陷修订'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'创建缺陷'})).not.toBeInTheDocument()
})
it('unknown create locks input and retries original intent key and content',async()=>{
 defaults();vi.mocked(defectsApi.create).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(d);const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await screen.findByRole('button',{name:/BUG-1/})
 await u.type(screen.getByLabelText('缺陷标题'),'Bug');await u.click(screen.getByRole('button',{name:'创建缺陷'}));await screen.findByRole('button',{name:'重试原创建意图'});expect(screen.getByLabelText('缺陷标题')).toBeDisabled();const call=vi.mocked(defectsApi.create).mock.calls[0]
 await u.click(screen.getByRole('button',{name:'重试原创建意图'}));await screen.findByText(/缺陷已创建/);expect(vi.mocked(defectsApi.create).mock.calls[1][1]).toEqual(call[1]);expect(vi.mocked(defectsApi.create).mock.calls[1][3]?.idempotencyKey).toBe(call[3]?.idempotencyKey)
})
it('project switch cancels pending read and ignored abort cannot restore old rows',async()=>{
 defaults();let resolve!: (v:{items:typeof d[];nextCursor:null})=>void;vi.mocked(defectsApi.list).mockImplementationOnce(()=>new Promise(r=>{resolve=r})).mockResolvedValueOnce({items:[],nextCursor:null});const v=render(<DefectsPanel projectId="p" access={access}/>);await waitFor(()=>expect(defectsApi.list).toHaveBeenCalled());const signal=vi.mocked(defectsApi.list).mock.calls[0][3]!.signal!
 v.rerender(<DefectsPanel projectId="b" access={{...access,projectId:'b'}}/>);expect(signal.aborted).toBe(true);await act(async()=>resolve({items:[d],nextCursor:null}));expect(screen.queryByText(/BUG-1/)).not.toBeInTheDocument();await waitFor(()=>expect(screen.getByRole('button',{name:'查询缺陷'})).toBeEnabled())
})
it('412 draft cannot silently use a refreshed version without explicit adoption',async()=>{
 defaults();vi.mocked(defectsApi.edit).mockRejectedValueOnce({status:412,code:'STALE_VERSION',message:'stale'});const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1/}));const detail=await screen.findByRole('region',{name:'缺陷详情'});await u.clear(within(detail).getByLabelText('编辑缺陷标题'));await u.type(within(detail).getByLabelText('编辑缺陷标题'),'Draft A');await u.click(within(detail).getByRole('button',{name:'保存缺陷修订'}));await screen.findByText(/stale/)
 const newer={...d,rowVersion:2,currentRevision:{...d.currentRevision,id:'r2',revisionNo:2,title:'Server B'}};vi.mocked(defectsApi.get).mockResolvedValue(newer);await u.click(within(detail).getByRole('button',{name:'刷新缺陷详情'}));await screen.findByText(/Server B/);expect(within(detail).getByLabelText('编辑缺陷标题')).toHaveValue('Draft A');expect(within(detail).getByRole('button',{name:'保存缺陷修订'})).toBeDisabled()
 await u.click(within(detail).getByRole('button',{name:'采用最新版本并继续编辑'}));vi.mocked(defectsApi.edit).mockResolvedValue({...newer,rowVersion:3,currentRevision:{...newer.currentRevision,revisionNo:3,title:'Draft A'}});await u.click(within(detail).getByRole('button',{name:'保存缺陷修订'}));await waitFor(()=>expect(defectsApi.edit).toHaveBeenCalledTimes(2));expect(vi.mocked(defectsApi.edit).mock.calls[1][1].rowVersion).toBe(2)
})
it('create access loss invalidates the whole loaded scope without changing permission props',async()=>{
 defaults();vi.mocked(defectsApi.create).mockRejectedValue({status:404,code:'NOT_FOUND',message:'gone'});const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1/}));await screen.findByRole('region',{name:'缺陷详情'});await u.type(screen.getByLabelText('缺陷标题'),'private draft');await u.click(screen.getByRole('button',{name:'创建缺陷'}));await screen.findByText(/旧内容已清理/);expect(screen.queryByRole('region',{name:'缺陷详情'})).not.toBeInTheDocument();expect(screen.queryByText(/BUG-1/)).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'创建缺陷'})).not.toBeInTheDocument()
})
it('pending list read and create are mutually exclusive so stale list cannot erase confirmed creation',async()=>{
 defaults();const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await screen.findByRole('button',{name:/BUG-1/});let resolve!:(v:{items:typeof d[];nextCursor:null})=>void;vi.mocked(defectsApi.list).mockImplementationOnce(()=>new Promise(r=>{resolve=r}));await u.click(screen.getByRole('button',{name:'刷新缺陷'}));expect(screen.getByRole('button',{name:'创建缺陷'})).toBeDisabled();await act(async()=>resolve({items:[d],nextCursor:null}));await waitFor(()=>expect(screen.getByRole('button',{name:'创建缺陷'})).toBeEnabled())
 let created!:(v:typeof d)=>void;vi.mocked(defectsApi.create).mockImplementationOnce(()=>new Promise(r=>{created=r}));await u.type(screen.getByLabelText('缺陷标题'),'Second');await u.click(screen.getByRole('button',{name:'创建缺陷'}));expect(screen.getByRole('button',{name:'刷新缺陷'})).toBeDisabled();expect(screen.getByRole('button',{name:'查询缺陷'})).toBeDisabled();await act(async()=>created({...d,id:'d2',displayNumber:2,currentRevision:{...d.currentRevision,defectId:'d2',title:'Second'}}));expect(await screen.findByRole('button',{name:/BUG-2.*Second/})).toBeEnabled()
})
it.each(['success','failure','abort'])('direct read loss cancels write and ignored late %s cannot restore data or block restored scope',async(mode)=>{
 defaults();let resolve!:(v:typeof d)=>void,reject!:(e:unknown)=>void;vi.mocked(defectsApi.create).mockImplementationOnce(()=>new Promise((yes,no)=>{resolve=yes;reject=no}));const u=userEvent.setup();const view=render(<DefectsPanel projectId="p" access={access}/>);await screen.findByRole('button',{name:/BUG-1/});await u.type(screen.getByLabelText('缺陷标题'),'Secret old');await u.click(screen.getByRole('button',{name:'创建缺陷'}));const signal=vi.mocked(defectsApi.create).mock.calls[0][3]!.signal!
 view.rerender(<DefectsPanel projectId="p" access={null}/>);expect(signal.aborted).toBe(true);view.rerender(<DefectsPanel projectId="p" access={access}/>);await screen.findByRole('button',{name:/BUG-1/});await act(async()=>{if(mode==='success')resolve(d);else reject(mode==='abort'?new DOMException('cancelled','AbortError'):new Error('late failure'))});expect(screen.getByLabelText('缺陷标题')).toHaveValue('');expect(screen.getByRole('button',{name:'创建缺陷'})).toBeEnabled();expect(screen.queryByText(/缺陷已创建/)).not.toBeInTheDocument()
})
it('StrictMode unmount aborts request and timeout preserves the original unknown intent',async()=>{
 defaults();const view=render(<StrictMode><DefectsPanel projectId="p" access={access}/></StrictMode>);await screen.findByRole('button',{name:/BUG-1/});vi.mocked(defectsApi.create).mockImplementation(()=>new Promise(()=>{}));vi.useFakeTimers();fireEvent.change(screen.getByLabelText('缺陷标题'),{target:{value:'Unknown'}});fireEvent.click(screen.getByRole('button',{name:'创建缺陷'}));const original=vi.mocked(defectsApi.create).mock.calls[0];await act(async()=>vi.advanceTimersByTime(8001));expect(original[3]!.signal!.aborted).toBe(true);expect(screen.getByLabelText('缺陷标题')).toBeDisabled();fireEvent.click(screen.getByRole('button',{name:'重试原创建意图'}));expect(vi.mocked(defectsApi.create).mock.calls[1][3]!.idempotencyKey).toBe(original[3]!.idempotencyKey);view.unmount();expect(vi.mocked(defectsApi.create).mock.calls[1][3]!.signal!.aborted).toBe(true);expect(vi.getTimerCount()).toBe(0)
})
it('confirmed edit followed by failed GET retries only reads and preserves confirmation',async()=>{
 defaults();const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await u.click(await screen.findByRole('button',{name:/BUG-1/}));const detail=await screen.findByRole('region',{name:'缺陷详情'});await u.type(within(detail).getByLabelText('编辑缺陷描述'),' fixed');vi.mocked(defectsApi.edit).mockResolvedValue({...d,rowVersion:2,currentRevision:{...d.currentRevision,id:'r2',revisionNo:2}});await u.click(within(detail).getByRole('button',{name:'保存缺陷修订'}));await screen.findByText(/写入已确认/);expect(vi.mocked(defectsApi.edit).mock.calls[0][2]).toEqual({...c,description:'reproduce fixed'});vi.mocked(defectsApi.get).mockRejectedValueOnce(new Error('read unavailable'));await u.click(within(detail).getByRole('button',{name:'刷新缺陷详情'}));await screen.findByText('read unavailable');expect(detail).toHaveTextContent('v2');await u.click(within(detail).getByRole('button',{name:'刷新缺陷详情'}));await screen.findByText(/从服务器重新读取/);expect(defectsApi.edit).toHaveBeenCalledTimes(1)
})
it('unknown defect creation freezes department location and reviewer with the original retry key',async()=>{
 defaults();vi.mocked(defectsApi.create).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(d);const u=userEvent.setup();render(<DefectsPanel projectId="p" access={access}/>);await screen.findByRole('button',{name:/BUG-1/})
 await u.type(screen.getByLabelText('缺陷标题'),'Deviation');await u.type(screen.getByLabelText('发生部门'),'QA');await u.type(screen.getByLabelText('发生地点或系统'),'Line 1');await u.click(screen.getByRole('button',{name:'查找复核人'}));await u.selectOptions(await screen.findByLabelText('指定复核人'),'reviewer-id');await u.click(screen.getByRole('button',{name:'创建缺陷'}));await screen.findByRole('button',{name:'重试原创建意图'});expect(screen.getByLabelText('发生部门')).toBeDisabled();const first=vi.mocked(defectsApi.create).mock.calls[0];expect(first[1].businessFields).toEqual({occurrence_department:{value:'QA'},occurrence_location:{value:'Line 1'},reviewer:{value:'reviewer-id'}})
 await u.click(screen.getByRole('button',{name:'重试原创建意图'}));await waitFor(()=>expect(defectsApi.create).toHaveBeenCalledTimes(2));expect(vi.mocked(defectsApi.create).mock.calls[1][1]).toEqual(first[1]);expect(vi.mocked(defectsApi.create).mock.calls[1][3]?.idempotencyKey).toBe(first[3]?.idempotencyKey)
})
