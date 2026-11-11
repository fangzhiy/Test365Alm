import { useCallback,useEffect,useRef,useState } from 'react'
import { signatureApi,signatureMeanings } from './signaturesApi'
import type { SignatureChallenge,SignatureKind,SignatureView } from './signaturesApi'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'
type Props={projectId:string;kind:SignatureKind;objectId:string;snapshotId:string;rowVersion:number;contextKey?:string;readOnly?:boolean;disabled?:boolean;onLock?:(v:boolean)=>void;onDenied?:()=>void;onChanged?:()=>void}
type Intent={phase:'challenge';etag:string;key:string}|{phase:'sign';etag:string;key:string;body:{challengeId:string}}
export default function SignaturePanel(props:Props){return <SignatureSession key={`${props.projectId}:${props.kind}:${props.objectId}:${props.snapshotId}:${props.contextKey??''}:${!!props.readOnly}`} {...props}/>}
function SignatureSession({projectId,kind,objectId,snapshotId,rowVersion,readOnly=false,disabled=false,onLock,onDenied,onChanged}:Props){
 const read=useCoreRequest(),write=useCoreRequest(),readRun=read.run
 const [view,setView]=useState<SignatureView|null>(null),[expanded,setExpanded]=useState(false),[challenge,setChallenge]=useState<SignatureChallenge|null>(null),[ready,setReady]=useState(false),[intent,setIntent]=useState<Intent|null>(null),[notice,setNotice]=useState('')
 const popup=useRef<Window|null>(null),callbacks=useRef({onLock,onDenied,onChanged}),pending=useRef<Intent|null>(null),locked=useRef(false)
 useEffect(()=>{callbacks.current={onLock,onDenied,onChanged}},[onLock,onDenied,onChanged])
 const lock=(value:boolean)=>{locked.current=value;callbacks.current.onLock?.(value)}
 useEffect(()=>()=>{popup.current?.close();callbacks.current.onLock?.(false)},[])
 useEffect(()=>{if(read.denied||write.denied){pending.current=null;lock(false);popup.current?.close();callbacks.current.onDenied?.()}},[read.denied,write.denied])
 const load=useCallback(()=>readRun(signal=>signatureApi.get(projectId,kind,objectId,snapshotId,{signal}),v=>{setView(v);setExpanded(true)}),[projectId,kind,objectId,snapshotId,readRun])
 useEffect(()=>{if(expanded&&!locked.current)void load()},[rowVersion,load,expanded])
 useEffect(()=>{
  const receive=(event:MessageEvent)=>{
   if(event.origin!==window.location.origin||event.source!==popup.current||!popup.current||!challenge||event.data?.type!=='test365alm-signature')return
   if(event.data.status==='reauthenticated'){setReady(true);setNotice('身份提供方已返回；服务端将在确认签署时再次检查挑战、主体和权限。')}
   else if(event.data.status==='failed'){setReady(false);setNotice('再认证失败，请重新登录后重试。');setChallenge(null);setView(null);lock(false);callbacks.current.onDenied?.()}
  }
  window.addEventListener('message',receive);return()=>window.removeEventListener('message',receive)
 },[challenge])
 const execute=async(original:Intent)=>{
  pending.current=original;setIntent(original);lock(true);setNotice('')
  const result=await write.run(async signal=>original.phase==='challenge'
   ?{challenge:await signatureApi.prepare(projectId,kind,objectId,snapshotId,original.etag,original.key,{signal})}
   :{view:await signatureApi.sign(projectId,kind,objectId,snapshotId,original.body,original.etag,original.key,{signal})},value=>{
    pending.current=null;setIntent(null)
    if(value.challenge){setChallenge(value.challenge);setReady(false)}
    if(value.view){setView(value.view);setChallenge(null);setReady(false);popup.current?.close();lock(false);callbacks.current.onChanged?.();setNotice('模拟签署写入已确认；此签署及其旧快照保持只读。')}
   })
  if(result==='rejected'){pending.current=null;setIntent(null);setChallenge(null);setReady(false);lock(false);setView(null);setExpanded(false)}
 }
 const begin=()=>{if(!view||!view.canSign||pending.current||disabled||readOnly)return;void execute({phase:'challenge',etag:`"${view.rowVersion}"`,key:newIdempotencyKey()})}
 const authenticate=()=>{if(!challenge||pending.current)return;popup.current?.close();popup.current=window.open(challenge.authenticationUrl,'_blank','popup,width=520,height=680');if(!popup.current)setNotice('浏览器阻止了登录窗口，请允许弹窗后再次点击重新登录。')}
 const confirm=()=>{if(!challenge||!ready||pending.current||disabled||readOnly||!view)return;void execute({phase:'sign',etag:`"${view.rowVersion}"`,key:newIdempotencyKey(),body:{challengeId:challenge.id}})}
 const cancel=()=>{if(pending.current)return;popup.current?.close();popup.current=null;setChallenge(null);setReady(false);lock(false);setNotice('本次挑战已放弃，未提交签署。')}
 const busy=read.busy||write.busy
 if(read.denied||write.denied)return <section aria-label={`模拟电子签署：${signatureMeanings[kind]}`}><p role="alert">签署范围已失效，请重新加载项目权限。</p></section>
 return <section aria-label={`模拟电子签署：${signatureMeanings[kind]}`} aria-busy={busy}>
  <h4>模拟电子签署 · {signatureMeanings[kind]}</h4><p>本功能生成模拟证据；真实厂商接入与合规认证尚未实施。</p>
  {!expanded&&<button type="button" disabled={busy||disabled} onClick={()=>setExpanded(true)}>查看模拟签署</button>}
  {view&&<><p>确切快照：<code>{snapshotId}</code> · 状态 {view.state}</p><details><summary>签署内容摘要</summary><code style={{overflowWrap:'anywhere'}}>{view.contentDigest}</code></details>
   {view.signature?<div role="status"><p>已模拟签署：{view.signature.meaning} · {view.signature.displayName} · <time dateTime={view.signature.signedAt}>{view.signature.signedAt}</time></p><p>主体 {view.signature.actorId} · 提供方 {view.signature.evidence.provider}</p><details><summary>不可变签署材料</summary><pre style={{overflowX:'auto'}}>{JSON.stringify(view.signature,null,2)}</pre></details></div>
    :!readOnly&&!challenge&&!intent&&view.canSign&&<button type="button" disabled={busy||disabled} onClick={begin}>开始模拟签署</button>}
   {!view.signature&&!view.canSign&&<p>当前身份或快照尚不满足签署前置，请完成相应复核、运行或关闭动作。</p>}
  </>}
  {challenge&&<div><p>正在确认{signatureMeanings[kind]}；请在身份提供方重新登录当前主体。挑战到期：<time dateTime={challenge.expiresAt}>{challenge.expiresAt}</time></p>
   {!intent&&<button type="button" disabled={busy||disabled} onClick={authenticate}>重新登录以确认身份</button>}
   {ready&&!intent&&<button type="button" disabled={busy||disabled} onClick={confirm}>确认模拟签署：{signatureMeanings[kind]}</button>}
   {!intent&&<button type="button" disabled={busy} onClick={cancel}>放弃本次签署挑战</button>}
  </div>}
  {intent&&<p role="status">签署结果待确认，相关内容和导航已锁定；请保留原意图重试。</p>}
  {intent&&!write.busy&&<button type="button" onClick={()=>void execute(intent)}>重试原签署意图</button>}
  {expanded&&!challenge&&!intent&&<button type="button" disabled={busy||disabled} onClick={()=>void load()}>刷新模拟签署</button>}
  {(notice||read.message||write.message)&&<p role="status">{notice||write.message||read.message}</p>}
 </section>
}
