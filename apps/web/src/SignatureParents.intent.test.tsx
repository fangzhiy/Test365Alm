import '@testing-library/jest-dom/vitest'
import { act,cleanup,fireEvent,render,screen,waitFor } from '@testing-library/react'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import TestExecutionPanel from './TestExecutionPanel'
import DefectsPanel from './DefectsPanel'
import { executionApi } from './testExecution'
import { defectsApi } from './defectsApi'
import { signatureApi } from './signaturesApi'
import type { SignatureView } from './signaturesApi'
import type { Defect } from './defectsApi'
import type { Run,RunAttempt,TestSet } from './testExecution'

const callbacks=vi.hoisted(()=>({moved:undefined as (()=>void)|undefined,select:undefined as ((id:string)=>void)|undefined,created:undefined as ((d:Defect)=>void)|undefined}))
vi.mock('./FolderTree',()=>({default:({onMoved,onSelect}:{onMoved:()=>void;onSelect:(id:string)=>void})=>{callbacks.moved=onMoved;callbacks.select=onSelect;return <span>Folders</span>}}))
vi.mock('./DefectCreate',()=>({default:({onCreated}:{onCreated:(d:Defect)=>void})=>{callbacks.created=onCreated;return null}}))
vi.mock('./ExecutionGrid',()=>({default:()=>null}))
vi.mock('./StepAttachments',()=>({default:()=>null}))
vi.mock('./HistoryPanel',()=>({default:()=>null}))
vi.mock('./AttachmentsPanel',()=>({default:()=>null}))
vi.mock('./BusinessFieldsForm',()=>({default:()=>null}))
vi.mock('./DefectAttempt',()=>({default:()=>null}))
vi.mock('./testExecution',()=>({executionApi:{pageSets:vi.fn(),getSet:vi.fn(),executionGrid:vi.fn(),pageRuns:vi.fn(),summary:vi.fn(),getRun:vi.fn(),pageAttempts:vi.fn()}}))
vi.mock('./defectsApi',async()=>({...await vi.importActual<typeof import('./defectsApi')>('./defectsApi'),defectsApi:{list:vi.fn(),get:vi.fn(),history:vi.fn(),verification:vi.fn(),retests:vi.fn()}}))
vi.mock('./signaturesApi',async()=>({...await vi.importActual<typeof import('./signaturesApi')>('./signaturesApi'),signatureApi:{get:vi.fn(),prepare:vi.fn(),sign:vi.fn()}}))

