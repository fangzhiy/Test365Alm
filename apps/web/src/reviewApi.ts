import { requestJson } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'
export type ReviewAction='SUBMIT'|'APPROVE'|'REJECT'
export type ReviewState='DRAFT'|'SUBMITTED'|'APPROVED'|'REJECTED'
export type ReviewCommand={action:ReviewAction;reason:string}
export type ReviewEvent={id:string;revisionId:string;revisionNo:number;rowVersion:number;action:ReviewAction;state:Exclude<ReviewState,'DRAFT'>;reviewerId:string;actorId:string;contentDigest:string;reason:string;occurredAt:string}
export type ReviewView={testCaseId:string;revisionId:string;revisionNo:number;currentRevisionId:string;rowVersion:number;contentDigest:string;reviewerId:string|null;state:ReviewState;canSubmit:boolean;canDecide:boolean;latest:ReviewEvent|null;items:ReviewEvent[];nextCursor:string|null}
const record=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v)
const positive=(v:unknown)=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0
const invalid=()=>{throw {code:'INVALID_RESPONSE',message:'复核响应无效'}}
function event(v:unknown):ReviewEvent {
 if(!record(v)||!['id','revisionId','reviewerId','actorId','contentDigest','reason','occurredAt'].every(k=>typeof v[k]==='string')
  ||!positive(v.revisionNo)||!positive(v.rowVersion)||!['SUBMIT','APPROVE','REJECT'].includes(String(v.action))
  ||!['SUBMITTED','APPROVED','REJECTED'].includes(String(v.state))||!/^[a-f0-9]{64}$/.test(String(v.contentDigest)))return invalid()
 return v as ReviewEvent
}
export function parseReview(v:unknown):ReviewView {
 if(!record(v)||!['testCaseId','revisionId','currentRevisionId','contentDigest'].every(k=>typeof v[k]==='string')||!positive(v.revisionNo)||!positive(v.rowVersion)
  ||!/^[a-f0-9]{64}$/.test(String(v.contentDigest))||!(v.reviewerId===null||typeof v.reviewerId==='string')
  ||!['DRAFT','SUBMITTED','APPROVED','REJECTED'].includes(String(v.state))||typeof v.canSubmit!=='boolean'||typeof v.canDecide!=='boolean'
  ||!Array.isArray(v.items)||v.items.length>100||!(v.nextCursor===null||typeof v.nextCursor==='string'))return invalid()
 const items=v.items.map(event),latest=v.latest===null?null:event(v.latest)
 if(new Set(items.map(e=>e.id)).size!==items.length||[...items,...(latest?[latest]:[])].some(e=>e.revisionId!==v.revisionId||e.revisionNo!==v.revisionNo||e.contentDigest!==v.contentDigest||e.reviewerId!==v.reviewerId)
  ||(latest?latest.state!==v.state:v.state!=='DRAFT'))return invalid()
 return {...v,items,latest} as ReviewView
}
const path=(p:string,t:string,r:string)=>`/api/v1/projects/${encodeURIComponent(p)}/tests/${encodeURIComponent(t)}/revisions/${encodeURIComponent(r)}/review`
export const reviewApi={
 async get(p:string,t:string,r:string,cursor:string|null=null,options?:AccessRequestOptions):Promise<ReviewView>{
  const query=new URLSearchParams({limit:'25'});if(cursor)query.set('cursor',cursor)
  const result=parseReview(await requestJson(`${path(p,t,r)}?${query}`,undefined,options))
  if(result.testCaseId!==t||result.revisionId!==r)return invalid()
  return result
 },
 async act(p:string,t:string,r:string,command:ReviewCommand,etag:string,key:string,options?:AccessRequestOptions):Promise<ReviewView>{
  const result=parseReview(await requestJson(path(p,t,r),{method:'POST',headers:{'Content-Type':'application/json','If-Match':etag},body:JSON.stringify(command)},{...options,idempotencyKey:key}))
  if(result.testCaseId!==t||result.revisionId!==r)return invalid()
  return result
 }
}
