import {requestJson} from './projectAccess'
import type {AccessRequestOptions} from './projectAccess'

export type AssetTarget={kind:'REQUIREMENT'|'TEST_CASE'|'DEFECT';id:string}
export type AssetPage<T>={items:T[];nextCursor:string|null}
export type UploadState='CREATED'|'UPLOADED'|'SCAN_FAILED'|'PUBLISHED'|'REJECTED'
export type AssetUpload={id:string;projectId:string;assetId:string|null;revisionId:string|null;filename:string;declaredSize:number;declaredSha256:string;declaredContentType:string;actualSize:number|null;computedSha256:string|null;detectedMime:string|null;state:UploadState;cleanupPending:boolean;lastErrorCode:string|null;createdBy:string;createdAt:string;rowVersion:number}
export type AssetRevision={id:string;projectId:string;assetId:string;revisionNumber:number;filename:string;size:number;sha256:string;mime:string;scanStatus:'CLEAN';createdBy:string;createdAt:string}
export type Asset={id:string;projectId:string;currentRevisionId:string;currentRevision:AssetRevision;createdBy:string;createdAt:string}
export type Attachment={id:string;projectId:string;kind:AssetTarget['kind'];requirementId:string|null;testCaseId:string|null;defectId:string|null;assetId:string;assetRevisionId:string;revision:AssetRevision;createdBy:string;createdAt:string}
export type UploadInput={filename:string;size:number;sha256:string;contentType:string;assetId?:string}
export type UploadReceipt={uploadId:string;state:'UPLOADED';size:number;sha256:string;mime:string}
const allowed=['text/plain','application/pdf','image/png','image/jpeg']
const record=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null&&!Array.isArray(v)
const str=(v:unknown):v is string=>typeof v==='string'&&v.length>0
const nullable=(v:unknown)=>v===null||str(v)
const positive=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>0
const size=(v:unknown)=>positive(v)&&v<=26214400
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const mime=(v:unknown)=>typeof v==='string'&&allowed.includes(v)
const bad=()=>({code:'INVALID_RESPONSE',message:'附件服务返回了无效或不一致的数据'})
const checked=<T,>(v:unknown,valid:(v:unknown)=>boolean):T=>{if(!valid(v))throw bad();return v as T}
const common=(v:unknown):v is Record<string,unknown>=>record(v)&&str(v.id)&&str(v.projectId)&&str(v.createdBy)&&str(v.createdAt)&&Number.isFinite(Date.parse(v.createdAt))
function validUpload(v:unknown):boolean {
 if(!common(v)||!nullable(v.assetId)||!nullable(v.revisionId)||!str(v.filename)||!size(v.declaredSize)||!hash(v.declaredSha256)||!mime(v.declaredContentType)||!positive(v.rowVersion)||typeof v.cleanupPending!=='boolean'||!nullable(v.lastErrorCode)||!['CREATED','UPLOADED','SCAN_FAILED','PUBLISHED','REJECTED'].includes(String(v.state)))return false
 if(!(v.actualSize===null||size(v.actualSize))||!(v.computedSha256===null||hash(v.computedSha256))||!(v.detectedMime===null||mime(v.detectedMime)))return false
 if(v.state==='CREATED'&&(v.actualSize!==null||v.computedSha256!==null||v.detectedMime!==null))return false
 if(v.state!=='PUBLISHED'&&(v.revisionId!==null||v.cleanupPending))return false
 if(v.state!=='CREATED'&&(v.actualSize!==v.declaredSize||v.computedSha256!==v.declaredSha256||v.detectedMime!==v.declaredContentType))return false
 return v.state!=='PUBLISHED'||(str(v.assetId)&&str(v.revisionId))
}
const validRevision=(v:unknown)=>common(v)&&str(v.assetId)&&positive(v.revisionNumber)&&str(v.filename)&&size(v.size)&&hash(v.sha256)&&mime(v.mime)&&v.scanStatus==='CLEAN'
const validAsset=(v:unknown)=>common(v)&&str(v.currentRevisionId)&&validRevision(v.currentRevision)&&record(v.currentRevision)&&v.currentRevision.id===v.currentRevisionId&&v.currentRevision.assetId===v.id&&v.currentRevision.projectId===v.projectId
const targetFields={REQUIREMENT:'requirementId',TEST_CASE:'testCaseId',DEFECT:'defectId'} as const
const targetInput=(t:AssetTarget)=>({kind:t.kind,[targetFields[t.kind]]:t.id})
function validAttachment(v:unknown):boolean {
 if(!common(v)||!['REQUIREMENT','TEST_CASE','DEFECT'].includes(String(v.kind))||!str(v.assetId)||!str(v.assetRevisionId)||!validRevision(v.revision)||!record(v.revision)||v.revision.id!==v.assetRevisionId||v.revision.assetId!==v.assetId||v.revision.projectId!==v.projectId)return false
 return Object.entries(targetFields).every(([k,f])=>k===v.kind?str(v[f]):v[f]===null)
}
const base=(p:string)=>`/api/v1/projects/${encodeURIComponent(p)}`
const enc=encodeURIComponent
const paging=(cursor?:string|null)=>`limit=25${cursor?`&cursor=${enc(cursor)}`:''}`
const post=(input:unknown)=>({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input)})
function page<T>(body:unknown,valid:(v:unknown)=>boolean):AssetPage<T>{return checked(body,v=>record(v)&&Array.isArray(v.items)&&v.items.every(valid)&&(v.nextCursor===null||str(v.nextCursor)))}
const scoped=(p:string,valid:(v:unknown)=>boolean)=>(v:unknown)=>valid(v)&&record(v)&&v.projectId===p
export async function prepareAssetFile(file:File,assetId?:string):Promise<UploadInput> {
 if(!size(file.size)||!allowed.includes(file.type)||file.name.length<1||file.name.length>200)throw Object.assign(new Error('请选择 1 byte–25 MiB 的 TXT、PDF、PNG 或 JPEG 文件'),{code:'INVALID_REQUEST',status:400})
 const bytes=await file.arrayBuffer(),digest=await crypto.subtle.digest('SHA-256',bytes)
 return {filename:file.name,size:file.size,sha256:[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join(''),contentType:file.type,...(assetId?{assetId}:{})}
}
export const assetsApi={
 async createUpload(p:string,input:UploadInput,o?:AccessRequestOptions):Promise<AssetUpload>{return checked(await requestJson(`${base(p)}/uploads`,post(input),o),scoped(p,validUpload))},
 async upload(p:string,id:string,o?:AccessRequestOptions):Promise<AssetUpload>{return checked(await requestJson(`${base(p)}/uploads/${enc(id)}`,undefined,o),v=>scoped(p,validUpload)(v)&&record(v)&&v.id===id)},
 async uploads(p:string,cursor?:string|null,o?:AccessRequestOptions):Promise<AssetPage<AssetUpload>>{return page(await requestJson(`${base(p)}/uploads?${paging(cursor)}`,undefined,o),scoped(p,validUpload))},
 async put(p:string,id:string,file:File,o?:AccessRequestOptions):Promise<UploadReceipt>{return checked(await requestJson(`${base(p)}/uploads/${enc(id)}/content`,{method:'PUT',headers:{'Content-Type':file.type},body:file},o),v=>record(v)&&v.uploadId===id&&v.state==='UPLOADED'&&size(v.size)&&hash(v.sha256)&&mime(v.mime))},
 async complete(p:string,id:string,o?:AccessRequestOptions):Promise<AssetUpload>{return checked(await requestJson(`${base(p)}/uploads/${enc(id)}/complete`,post({}),o),v=>scoped(p,validUpload)(v)&&record(v)&&v.id===id&&v.state==='PUBLISHED')},
 async list(p:string,cursor?:string|null,o?:AccessRequestOptions):Promise<AssetPage<Asset>>{return page(await requestJson(`${base(p)}/assets?${paging(cursor)}`,undefined,o),scoped(p,validAsset))},
 async asset(p:string,id:string,o?:AccessRequestOptions):Promise<Asset>{return checked(await requestJson(`${base(p)}/assets/${enc(id)}`,undefined,o),v=>scoped(p,validAsset)(v)&&record(v)&&v.id===id)},
 async revisions(p:string,id:string,cursor?:string|null,o?:AccessRequestOptions):Promise<AssetPage<AssetRevision>>{return page(await requestJson(`${base(p)}/assets/${enc(id)}/revisions?${paging(cursor)}`,undefined,o),v=>scoped(p,validRevision)(v)&&record(v)&&v.assetId===id)},
 async revision(p:string,asset:string,id:string,o?:AccessRequestOptions):Promise<AssetRevision>{return checked(await requestJson(`${base(p)}/assets/${enc(asset)}/revisions/${enc(id)}`,undefined,o),v=>scoped(p,validRevision)(v)&&record(v)&&v.assetId===asset&&v.id===id)},
 async attachments(p:string,t:AssetTarget,cursor?:string|null,o?:AccessRequestOptions):Promise<AssetPage<Attachment>>{return page(await requestJson(`${base(p)}/attachments?${new URLSearchParams({...targetInput(t),...{limit:'25'},...(cursor?{cursor}:{})})}`,undefined,o),v=>scoped(p,validAttachment)(v)&&record(v)&&v.kind===t.kind&&v[targetFields[t.kind]]===t.id)},
 async attach(p:string,t:AssetTarget,revision:AssetRevision,o?:AccessRequestOptions):Promise<Attachment>{return checked(await requestJson(`${base(p)}/attachments`,post({...targetInput(t),assetId:revision.assetId,assetRevisionId:revision.id}),o),v=>scoped(p,validAttachment)(v)&&record(v)&&v.kind===t.kind&&v[targetFields[t.kind]]===t.id&&v.assetRevisionId===revision.id)},
 async download(p:string,asset:string,revision:string,o?:AccessRequestOptions):Promise<Blob>{
  const response=await fetch(`${base(p)}/assets/${enc(asset)}/revisions/${enc(revision)}/download`,{credentials:'same-origin',signal:o?.signal})
  if(!response.ok){let error:unknown;try{error=await response.json()}catch{/* HTTP status remains authoritative. */}throw record(error)&&str(error.code)&&str(error.message)?{code:error.code,message:error.message,status:response.status}:{code:`HTTP_${response.status}`,message:`下载失败（${response.status}）`,status:response.status}}
  const length=Number(response.headers.get('Content-Length'))
  if(!mime(response.headers.get('Content-Type')?.split(';')[0])||!size(length)||response.headers.get('X-Content-Type-Options')!=='nosniff'||!response.headers.get('Content-Disposition')?.startsWith('attachment;'))throw bad()
  const blob=await response.blob();if(blob.size!==length)throw bad();return blob
 },
}
export const appendAssets=<T extends {id:string}>(old:T[],next:T[])=>[...old,...next.filter(n=>!old.some(x=>x.id===n.id))]