const access={tenantId:'tenant',projectId:'project',principalId:'signer',roles:['PROJECT_MEMBER'] as const,permissions:['test:read','run:write','defect:read','defect:create','defect:transition']}
const set:TestSet={id:'set',projectId:'project',name:'Signed set',description:''}
const attempt:RunAttempt={id:'attempt',attemptNo:1,rowVersion:7,state:'FINISHED',outcome:'PASS',steps:[]}
const run:Run={id:'run',projectId:'project',instanceId:'instance',manifestId:'manifest',attemptId:'attempt',rowVersion:1,state:'FINISHED',outcome:'PASS',attempt}
const defect:Defect={id:'defect',projectId:'project',displayNumber:1,rowVersion:7,createdBy:'signer',createdAt:'2026-11-11',links:[],currentRevision:{id:'revision',defectId:'defect',revisionNo:7,title:'Closed deviation',description:'',severity:'HIGH',priority:'MEDIUM',status:'CLOSED',reason:'Verified',createdBy:'signer',createdAt:'2026-11-11'}}
const challenge={id:'challenge',expiresAt:'2026-11-11T12:05:00Z',authenticationUrl:'/api/authenticate'}
const popup={closed:false,close:vi.fn()}
const deferred=<T,>()=>{let resolve!:(v:T)=>void,reject!:(e:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
const phases=['in-flight','unknown'] as const
const stages=['challenge','sign'] as const
const parents=['execution','deviation'] as const
type Parent=typeof parents[number]
type Stage=typeof stages[number]
const view=(parent:Parent):SignatureView=>({kind:parent==='execution'?'RUN_COMPLETION':'DEVIATION_CLOSURE',objectId:parent==='execution'?'run':'defect',snapshotId:parent==='execution'?'attempt':'revision',rowVersion:7,state:parent==='execution'?'FINISHED':'CLOSED',canSign:true,contentDigest:'a'.repeat(64),signature:null})
const signed=(parent:Parent):SignatureView=>{const v=view(parent);return {...v,canSign:false,signature:{id:'signed',schema:'SIGNATURE_RECORD_V1',projectId:'project',kind:v.kind,meaning:parent==='execution'?'运行完成确认':'偏差关闭确认',objectId:v.objectId,snapshotId:v.snapshotId,sourceVersion:7,actorId:'signer',issuer:'https://idp',subject:'signer',displayName:'Signer',authenticatedAt:'2026-11-11T12:00:00Z',signedAt:'2026-11-11T12:00:01Z',contentDigest:v.contentDigest,contentSnapshot:{},evidence:{provider:'SIMULATED_V1',simulated:true,reference:'signed',bindingDigest:'b'.repeat(64)}}}}

beforeEach(()=>{
 vi.resetAllMocks();callbacks.moved=undefined;callbacks.select=undefined;callbacks.created=undefined
 vi.mocked(executionApi.pageSets).mockResolvedValue({items:[set],nextCursor:null});vi.mocked(executionApi.getSet).mockResolvedValue(set)
 vi.mocked(executionApi.executionGrid).mockResolvedValue({items:[],nextCursor:null});vi.mocked(executionApi.pageRuns).mockResolvedValue({items:[run],nextCursor:null})
 vi.mocked(executionApi.summary).mockResolvedValue({totalInstances:1,unrunInstances:0,activeAttempts:0,latestCompletedPass:1,latestCompletedFail:0,latestCompletedBlocked:0})
 vi.mocked(executionApi.getRun).mockResolvedValue(run);vi.mocked(executionApi.pageAttempts).mockResolvedValue({items:[attempt],nextCursor:null})
 vi.mocked(defectsApi.list).mockResolvedValue({items:[defect],nextCursor:null});vi.mocked(defectsApi.get).mockResolvedValue(defect)
 vi.mocked(defectsApi.history).mockResolvedValue({items:[defect.currentRevision],nextCursor:null});vi.mocked(defectsApi.retests).mockResolvedValue({items:[],nextCursor:null})
 vi.mocked(defectsApi.verification).mockResolvedValue({eligible:true,runId:null,attemptId:null,reason:'Closed'})
 vi.mocked(signatureApi.prepare).mockResolvedValue(challenge);vi.spyOn(window,'open').mockReturnValue(popup as unknown as Window)
})
afterEach(()=>{cleanup();vi.restoreAllMocks()})

function renderParent(parent:Parent,options:Partial<{projectId:string;principalId:string;permissions:string[];resetSignal:number}>={}){
 const projectId=options.projectId??'project',nextAccess={...access,projectId,principalId:options.principalId??access.principalId,permissions:options.permissions??access.permissions}
 return parent==='execution'?<TestExecutionPanel projectId={projectId} access={nextAccess} resetSignal={options.resetSignal}/>:<DefectsPanel projectId={projectId} access={nextAccess} resetSignal={options.resetSignal}/>
}
async function open(parent:Parent){
 vi.mocked(signatureApi.get).mockResolvedValue(view(parent))
 if(parent==='execution'){fireEvent.click(await screen.findByRole('button',{name:/Signed set/}));fireEvent.click(await screen.findByRole('button',{name:/运行 run/}))}
 else fireEvent.click(await screen.findByRole('button',{name:/BUG-1/}))
 fireEvent.click(await screen.findByRole('button',{name:'查看模拟签署'}));await waitFor(()=>expect(screen.getByRole('button',{name:'开始模拟签署'})).toBeEnabled())
}
async function begin(parent:Parent,stage:Stage){
 const pending=deferred<SignatureView>(),preparing=deferred<typeof challenge>()
 if(stage==='challenge')vi.mocked(signatureApi.prepare).mockReturnValueOnce(preparing.promise)
 else vi.mocked(signatureApi.sign).mockReturnValueOnce(pending.promise).mockResolvedValue(signed(parent))
 fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}))
 if(stage==='sign'){
  const authenticate=await screen.findByRole('button',{name:'重新登录以确认身份'})
  await waitFor(()=>expect(authenticate).toBeEnabled());fireEvent.click(authenticate);expect(window.open).toHaveBeenCalledTimes(1)
  act(()=>window.dispatchEvent(new MessageEvent('message',{origin:location.origin,source:popup as unknown as Window,data:{type:'test365alm-signature',status:'reauthenticated'}})))
  fireEvent.click(await screen.findByRole('button',{name:/确认模拟签署：/}))
 }
 await waitFor(()=>expect(stage==='challenge'?signatureApi.prepare:signatureApi.sign).toHaveBeenCalledTimes(1))
 return {unknown:()=>stage==='challenge'?preparing.reject(new Error('unknown transport outcome')):pending.reject(new Error('unknown transport outcome')),complete:()=>stage==='challenge'?preparing.resolve(challenge):pending.resolve(signed(parent))}
}

