import {useCallback,useEffect,useRef,useState} from 'react'
import type {ProjectAccess} from './projectAccess'
import {appendAssets,assetsApi,prepareAssetFile} from './assetsApi'
import type {Asset,AssetPage,AssetRevision,AssetTarget,AssetUpload,Attachment,UploadInput} from './assetsApi'
import {useCoreRequest} from './useCoreRequest'
import {newIdempotencyKey} from './requirements'

type Props={projectId:string;target:AssetTarget;access:ProjectAccess|null;resetSignal?:number;onDenied?:()=>void}
const empty=<T,>():AssetPage<T>=>({items:[],nextCursor:null})
const labels={CREATED:'未上传',UPLOADED:'已上传，等待扫描',SCAN_FAILED:'扫描失败，可重试',PUBLISHED:'发布已确认',REJECTED:'文件被拒绝，不可发布'}
export default function AttachmentsPanel(props:Props) {
 const {projectId,target,access,resetSignal=0}=props
 if(!access||access.projectId!==projectId||!access.permissions.includes('attachment:read')||!access.permissions.includes('asset:read'))return null
 return <Boundary key={`${projectId}:${target.kind}:${target.id}:${access.principalId}:${[...access.permissions].sort().join(',')}:${resetSignal}`} {...props} access={access}/>
}
function Boundary(props:Props&{access:ProjectAccess}) {
 const [denied,setDenied]=useState(false),callback=props.onDenied
 const onDenied=useCallback(()=>{setDenied(true);callback?.()},[callback])
 return <section aria-label="附件与资产" className="requirement-history"><h4>附件与不可变资产版本</h4>{denied?<p role="alert">当前附件范围已失效，请重新取得项目权限。</p>:<AttachmentScope {...props} onDenied={onDenied}/>}</section>
}
function AttachmentScope({projectId,target,access,onDenied}:Props&{access:ProjectAccess;onDenied:()=>void}) {
 const read=useCoreRequest(),download=useCoreRequest(),readRun=read.run
 const [links,setLinks]=useState(empty<Attachment>),[uploads,setUploads]=useState(empty<AssetUpload>),[assets,setAssets]=useState(empty<Asset>)
 const [selected,setSelected]=useState<string|null>(null),[publishedRevision,setPublishedRevision]=useState<string|null>(null),[detailRefresh,setDetailRefresh]=useState(0),[recovered,setRecovered]=useState<AssetUpload|null>(null),[uploadEpoch,setUploadEpoch]=useState(0),[uploadLocked,setUploadLocked]=useState(false),[uploadBusy,setUploadBusy]=useState(false),[detailLocked,setDetailLocked]=useState(false)
 const urlRef=useRef<string|null>(null),urlTimer=useRef<number|undefined>(undefined)
 const revoke=useCallback(()=>{if(urlRef.current){URL.revokeObjectURL(urlRef.current);urlRef.current=null}window.clearTimeout(urlTimer.current)},[])
 useEffect(()=>revoke,[revoke])
 useEffect(()=>{if(read.denied||download.denied)onDenied()},[read.denied,download.denied,onDenied])
 const targetKind=target.kind,targetId=target.id
 const load=useCallback((cursor:string|null=null)=>readRun(signal=>assetsApi.attachments(projectId,{kind:targetKind,id:targetId},cursor,{signal}),page=>setLinks(old=>({...page,items:cursor?appendAssets(old.items,page.items):page.items}))),[readRun,projectId,targetKind,targetId])
 useEffect(()=>{void load()},[load])
 const locked=read.busy||uploadLocked||detailLocked
 const add=(link:Attachment)=>setLinks(old=>({...old,items:appendAssets(old.items,[link])}))
 const getDownload=(revision:AssetRevision)=>download.run(signal=>assetsApi.download(projectId,revision.assetId,revision.id,{signal}),blob=>{
  revoke();const url=URL.createObjectURL(blob);urlRef.current=url;const anchor=document.createElement('a');anchor.href=url;anchor.download=revision.filename;document.body.append(anchor);anchor.click();anchor.remove();urlTimer.current=window.setTimeout(revoke,0)
 })
 return <>
  <p>附件固定引用发布修订；新版本不会替换已有附件。下载每次重新检查权限。单文件最大 25 MiB。</p>
  <button type="button" disabled={locked} onClick={()=>void load()}>刷新附件</button>
  <ul aria-label="已关联附件">{links.items.map(link=><li key={link.id}><strong>{link.revision.filename}</strong> · 冻结修订 {link.revision.revisionNumber} · {link.revision.size} bytes · {link.revision.mime} · SHA {link.revision.sha256.slice(0,12)} · CLEAN · {link.createdAt}
   <button type="button" disabled={locked} onClick={()=>{setSelected(link.assetId);setPublishedRevision(null)}}>查看资产 {link.revision.filename}</button>
   {access.permissions.includes('asset:download')&&<button type="button" disabled={download.busy} onClick={()=>void getDownload(link.revision)}>下载冻结附件 {link.revision.filename} 修订 {link.revision.revisionNumber}</button>}
  </li>)}</ul>
  {links.nextCursor&&<button type="button" disabled={locked} onClick={()=>void load(links.nextCursor)}>更多附件</button>}
  <button type="button" disabled={locked} onClick={()=>void read.run(signal=>assetsApi.list(projectId,null,{signal}),setAssets)}>选择已有资产</button>
  {assets.items.map(asset=><button type="button" key={asset.id} disabled={locked} onClick={()=>{setSelected(asset.id);setPublishedRevision(null)}}>资产 {asset.currentRevision.filename} · 修订 {asset.currentRevision.revisionNumber}</button>)}
  {assets.nextCursor&&<button type="button" disabled={locked} onClick={()=>void read.run(signal=>assetsApi.list(projectId,assets.nextCursor,{signal}),page=>setAssets(old=>({...page,items:appendAssets(old.items,page.items)})))}>更多资产</button>}
  {access.permissions.includes('asset:upload')&&<>
   <AssetUploader key={`new:${uploadEpoch}`} projectId={projectId} initial={recovered} disabled={detailLocked} onLock={setUploadLocked} onBusy={setUploadBusy} onDenied={onDenied} onPublished={(id,revisionId)=>{setSelected(id);setPublishedRevision(revisionId);setDetailRefresh(n=>n+1)}}/>
   <button type="button" disabled={read.busy||uploadBusy||detailLocked} onClick={()=>void read.run(signal=>assetsApi.uploads(projectId,null,{signal}),setUploads)}>恢复本人上传会话</button>
   <p>切换范围后可从本人会话恢复服务器状态；不会自动建立新上传。</p>
   {uploads.items.map(upload=><button type="button" key={upload.id} disabled={read.busy||uploadBusy||detailLocked} onClick={()=>{setRecovered(upload);setUploadEpoch(n=>n+1)}}>恢复 {upload.filename} · {labels[upload.state]}</button>)}
   {uploads.nextCursor&&<button type="button" disabled={read.busy||uploadBusy||detailLocked} onClick={()=>void read.run(signal=>assetsApi.uploads(projectId,uploads.nextCursor,{signal}),page=>setUploads(old=>({...page,items:appendAssets(old.items,page.items)})))}>更多本人会话</button>}
  </>}
  {selected&&<AssetDetail key={selected} projectId={projectId} id={selected} target={target} access={access} publishedRevision={publishedRevision} refreshTrigger={detailRefresh} disabled={uploadLocked} onDenied={onDenied} onLock={setDetailLocked} onAttach={add} onDownload={getDownload}/>}
  <p role="status">{read.busy?'附件读取中…':read.message}</p><p role="status">{download.busy?'下载中…':download.message}</p>
 </>
}

