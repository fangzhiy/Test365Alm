import { requestJson, requestJsonWithResponse } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'

export type ReleaseStatus = 'PLANNED'|'ACTIVE'|'CLOSED'|'ARCHIVED'
export type PlanningContent = {name:string;description?:string;startDate:string;endDate:string;status?:ReleaseStatus}
export type Release = PlanningContent & {id:string;projectId:string;displayNumber:number;description:string;status:ReleaseStatus;rowVersion:number;scopeVersion:number;createdBy:string;createdAt:string;updatedAt:string}
export type Cycle = PlanningContent & {id:string;projectId:string;releaseId:string;status:Exclude<ReleaseStatus,'ARCHIVED'>;rowVersion:number;createdBy:string;createdAt:string;updatedAt:string}
export type ScopeEntry = {id:string;projectId:string;releaseId:string;cycleId:string|null;kind:'REQUIREMENT'|'TEST_CASE';requirementId:string|null;requirementRevisionId:string|null;testCaseId:string|null;testRevisionId:string|null;createdBy:string;createdAt:string}
export type ScopeInput = {kind:'REQUIREMENT';requirementId:string;cycleId?:string}|{kind:'TEST_CASE';testCaseId:string;cycleId?:string}
export type Metric = {numerator:number;denominator:number}
export type QualitySnapshot = {id:string;projectId:string;releaseId:string;evaluatedAt:string;evaluatedBy:string;metricVersion:number;rule:string;scopeVersion:number;scopeFingerprint:string;requirementCount:number;coverage:Metric;plannedTests:Metric;completed:Metric;effectivePass:Metric;openDefects:Metric;evidence?:unknown}
export type ReleasePage<T> = {items:T[];nextCursor:string|null}
export type ScopeChoice = {id:string;title:string}
// A newly confirmed UUID may fall beyond an already displayed page cursor.
// Keep one identity when that later server page includes it again.
export function appendReleaseItems<T extends {id:string}>(current:T[],next:T[]):T[]{return [...new Map([...current,...next].map(v=>[v.id,v])).values()]}
const record=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null&&!Array.isArray(v)
const str=(v:unknown):v is string=>typeof v==='string'
const id=(v:unknown):v is string=>str(v)&&v.length>0
const count=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0
const positive=(v:unknown):v is number=>count(v)&&v>0
const date=(v:unknown)=>str(v)&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&!Number.isNaN(Date.parse(v))&&new Date(v).toISOString().slice(0,10)===v
const invalid=():never=>{throw {code:'INVALID_RESPONSE',message:'发布接口返回无效数据'}}
function entity(v:unknown,cycle=false):Release|Cycle {
 if(!record(v)||!['id','projectId','name','createdBy','createdAt','updatedAt'].every(k=>id(v[k]))||!date(v.startDate)||!date(v.endDate)||String(v.startDate)>String(v.endDate)||!positive(v.rowVersion)||!(cycle?['PLANNED','ACTIVE','CLOSED']:['PLANNED','ACTIVE','CLOSED','ARCHIVED']).includes(String(v.status)))return invalid()
 if(cycle?!id(v.releaseId):!positive(v.displayNumber)||!count(v.scopeVersion)||!str(v.description))return invalid()
 return v as Release|Cycle
}
function scope(v:unknown):ScopeEntry {
 if(!record(v)||!['id','projectId','releaseId','createdBy','createdAt'].every(k=>id(v[k]))||!(v.cycleId===null||id(v.cycleId)))return invalid()
 if(v.kind==='REQUIREMENT'){if(!id(v.requirementId)||!id(v.requirementRevisionId)||v.testCaseId!==null||v.testRevisionId!==null)return invalid()}
 else if(v.kind==='TEST_CASE'){if(!id(v.testCaseId)||!id(v.testRevisionId)||v.requirementId!==null||v.requirementRevisionId!==null)return invalid()}else return invalid()
 return v as ScopeEntry
}
function metric(v:unknown):Metric {if(!record(v)||!count(v.numerator)||!count(v.denominator)||v.numerator>v.denominator)return invalid();return v as Metric}
function snapshot(v:unknown):QualitySnapshot {
 if(!record(v)||!['id','projectId','releaseId','evaluatedAt','evaluatedBy','rule','scopeFingerprint'].every(k=>id(v[k]))||!positive(v.metricVersion)||!count(v.scopeVersion)||!count(v.requirementCount))return invalid()
 return {...v,coverage:metric(v.coverage),plannedTests:metric(v.plannedTests),completed:metric(v.completed),effectivePass:metric(v.effectivePass),openDefects:metric(v.openDefects)} as QualitySnapshot
}
function page<T>(v:unknown,parse:(v:unknown)=>T):ReleasePage<T>{if(!record(v)||!Array.isArray(v.items)||!(v.nextCursor===null||id(v.nextCursor)))return invalid();return {items:v.items.map(parse),nextCursor:v.nextCursor}}
const root=(p:string)=>`/api/v1/projects/${encodeURIComponent(p)}/releases`
const path=(p:string,r:string)=>`${root(p)}/${encodeURIComponent(r)}`
const params=(cursor:string|null)=>`?limit=25${cursor?`&cursor=${encodeURIComponent(cursor)}`:''}`
const json=(method:string,body:unknown,version?:number):RequestInit=>({method,headers:{'Content-Type':'application/json',...(version?{'If-Match':`"${version}"`}:{})},body:JSON.stringify(body)})
export const planningContent=(v:PlanningContent,cycle=false,create=false):PlanningContent=>({name:v.name,...(!cycle?{description:v.description??''}:{}),startDate:v.startDate,endDate:v.endDate,...(!create&&v.status?{status:v.status}:{})})
export const emptyPlanning=():PlanningContent=>({name:'',description:'',startDate:'',endDate:''})
async function tagged<T extends Release|Cycle>(url:string,init:RequestInit|undefined,cycle:boolean,o?:AccessRequestOptions):Promise<T>{const {body,response}=await requestJsonWithResponse(url,init,o);const value=entity(body,cycle);if(response.headers.get('ETag')!==`"${value.rowVersion}"`)return invalid();return value as T}
export const releasesApi={
 list:async(p:string,c:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(root(p)+params(c),undefined,o),v=>entity(v) as Release),
 get:(p:string,r:string,o?:AccessRequestOptions)=>tagged<Release>(path(p,r),undefined,false,o),
 create:(p:string,v:PlanningContent,o?:AccessRequestOptions)=>tagged<Release>(root(p),json('POST',planningContent(v,false,true)),false,o),
 update:(p:string,r:Release,v:PlanningContent,o?:AccessRequestOptions)=>tagged<Release>(path(p,r.id),json('PATCH',planningContent(v),r.rowVersion),false,o),
 cycles:async(p:string,r:string,c:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(path(p,r)+'/cycles'+params(c),undefined,o),v=>entity(v,true) as Cycle),
 cycle:(p:string,r:string,c:string,o?:AccessRequestOptions)=>tagged<Cycle>(`${path(p,r)}/cycles/${encodeURIComponent(c)}`,undefined,true,o),
 createCycle:(p:string,r:string,v:PlanningContent,o?:AccessRequestOptions)=>tagged<Cycle>(path(p,r)+'/cycles',json('POST',planningContent(v,true,true)),true,o),
 updateCycle:(p:string,r:string,c:Cycle,v:PlanningContent,o?:AccessRequestOptions)=>tagged<Cycle>(`${path(p,r)}/cycles/${c.id}`,json('PATCH',planningContent(v,true),c.rowVersion),true,o),
 scope:async(p:string,r:string,c:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(path(p,r)+'/scope'+params(c),undefined,o),scope),
 addScope:async(p:string,r:string,v:ScopeInput,o?:AccessRequestOptions)=>scope(await requestJson(path(p,r)+'/scope',json('POST',v),o)),
 removeScope:async(p:string,r:string,s:string,o?:AccessRequestOptions)=>{const v=await requestJson(`${path(p,r)}/scope/${s}`,{method:'DELETE'},o);if(!record(v)||v.id!==s||v.removed!==true)return invalid();return v},
 snapshots:async(p:string,r:string,c:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(path(p,r)+'/snapshots'+params(c),undefined,o),snapshot),
 snapshot:async(p:string,r:string,s:string,o?:AccessRequestOptions)=>snapshot(await requestJson(`${path(p,r)}/snapshots/${s}`,undefined,o)),
 evaluate:async(p:string,r:string,o?:AccessRequestOptions)=>snapshot(await requestJson(path(p,r)+'/milestones/evaluate',json('POST',{}),o)),
 choices:async(p:string,kind:'REQUIREMENT'|'TEST_CASE',c:string|null=null,o?:AccessRequestOptions)=>page(await requestJson(`/api/v1/projects/${encodeURIComponent(p)}/${kind==='REQUIREMENT'?'requirements':'tests'}${params(c)}`,undefined,o),v=>{if(!record(v)||!id(v.id)||!id(v.title))return invalid();return {id:v.id,title:v.title}}),
}