it.each(stages.flatMap(stage=>phases.flatMap(phase=>['move','select'].map(callback=>({stage,phase,callback})))) )('preserves execution $stage intent during $phase after a pre-signature folder $callback callback',async({stage,phase,callback})=>{
 render(renderParent('execution'));await open('execution')
 // Capture the actual parent's old callback before signing, as FolderTree.place does across its await.
 const select=callbacks.select!,late=callback==='move'?callbacks.moved!:()=>select('destination')
 const pending=await begin('execution',stage);if(phase==='unknown')await act(async()=>pending.unknown())
 const reads=vi.mocked(executionApi.pageSets).mock.calls.length
 act(()=>late());expect(executionApi.pageSets).toHaveBeenCalledTimes(reads)
 expect(screen.getByText('运行详情 · 尝试 1')).toBeInTheDocument()
 if(phase==='in-flight')await act(async()=>pending.unknown())
 fireEvent.click(await screen.findByRole('button',{name:'重试原签署意图'}))
 if(stage==='challenge')await screen.findByRole('button',{name:'重新登录以确认身份'})
 else await screen.findByText(/已模拟签署：运行完成确认/)
 const calls=stage==='challenge'?vi.mocked(signatureApi.prepare).mock.calls:vi.mocked(signatureApi.sign).mock.calls
 expect(calls).toHaveLength(2);expect(calls[0].slice(0,stage==='challenge'?6:7)).toEqual(calls[1].slice(0,stage==='challenge'?6:7))
 expect(calls[0][stage==='challenge'?4:5]).toBe('"7"')
 if(stage==='sign')expect(calls[0][4]).toEqual({challengeId:'challenge'})
})

it.each(stages.flatMap(stage=>phases.map(phase=>({stage,phase}))))('preserves deviation $stage intent during $phase when a prior creation callback arrives',async({stage,phase})=>{
 render(renderParent('deviation'));await open('deviation');const late=callbacks.created!
 const pending=await begin('deviation',stage);if(phase==='unknown')await act(async()=>pending.unknown())
 act(()=>late({...defect,id:'other',displayNumber:2,currentRevision:{...defect.currentRevision,id:'other-revision',defectId:'other',title:'New deviation'}}))
 expect(screen.getByLabelText('编辑缺陷标题')).toHaveValue('Closed deviation')
 expect(screen.getByRole('button',{name:/BUG-2/})).toBeDisabled();fireEvent.click(screen.getByRole('button',{name:/BUG-2/}))
 expect(defectsApi.get).toHaveBeenCalledTimes(1);expect(screen.getByRole('button',{name:'刷新缺陷'})).toBeDisabled()
 if(phase==='in-flight')await act(async()=>pending.unknown())
 fireEvent.click(await screen.findByRole('button',{name:'重试原签署意图'}))
 if(stage==='challenge')await screen.findByRole('button',{name:'重新登录以确认身份'})
 else await screen.findByText(/已模拟签署：偏差关闭确认/)
 const calls=stage==='challenge'?vi.mocked(signatureApi.prepare).mock.calls:vi.mocked(signatureApi.sign).mock.calls
 expect(calls).toHaveLength(2);expect(calls[0].slice(0,stage==='challenge'?6:7)).toEqual(calls[1].slice(0,stage==='challenge'?6:7))
})

it.each(parents.flatMap(parent=>stages.flatMap(stage=>phases.flatMap(phase=>['project','identity','permissions','reset'].map(scope=>({parent,stage,phase,scope}))))))('clears $parent $stage $phase intent on real $scope teardown and ignores late success',async({parent,stage,phase,scope})=>{
 const ui=render(renderParent(parent));await open(parent);const pending=await begin(parent,stage)
 const signal=stage==='challenge'?vi.mocked(signatureApi.prepare).mock.calls[0][6]?.signal:vi.mocked(signatureApi.sign).mock.calls[0][7]?.signal
 if(phase==='unknown')await act(async()=>pending.unknown())
 const next=scope==='project'?{projectId:'other-project'}:scope==='identity'?{principalId:'other-signer'}:scope==='permissions'?{permissions:[]}: {resetSignal:1}
 ui.rerender(renderParent(parent,next));if(phase==='in-flight')await waitFor(()=>expect(signal?.aborted).toBe(true));await act(async()=>pending.complete())
 expect(screen.queryByRole('button',{name:'重试原签署意图'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'重新登录以确认身份'})).not.toBeInTheDocument()
 expect(screen.queryByText(/已模拟签署：/)).not.toBeInTheDocument()
})