type UploadIntent={kind:'create';key:string;file:File;input?:UploadInput}|{kind:'put';uploadId:string;file:File}|{kind:'complete';uploadId:string;key:string}
function AssetUploader({projectId,assetId,initial=null,disabled=false,onLock,onBusy,onDenied,onPublished}:{projectId:string;assetId?:string;initial?:AssetUpload|null;disabled?:boolean;onLock:(v:boolean)=>void;onBusy?:(v:boolean)=>void;onDenied:()=>void;onPublished:(assetId:string,revisionId:string)=>void}) {
 const write=useCoreRequest(),read=useCoreRequest(),[file,setFile]=useState<File|null>(null),[upload,setUpload]=useState<AssetUpload|null>(initial),[intent,setIntent]=useState<UploadIntent|null>(null),[unknown,setUnknown]=useState(false),[confirmed,setConfirmed]=useState(''),[epoch,setEpoch]=useState(0)
 const locked=write.busy||intent!==null,allLocked=disabled||write.busy||read.busy
 useEffect(()=>{onLock(locked);return()=>onLock(false)},[locked,onLock])
 useEffect(()=>{onBusy?.(write.busy);return()=>onBusy?.(false)},[write.busy,onBusy])
 useEffect(()=>{if(read.denied||write.denied)onDenied()},[read.denied,write.denied,onDenied])
 const execute=async(command?:UploadIntent)=>{
  const op=intent??command;if(!op)return;setIntent(op);setUnknown(false)
  const result=await write.run(async signal=>{
   if(op.kind==='create'){
    // The snapshot and key are retained even when a response is lost. No follow-up HTTP after cancellation.
    const input=op.input??await prepareAssetFile(op.file,assetId);op.input=input;if(signal.aborted)throw new DOMException('Cancelled','AbortError')
    return {upload:await assetsApi.createUpload(projectId,input,{signal,idempotencyKey:op.key})}
   }
   if(op.kind==='put'){
    const input=await prepareAssetFile(op.file,assetId);if(signal.aborted)throw new DOMException('Cancelled','AbortError')
    if(!upload||input.size!==upload.declaredSize||input.sha256!==upload.declaredSha256||input.contentType!==upload.declaredContentType)throw {status:422,code:'CONTENT_MISMATCH',message:'所选文件与原上传会话不符，请选择原文件'}
    const receipt=await assetsApi.put(projectId,op.uploadId,op.file,{signal})
    if(receipt.size!==input.size||receipt.sha256!==input.sha256||receipt.mime!==input.contentType)throw {code:'INVALID_RESPONSE',message:'上传回执与原文件不一致，请读取原会话恢复'}
    return {receipt}
   }
   return {upload:await assetsApi.complete(projectId,op.uploadId,{signal,idempotencyKey:op.key})}
  },result=>{
   if(result.upload){setUpload(result.upload);setConfirmed(result.upload.state==='PUBLISHED'?'发布已确认':'上传会话已确认')}
   if(result.receipt){const receipt=result.receipt;setUpload(old=>old?{...old,state:'UPLOADED',actualSize:receipt.size,computedSha256:receipt.sha256,detectedMime:receipt.mime}:old);setConfirmed('文件上传已确认')}
  })
  if(result==='ok'||result==='rejected'){setIntent(null);setUnknown(false)}else if(result==='unknown')setUnknown(true)
 }
 const recover=()=>upload&&read.run(signal=>assetsApi.upload(projectId,upload.id,{signal}),latest=>{
  setUpload(latest)
  const resolved=latest.state==='PUBLISHED'||latest.state==='REJECTED'||(intent?.kind==='put'&&latest.state!=='CREATED')
  if(resolved){setIntent(null);setUnknown(false);write.setMessage('')}
  if(latest.state==='PUBLISHED')setConfirmed('发布已确认')
 })
 const reset=()=>{setFile(null);setUpload(null);setIntent(null);setUnknown(false);setConfirmed('');setEpoch(n=>n+1);write.setMessage('');read.setMessage('')}
 return <section aria-label={assetId?'上传资产新版本':'新附件上传'}>
  <h5>{assetId?'为当前资产追加新版本':'上传新资产'}</h5>
  <label>{assetId?'选择新版本文件':'选择附件文件'}<input key={epoch} aria-label={assetId?'选择新版本文件':'选择附件文件'} type="file" accept="text/plain,application/pdf,image/png,image/jpeg" disabled={allLocked||intent!==null||upload?.state==='PUBLISHED'||upload?.state==='REJECTED'} onChange={e=>setFile(e.target.files?.[0]??null)}/></label>
  {file&&<p>{file.name} · {file.size} bytes · 声明 {file.type||'未知'}</p>}
  {!upload&&<button type="button" disabled={allLocked||intent!==null||!file} onClick={()=>file&&void execute({kind:'create',key:newIdempotencyKey(),file})}>创建上传会话</button>}
  {upload&&<><p>{labels[upload.state]}</p><p>上传标识 {upload.id} · {upload.filename} · {upload.declaredSize} bytes · {upload.detectedMime??upload.declaredContentType}</p>
   {upload.state==='CREATED'&&<button type="button" disabled={allLocked||intent!==null||!file} onClick={()=>file&&void execute({kind:'put',uploadId:upload.id,file})}>上传文件内容</button>}
   {(upload.state==='UPLOADED'||upload.state==='SCAN_FAILED')&&<button type="button" disabled={allLocked||intent!==null} onClick={()=>void execute({kind:'complete',uploadId:upload.id,key:newIdempotencyKey()})}>扫描并发布</button>}
   <button type="button" disabled={allLocked} onClick={()=>void recover()}>读取上传状态</button>
   {upload.state==='PUBLISHED'&&upload.assetId&&upload.revisionId&&<button type="button" disabled={allLocked} onClick={()=>onPublished(upload.assetId!,upload.revisionId!)}>读取已发布版本</button>}
   {upload.cleanupPending&&<p>发布已生效；隔离对象清理待处理。</p>}{upload.lastErrorCode&&<p>状态原因：{upload.lastErrorCode}</p>}
  </>}
  {unknown&&<p role="status">结果未知：保留原操作、文件和键；先读取原会话状态，或明确重试原操作。</p>}
  {intent&&!write.busy&&<button type="button" disabled={disabled||read.busy} onClick={()=>void execute()}>重试原上传操作</button>}
  {upload&&!intent&&!write.busy&&(upload.state==='PUBLISHED'||upload.state==='REJECTED')&&<button type="button" disabled={disabled||read.busy} onClick={reset}>开始新的上传意图</button>}
  {confirmed&&confirmed!=='发布已确认'&&<p role="status">{confirmed}</p>}
  <p role="status">{write.busy?(intent?.kind==='complete'?'扫描中…':intent?.kind==='put'?'文件上传中…':'创建上传会话中…'):write.message}</p><p role="status">{read.busy?'恢复上传状态中…':read.message}</p>
 </section>
}

