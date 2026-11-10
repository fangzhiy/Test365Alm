import '@testing-library/jest-dom/vitest'
import { act,cleanup,fireEvent,render,screen,waitFor } from '@testing-library/react'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import TestCasesPanel from './TestCasesPanel'
import { testsApi,type TestCase } from './tests'
import { signatureApi } from './signaturesApi'
vi.mock('./tests',()=>({testsApi:{list:vi.fn(),get:vi.fn(),revisions:vi.fn(),appendRevision:vi.fn()}}))
vi.mock('./reviewApi',()=>({reviewApi:{get:vi.fn().mockResolvedValue({testCaseId:'test',revisionId:'revision',revisionNo:1,currentRevisionId:'revision',rowVersion:3,contentDigest:'a'.repeat(64),reviewerId:'author',state:'APPROVED',canSubmit:false,canDecide:false,latest:null,items:[],nextCursor:null})}}))
vi.mock('./signaturesApi',async()=>({...await vi.importActual<typeof import('./signaturesApi')>('./signaturesApi'),signatureApi:{get:vi.fn(),prepare:vi.fn(),sign:vi.fn()}}))
vi.mock('./BusinessFieldsForm',()=>({default:()=>null}));vi.mock('./AttachmentsPanel',()=>({default:()=>null}));vi.mock('./HistoryPanel',()=>({default:()=>null}));vi.mock('./ReviewerPicker',()=>({default:()=>null}))
let lateMoved:(()=>void)|undefined
vi.mock('./FolderTree',()=>({default:({onMoved}:{onMoved:()=>void})=>{lateMoved=onMoved;return <span>Folders</span>}}))
const access={tenantId:'tenant',projectId:'project',principalId:'author',roles:['PROJECT_MEMBER'] as const,permissions:['test:read','test:create','test:update','test:history:read']}
const saved:TestCase={id:'test',projectId:'project',displayNumber:'TC-1',testType:'MANUAL',rowVersion:3,etag:'"3"',currentRevisionId:'revision',revisionNumber:1,title:'Original',description:'',preconditions:'',createdAt:'2026-11-11T00:00:00Z',createdBy:'author',steps:[]}
const other={...saved,id:'other',displayNumber:'TC-2',currentRevisionId:'other-revision'}
const view={kind:'TEST_APPROVAL' as const,objectId:'test',snapshotId:'revision',rowVersion:3,state:'APPROVED',canSign:true,contentDigest:'a'.repeat(64),signature:null}
const challenge={id:'challenge',expiresAt:'2026-11-11T12:05:00Z',authenticationUrl:'/api/authenticate'}
const deferred=<T,>()=>{let resolve!:(v:T)=>void,reject!:(e:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
beforeEach(()=>{
 vi.mocked(testsApi.list).mockReset().mockResolvedValue({items:[saved,other],nextCursor:null});vi.mocked(testsApi.get).mockReset().mockResolvedValue(saved);vi.mocked(testsApi.revisions).mockReset().mockResolvedValue([])
 vi.mocked(signatureApi.get).mockReset().mockResolvedValue(view);vi.mocked(signatureApi.prepare).mockReset();vi.mocked(signatureApi.sign).mockReset()
})
afterEach(cleanup)
async function open(){fireEvent.click(await screen.findByRole('button',{name:/TC-1/}));fireEvent.click(await screen.findByRole('button',{name:'查看模拟签署'}));await screen.findByRole('button',{name:'开始模拟签署'})}
it.each(['same case','other case','query Enter','refresh','late folder callback'].flatMap(action=>['unknown','in-flight'].map(phase=>({action,phase}))))('keeps signature intent mounted during $action while $phase',async({action,phase})=>{
 const d=deferred<typeof challenge>();vi.mocked(signatureApi.prepare).mockReturnValueOnce(d.promise).mockResolvedValue(challenge)
 render(<TestCasesPanel projectId="project" access={access}/>);await open();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));await waitFor(()=>expect(signatureApi.prepare).toHaveBeenCalledTimes(1))
 if(phase==='unknown'){await act(async()=>d.reject(new Error('unknown outcome')));await screen.findByRole('button',{name:'重试原签署意图'})}
 const gets=vi.mocked(testsApi.get).mock.calls.length,lists=vi.mocked(testsApi.list).mock.calls.length
 if(action==='query Enter')fireEvent.submit(screen.getByRole('textbox',{name:'查询测试用例'}).closest('form')!)
 else if(action==='late folder callback')act(()=>lateMoved?.())
 else fireEvent.click(screen.getByRole('button',{name:action==='same case'?/TC-1/:action==='other case'?/TC-2/:'刷新用例'}))
 expect(testsApi.get).toHaveBeenCalledTimes(gets);expect(testsApi.list).toHaveBeenCalledTimes(lists);expect(screen.getByLabelText('编辑测试用例标题')).toBeDisabled()
 if(phase==='in-flight')await act(async()=>d.reject(new Error('unknown outcome')))
 fireEvent.click(await screen.findByRole('button',{name:'重试原签署意图'}));await screen.findByRole('button',{name:'重新登录以确认身份'})
 const calls=vi.mocked(signatureApi.prepare).mock.calls;expect(calls[0].slice(0,6)).toEqual(calls[1].slice(0,6));expect(calls[0][4]).toBe('"3"')
})
it.each(['project','identity','viewer','reset'])('clears signature pending intent on real %s teardown and ignores late success',async(scope)=>{
 const d=deferred<typeof challenge>();vi.mocked(signatureApi.prepare).mockReturnValue(d.promise)
 const ui=render(<TestCasesPanel projectId="project" access={access}/>);await open();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));await waitFor(()=>expect(signatureApi.prepare).toHaveBeenCalledTimes(1))
 const signal=vi.mocked(signatureApi.prepare).mock.calls[0][6]?.signal
 const project=scope==='project'?'new-project':'project',next={...access,projectId:project,principalId:scope==='identity'?'other-author':access.principalId,permissions:scope==='viewer'?['test:read']:access.permissions}
 ui.rerender(<TestCasesPanel projectId={project} access={next} resetSignal={scope==='reset'?1:0}/>);await waitFor(()=>expect(signal?.aborted).toBe(true));await act(async()=>d.resolve(challenge));expect(screen.queryByRole('button',{name:'重新登录以确认身份'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'重试原签署意图'})).not.toBeInTheDocument()
})
