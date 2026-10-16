import { requestJson, requestJsonWithResponse } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'
export type DefectStatus = 'OPEN'|'IN_PROGRESS'|'RESOLVED'|'CLOSED'|'REOPENED'
export type DefectContent = { title: string; description: string; severity: string; priority: string }
export const emptyDefect = ():DefectContent=>({title:'',description:'',severity:'MEDIUM',priority:'MEDIUM'})
export const defectContent = (v:DefectContent):DefectContent=>({title:v.title,description:v.description,severity:v.severity,priority:v.priority})
export type DefectRevision = DefectContent & { id: string; defectId: string; revisionNo: number; status: DefectStatus; reason: string; createdBy: string; createdAt: string }
export type DefectSource = { runId: string; attemptId: string; stepKey: string }
export type DefectLink = { id: string; kind: 'RUN_STEP'|'REQUIREMENT'; requirementId: string|null; requirementRevisionId: string|null; testCaseId: string|null; testRevisionId: string|null; runId: string|null; attemptId: string|null; stepKey: string|null; actualResult: string|null; expectedResult: string|null; sourceConclusion: string|null; sourceVersion: number|null }
export type Defect = { id: string; projectId: string; displayNumber: number; rowVersion: number; createdBy: string; createdAt: string; currentRevision: DefectRevision; links: DefectLink[] }
export type Verification = { eligible: boolean; runId: string|null; attemptId: string|null; reason: string }
export type Retest = { id: string; runId: string; attemptId: string; resolvedRevisionId: string; attemptNo: number; status: string; conclusion: string|null }
export type DefectPage<T> = { items: T[]; nextCursor: string|null }
const record = (v: unknown): v is Record<string,unknown> => typeof v==='object' && v!==null && !Array.isArray(v)
const str = (v: unknown): v is string => typeof v==='string'
const id = (v: unknown): v is string => str(v) && v.length>0
const integer = (v: unknown): v is number => typeof v==='number' && Number.isSafeInteger(v) && v>0
const statuses = ['OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED']
const levels = ['LOW','MEDIUM','HIGH','CRITICAL']
const invalid = (): never => { throw {code:'INVALID_RESPONSE',message:'缺陷接口返回了无效数据'} }
function revision(v: unknown): DefectRevision {
 if(!record(v)||!['id','defectId','createdBy','createdAt'].every(k=>id(v[k]))||!['title','description','reason'].every(k=>str(v[k]))||!integer(v.revisionNo)||!levels.includes(String(v.severity))||!levels.includes(String(v.priority))||!statuses.includes(String(v.status)))return invalid()
 return v as DefectRevision
}
function link(v: unknown): DefectLink {
 if(!record(v)||!id(v.id)||!['RUN_STEP','REQUIREMENT'].includes(String(v.kind)))return invalid()
 if(v.kind==='RUN_STEP' && (!['testCaseId','testRevisionId','runId','attemptId','manifestId','stepKey'].every(k=>id(v[k]))||!str(v.actualResult)||!str(v.expectedResult)||!['FAIL','BLOCKED'].includes(String(v.sourceConclusion))||!integer(v.sourceVersion)))return invalid()
 if(v.kind==='REQUIREMENT' && (!id(v.requirementId)||!id(v.requirementRevisionId)))return invalid()
 return v as DefectLink
}
function defect(v: unknown): Defect {
 if(!record(v)||!['id','projectId','createdBy','createdAt'].every(k=>id(v[k]))||!integer(v.displayNumber)||!integer(v.rowVersion)||!Array.isArray(v.links))return invalid()
 const r=revision(v.currentRevision);if(r.defectId!==v.id||r.revisionNo!==v.rowVersion)return invalid()
 return {...v,currentRevision:r,links:v.links.map(link)} as Defect
}
function page<T>(v: unknown,parse:(v:unknown)=>T): DefectPage<T> {
 if(!record(v)||!Array.isArray(v.items)||!(v.nextCursor===null||id(v.nextCursor)))return invalid()
 return {items:v.items.map(parse),nextCursor:v.nextCursor}
}
const root = (p:string)=>`/api/v1/projects/${encodeURIComponent(p)}/defects`
const params = (cursor:string|null,q?:string)=>`?limit=25${q!==undefined?`&q=${encodeURIComponent(q)}`:''}${cursor?`&cursor=${encodeURIComponent(cursor)}`:''}`
const json = (method:string,body:unknown,version?:number):RequestInit=>({method,headers:{'Content-Type':'application/json',...(version?{'If-Match':`"${version}"`}:{})},body:JSON.stringify(body)})
async function tagged(path:string,init:RequestInit|undefined,o?:AccessRequestOptions) {
 const {body,response}=await requestJsonWithResponse(path,init,o);const d=defect(body)
 if(response.headers.get('ETag')!==`"${d.rowVersion}"`)return invalid()
 return d
}
export const defectsApi = {
 list: async(p:string,q='',cursor:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(root(p)+params(cursor,q),undefined,o),defect),
 get:(p:string,d:string,o?:AccessRequestOptions)=>tagged(`${root(p)}/${d}`,undefined,o),
 create:(p:string,c:DefectContent,source:DefectSource|null,o?:AccessRequestOptions)=>tagged(`${root(p)}${source?'/from-step':''}`,json('POST',{...defectContent(c),...source}),o),
 edit:(p:string,d:Defect,c:DefectContent,o?:AccessRequestOptions)=>tagged(`${root(p)}/${d.id}`,json('PATCH',defectContent(c),d.rowVersion),o),
 transition:(p:string,d:Defect,status:DefectStatus,reason:string,o?:AccessRequestOptions)=>tagged(`${root(p)}/${d.id}/transitions`,json('POST',{status,reason},d.rowVersion),o),
 history:async(p:string,d:string,cursor:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(`${root(p)}/${d}/revisions${params(cursor)}`,undefined,o),revision),
 revision:async(p:string,d:string,r:string,o?:AccessRequestOptions)=>revision(await requestJson(`${root(p)}/${d}/revisions/${r}`,undefined,o)),
 verification:async(p:string,d:string,o?:AccessRequestOptions):Promise<Verification>=>{
  const v=await requestJson(`${root(p)}/${d}/verification`,undefined,o)
  if(!record(v)||typeof v.eligible!=='boolean'||!str(v.reason)||!(v.runId===null||id(v.runId))||!(v.attemptId===null||id(v.attemptId))||v.eligible&&(!id(v.runId)||!id(v.attemptId)))return invalid()
  return v as Verification
 },
 retest:async(p:string,d:Defect,o?:AccessRequestOptions):Promise<{runId:string;attemptId:string}>=>{
  const v=await requestJson(`${root(p)}/${d.id}/retests`,json('POST',{},d.rowVersion),o);if(!record(v)||!id(v.runId)||!id(v.attemptId))return invalid();return {runId:v.runId,attemptId:v.attemptId}
 },
 retests:async(p:string,d:string,cursor:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(`${root(p)}/${d}/retests${params(cursor)}`,undefined,o),v=>{
  if(!record(v)||!['id','runId','attemptId','resolvedRevisionId'].every(k=>id(v[k]))||!integer(v.attemptNo)||!['RUNNING','PAUSED','FINISHED'].includes(String(v.status))||!(v.conclusion===null||['PASS','FAIL','BLOCKED'].includes(String(v.conclusion))))return invalid();return v as Retest
 }),
}