function AssetDetail({projectId,id,target,access,disabled,publishedRevision,refreshTrigger,onDenied,onLock,onAttach,onDownload}:{projectId:string;id:string;target:AssetTarget;access:ProjectAccess;disabled:boolean;publishedRevision:string|null;refreshTrigger:number;onDenied:()=>void;onLock:(v:boolean)=>void;onAttach:(v:Attachment)=>void;onDownload:(v:AssetRevision)=>unknown}) {
 const read=useCoreRequest(),write=useCoreRequest(),readRun=read.run
 const [asset,setAsset]=useState<Asset|null>(null),[revisions,setRevisions]=useState(empty<AssetRevision>),[selected,setSelected]=useState<AssetRevision|null>(null),[intent,setIntent]=useState<{key:string;revision:AssetRevision}|null>(null),[uploadLocked,setUploadLocked]=useState(false),[confirmed,setConfirmed]=useState(false),[refresh,setRefresh]=useState(0),[versionRevision,setVersionRevision]=useState<string|null>(null)
 const locked=disabled||read.busy||write.busy||intent!==null||uploadLocked
 useEffect(()=>{onLock(write.busy||intent!==null||uploadLocked);return()=>onLock(false)},[write.busy,intent,uploadLocked,onLock])
 useEffect(()=>{if(read.denied||write.denied)onDenied()},[read.denied,write.denied,onDenied])
 const frozenRevision=versionRevision??publishedRevision
 const load=useCallback(()=>readRun(signal=>Promise.all([assetsApi.asset(projectId,id,{signal}),assetsApi.revisions(projectId,id,null,{signal}),frozenRevision?assetsApi.revision(projectId,id,frozenRevision,{signal}):Promise.resolve(null)]),([a,rs,frozen])=>{setAsset(a);setRevisions(rs);setSelected(frozen??a.currentRevision)}),[readRun,projectId,id,frozenRevision])
 useEffect(()=>{void load()},[load,refresh,refreshTrigger])
 const attach=async()=>{const op=intent??(selected?{key:newIdempotencyKey(),revision:selected}:null);if(!op)return;setIntent(op);const result=await write.run(signal=>assetsApi.attach(projectId,target,op.revision,{signal,idempotencyKey:op.key}),value=>{onAttach(value);setConfirmed(true)});if(result==='ok'||result==='rejected')setIntent(null)}
 return <section aria-label="资产详情"><h5>资产详情</h5><button type="button" disabled={locked} onClick={()=>void load()}>刷新资产详情</button>
  {asset&&<><p>当前资产修订 {asset.currentRevision.revisionNumber}</p><p>{asset.currentRevision.filename} · {asset.currentRevision.size} bytes · {asset.currentRevision.mime}</p>
   <ul>{revisions.items.map(r=><li key={r.id}><button type="button" disabled={locked} onClick={()=>void read.run(signal=>assetsApi.revision(projectId,id,r.id,{signal}),setSelected)}>选择资产修订 {r.revisionNumber}</button></li>)}</ul>
   {revisions.nextCursor&&<button type="button" disabled={locked} onClick={()=>void read.run(signal=>assetsApi.revisions(projectId,id,revisions.nextCursor,{signal}),page=>setRevisions(old=>({...page,items:appendAssets(old.items,page.items)})))}>更多资产修订</button>}
   {selected&&<article aria-label="所选资产修订"><p>修订 {selected.revisionNumber} · {selected.filename} · {selected.size} bytes · {selected.mime} · SHA {selected.sha256.slice(0,12)} · {selected.scanStatus} · {selected.createdAt}</p>
    {access.permissions.includes('asset:download')&&<button type="button" disabled={locked} onClick={()=>onDownload(selected)}>下载所选资产修订</button>}
    {access.permissions.includes('attachment:create')&&<button type="button" disabled={locked} onClick={()=>void attach()}>关联所选冻结版本</button>}
   </article>}
   {access.permissions.includes('asset:version:create')&&<AssetUploader projectId={projectId} assetId={id} disabled={disabled||read.busy||write.busy||intent!==null} onLock={setUploadLocked} onDenied={onDenied} onPublished={(_asset,revisionId)=>{setVersionRevision(revisionId);setRefresh(n=>n+1)}}/>}
  </>}
  {intent&&!write.busy&&<button type="button" disabled={disabled||read.busy||uploadLocked} onClick={()=>void attach()}>重试原关联意图</button>}
  {confirmed&&<p role="status">关联已确认</p>}
  <p role="status">{read.busy?'资产详情读取中…':read.message}</p><p role="status">{write.busy?'附件关联中…':write.message}</p>
 </section>
}
