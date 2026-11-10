import { requestJson } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'
export type SignatureKind='TEST_APPROVAL'|'RUN_COMPLETION'|'DEVIATION_CLOSURE'
export const signatureMeanings:Record<SignatureKind,string>={TEST_APPROVAL:'用例批准确认',RUN_COMPLETION:'运行完成确认',DEVIATION_CLOSURE:'偏差关闭确认'}
export type SignatureRecord={id:string;schema:'SIGNATURE_RECORD_V1';projectId:string;kind:SignatureKind;meaning:string;objectId:string;snapshotId:string;sourceVersion:number;actorId:string;issuer:string;subject:string;displayName:string;authenticatedAt:string;signedAt:string;contentDigest:string;contentSnapshot:Record<string,unknown>;evidence:{provider:'SIMULATED_V1';simulated:true;reference:string;bindingDigest:string}}
export type SignatureView={kind:SignatureKind;objectId:string;snapshotId:string;rowVersion:number;state:string;canSign:boolean;contentDigest:string;signature:SignatureRecord|null}
export type SignatureChallenge={id:string;expiresAt:string;authenticationUrl:string}
const record=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v)
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0
const digest=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const date=(v:unknown):v is string=>typeof v==='string'&&Number.isFinite(Date.parse(v))
const invalid=():never=>{throw {code:'INVALID_RESPONSE',message:'模拟签署响应无效'}}
const path=(p:string,k:SignatureKind,o:string,s:string)=>`/api/v1/projects/${encodeURIComponent(p)}/signatures/${k}/${encodeURIComponent(o)}/${encodeURIComponent(s)}`
function scoped(v:unknown,p:string,k:SignatureKind,o:string,s:string):SignatureView {
 if(!record(v)||v.kind!==k||v.objectId!==o||v.snapshotId!==s||!positive(v.rowVersion)||typeof v.state!=='string'||typeof v.canSign!=='boolean'||!digest(v.contentDigest))return invalid()
 if(v.canSign&&v.state!==({TEST_APPROVAL:'APPROVED',RUN_COMPLETION:'FINISHED',DEVIATION_CLOSURE:'CLOSED'}[k]))return invalid()
 if(v.signature!==null){
  const r=v.signature
  if(!record(r)||r.schema!=='SIGNATURE_RECORD_V1'||r.projectId!==p||r.kind!==k||r.objectId!==o||r.snapshotId!==s||r.meaning!==signatureMeanings[k]
   ||!['id','actorId','issuer','subject','displayName'].every(key=>typeof r[key]==='string'&&r[key]!== '')||!positive(r.sourceVersion)
   ||!date(r.authenticatedAt)||!date(r.signedAt)||r.contentDigest!==v.contentDigest||!record(r.contentSnapshot)
   ||r.contentSnapshot.schema!=='SIGNATURE_SNAPSHOT_V1'||r.contentSnapshot.projectId!==p||r.contentSnapshot.kind!==k||r.contentSnapshot.objectId!==o||r.contentSnapshot.snapshotId!==s
   ||!record(r.evidence)||r.evidence.provider!=='SIMULATED_V1'||r.evidence.simulated!==true||r.evidence.reference!==r.id||!digest(r.evidence.bindingDigest)||v.canSign)return invalid()
 }
 return v as SignatureView
}
export const signatureApi={
 async get(p:string,k:SignatureKind,o:string,s:string,options?:AccessRequestOptions):Promise<SignatureView>{return scoped(await requestJson(path(p,k,o,s),undefined,options),p,k,o,s)},
 async prepare(p:string,k:SignatureKind,o:string,s:string,etag:string,key:string,options?:AccessRequestOptions):Promise<SignatureChallenge>{
  const result=await requestJson(`${path(p,k,o,s)}/challenges`,{method:'POST',headers:{'Content-Type':'application/json','If-Match':etag},body:'{}'},{...options,idempotencyKey:key})
  if(!record(result)||typeof result.id!=='string'||!date(result.expiresAt)||result.authenticationUrl!==`${path(p,k,o,s)}/challenges/${encodeURIComponent(result.id)}/authenticate`)return invalid()
  return result as SignatureChallenge
 },
 async sign(p:string,k:SignatureKind,o:string,s:string,body:{challengeId:string},etag:string,key:string,options?:AccessRequestOptions):Promise<SignatureView>{
  return scoped(await requestJson(path(p,k,o,s),{method:'POST',headers:{'Content-Type':'application/json','If-Match':etag},body:JSON.stringify(body)},{...options,idempotencyKey:key}),p,k,o,s)
 }
}
