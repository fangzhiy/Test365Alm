import { useCallback,useEffect,useRef,useState } from 'react'
import ReviewerPicker from './ReviewerPicker'
import { reviewApi } from './reviewApi'
import type { ReviewAction,ReviewCommand,ReviewView } from './reviewApi'
import { newIdempotencyKey } from './requirements'
type Props={projectId:string;testCaseId:string;revisionId:string;rowVersion:number;contextKey:string;readOnly?:boolean;historical?:boolean;disabled?:boolean;onChanged?:(v:ReviewView)=>void;onLock?:(locked:boolean)=>void;onDenied?:()=>void}
type Intent={command:ReviewCommand;etag:string;key:string}
const states={DRAFT:'未提交',SUBMITTED:'待复核',APPROVED:'已批准',REJECTED:'已拒绝'}
const actions={SUBMIT:'提交复核',APPROVE:'批准',REJECT:'拒绝'}
const errorStatus=(error:unknown)=>(error as {status?:number}|null)?.status
export default function ReviewPanel(props:Props){
 return <ReviewSession key={`${props.projectId}:${props.testCaseId}:${props.revisionId}:${props.contextKey}:${props.readOnly===true}`} {...props}/>
}
function ReviewSession({projectId,testCaseId,revisionId,rowVersion,readOnly=false,historical=false,disabled=false,onChanged,onLock,onDenied}:Props){
 const [view,setView]=useState<ReviewView|null>(null),[reason,setReason]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false),[pending,setPending]=useState<Intent|null>(null),[blocked,setBlocked]=useState(false)
 const controller=useRef<AbortController|null>(null),mounted=useRef(false),callbacks=useRef({onChanged,onLock,onDenied}),intent=useRef<Intent|null>(null)
 useEffect(()=>{callbacks.current={onChanged,onLock,onDenied}},[onChanged,onLock,onDenied])
 const load=useCallback(async(cursor:string|null=null)=>{
  controller.current?.abort();const c=new AbortController();controller.current=c;setBusy(true);setError('')
  try{
   const result=await reviewApi.get(projectId,testCaseId,revisionId,cursor,{signal:c.signal})
   if(c.signal.aborted||!mounted.current)return
   setView(old=>cursor&&old?{...result,items:[...old.items,...result.items.filter(e=>!old.items.some(x=>x.id===e.id))]}:result);setBlocked(false)
  }catch(cause){
   if(c.signal.aborted||!mounted.current)return
   if([401,403,404].includes(errorStatus(cause)??0)){setView(null);setBlocked(true);callbacks.current.onDenied?.();setError('无权读取复核记录或对象不存在')}
   else setError('复核记录加载失败，请重试')
  }finally{if(!c.signal.aborted&&mounted.current)setBusy(false)}
 },[projectId,testCaseId,revisionId])
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;controller.current?.abort();callbacks.current.onLock?.(false)}},[])
 useEffect(()=>{if(!intent.current)void load();return()=>controller.current?.abort()},[rowVersion,load])
 const execute=async(original:Intent)=>{
  controller.current?.abort();const c=new AbortController();controller.current=c;setBusy(true);setError('');setPending(original);intent.current=original;callbacks.current.onLock?.(true)
  const timer=window.setTimeout(()=>c.abort(),10000)
  try{
   const result=await reviewApi.act(projectId,testCaseId,revisionId,original.command,original.etag,original.key,{signal:c.signal})
   if(!mounted.current)return
   intent.current=null;setPending(null);setView(result);setReason('');callbacks.current.onLock?.(false);callbacks.current.onChanged?.(result)
  }catch(cause){
   if(!mounted.current)return
   const status=errorStatus(cause)
   if(status&&status>=400&&status<500){
    intent.current=null;setPending(null);callbacks.current.onLock?.(false)
    setError(status===412?'版本已变化，请刷新复核状态后确认；说明已保留。':status===403?'当前身份不能进行此复核动作，请刷新权限。':status===401||status===404?'身份或范围已失效，请重新选择项目。':'复核动作被拒绝，请刷新状态后确认。')
    setBlocked(true)
    if(status===401||status===404){setView(null);callbacks.current.onDenied?.()}
   }else setError('复核结果未知，请重试原复核意图确认结果。')
  }finally{window.clearTimeout(timer);if(mounted.current)setBusy(false)}
 }
 const begin=(action:ReviewAction)=>{
  if(!view||pending||busy||disabled||blocked||readOnly)return
  if(action==='REJECT'&&!reason.trim()){setError('请填写拒绝理由');return}
  void execute({command:{action,reason:reason.trim()},etag:`"${view.rowVersion}"`,key:newIdempotencyKey()})
 }
 return <section aria-label={historical?'历史修订复核':'用例复核'} aria-busy={busy}>
  <h4>{historical?'历史修订复核':'用例复核'}</h4>
  {view&&<>
   <p>修订 {view.revisionNo}：<strong>{states[view.state]}</strong> · 指定复核人：<ReviewerPicker projectId={projectId} value={view.reviewerId??undefined}/></p>
   {view.state==='APPROVED'&&<p>本修订已批准；编辑保存会生成需要重新复核的新修订。</p>}
   {view.currentRevisionId!==view.revisionId&&<p>这是历史修订的复核结果，当前修订需单独复核。</p>}
   <details><summary>复核内容摘要</summary><code style={{overflowWrap:'anywhere'}}>{view.contentDigest}</code></details>
   {!readOnly&&(view.canSubmit||view.canDecide)&&<div>
    <label>复核说明或拒绝理由<textarea maxLength={2000} value={reason} onChange={e=>setReason(e.target.value)} disabled={busy||!!pending||disabled}/></label>
    {view.canSubmit&&<button type="button" disabled={busy||!!pending||disabled||blocked} onClick={()=>begin('SUBMIT')}>提交复核</button>}
    {view.canDecide&&<><button type="button" disabled={busy||!!pending||disabled||blocked} onClick={()=>begin('APPROVE')}>批准本修订</button><button type="button" disabled={busy||!!pending||disabled||blocked} onClick={()=>begin('REJECT')}>拒绝本修订</button></>}
   </div>}
   {view.items.length>0?<div role="region" aria-label="复核记录表格" tabIndex={0} style={{overflowX:'auto'}}><table><thead><tr>{['修订','时间','动作','执行人','理由'].map(t=><th key={t} scope="col">{t}</th>)}</tr></thead><tbody>{view.items.map(e=><tr key={e.id}><td>{e.revisionNo}</td><td><time dateTime={e.occurredAt}>{new Date(e.occurredAt).toLocaleString('zh-CN')}</time></td><td>{actions[e.action]}</td><td><ReviewerPicker projectId={projectId} value={e.actorId}/></td><td>{e.reason||'—'}</td></tr>)}</tbody></table></div>:<p>暂无复核记录</p>}
   {view.nextCursor&&<button type="button" disabled={busy||!!pending} onClick={()=>void load(view.nextCursor)}>更多复核记录</button>}
  </>}
  {error&&<p role="alert">{error}</p>}
  {pending?<button type="button" disabled={busy} onClick={()=>void execute(pending)}>重试原复核意图</button>:<button type="button" disabled={busy||disabled} onClick={()=>void load()}>刷新复核状态</button>}
 </section>
}
