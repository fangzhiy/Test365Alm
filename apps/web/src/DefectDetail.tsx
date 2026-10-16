import { useCallback,useEffect,useState } from 'react'
import type { ProjectAccess } from './projectAccess'
import { defectsApi,defectContent,emptyDefect } from './defectsApi'
import type { Defect,DefectContent,DefectRevision,DefectStatus,Retest,Verification } from './defectsApi'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'
import DefectForm from './DefectForm'
import DefectAttempt from './DefectAttempt'
import AttachmentsPanel from './AttachmentsPanel'
type Intent={key:string;target:Defect;kind:'edit'|'transition'|'retest';content:DefectContent;status:DefectStatus;reason:string}
const edges:Record<DefectStatus,DefectStatus[]>={OPEN:['IN_PROGRESS','RESOLVED'],IN_PROGRESS:['RESOLVED'],RESOLVED:['CLOSED','REOPENED'],REOPENED:['IN_PROGRESS','RESOLVED'],CLOSED:[]}
export default function DefectDetail({projectId,id,access,onLockChange,onDenied}:{projectId:string;id:string;access:ProjectAccess;onLockChange:(v:boolean)=>void;onDenied:()=>void}) {
 const {run,busy,message,setMessage,denied}=useCoreRequest()
 const [current,setCurrent]=useState<Defect|null>(null),[latest,setLatest]=useState<Defect|null>(null),[draft,setDraft]=useState(emptyDefect),[dirty,setDirty]=useState(false)
 const [history,setHistory]=useState<DefectRevision[]>([]),[historyCursor,setHistoryCursor]=useState<string|null>(null),[retests,setRetests]=useState<Retest[]>([]),[retestCursor,setRetestCursor]=useState<string|null>(null),[verification,setVerification]=useState<Verification|null>(null)
 const [intent,setIntent]=useState<Intent|null>(null),[reason,setReason]=useState(''),[viewed,setViewed]=useState<DefectRevision|null>(null),[attempt,setAttempt]=useState<{runId:string;attemptId:string}|null>(null),[attemptLocked,setAttemptLocked]=useState(false)
 useEffect(()=>{onLockChange(busy||intent!==null||attemptLocked);return()=>onLockChange(false)},[busy,intent,attemptLocked,onLockChange])
 useEffect(()=>{if(denied)onDenied()},[denied,onDenied])
 const initialLoad=useCallback(()=>run(signal=>Promise.all([defectsApi.get(projectId,id,{signal}),defectsApi.history(projectId,id,null,{signal}),defectsApi.verification(projectId,id,{signal}),defectsApi.retests(projectId,id,null,{signal})]),([d,h,v,r])=>{setCurrent(d);setDraft(defectContent(d.currentRevision));setHistory(h.items);setHistoryCursor(h.nextCursor);setVerification(v);setRetests(r.items);setRetestCursor(r.nextCursor)}),[projectId,id,run])
 useEffect(()=>{void initialLoad()},[initialLoad])
 const refresh=()=>run(signal=>Promise.all([defectsApi.get(projectId,id,{signal}),defectsApi.history(projectId,id,null,{signal}),defectsApi.verification(projectId,id,{signal}),defectsApi.retests(projectId,id,null,{signal})]),([d,h,v,r])=>{if(dirty){setLatest(d)}else{setCurrent(d);setDraft(defectContent(d.currentRevision))}setHistory(h.items);setHistoryCursor(h.nextCursor);setVerification(v);setRetests(r.items);setRetestCursor(r.nextCursor);setMessage('详情/历史已从服务器重新读取')})
 const persist=async(kind:Intent['kind'],status:DefectStatus='OPEN')=>{
  if(!current)return
  const command=intent??{key:newIdempotencyKey(),target:current,kind,content:{...draft},status,reason};setIntent(command)
  const result=await run(async signal=>{
   const o={signal,idempotencyKey:command.key}
   if(command.kind==='edit')return {defect:await defectsApi.edit(projectId,command.target,command.content,o)}
   if(command.kind==='transition')return {defect:await defectsApi.transition(projectId,command.target,command.status,command.reason,o)}
   return {attempt:await defectsApi.retest(projectId,command.target,o)}
  },v=>{if(v.defect){setCurrent(v.defect);setDraft(defectContent(v.defect.currentRevision));setDirty(false);setLatest(null)}if(v.attempt)setAttempt(v.attempt);setVerification(null);setHistory([]);setRetests([]);setMessage(command.kind==='retest'?'复测已确认创建；下方使用原执行服务':'缺陷写入已确认；刷新详情/历史只执行 GET')})
  if(result==='ok'||result==='rejected')setIntent(null)
 }
 const locked=busy||intent!==null||attemptLocked,closed=(latest??current)?.currentRevision.status==='CLOSED',conflict=latest!==null&&latest.rowVersion!==current?.rowVersion
 if(!current)return <section aria-label="缺陷详情"><button disabled={locked} onClick={()=>void initialLoad()}>重新读取缺陷详情</button><p role="status">{busy?'读取详情中…':message}</p></section>
 const source=current.links.find(l=>l.kind==='RUN_STEP')
 return <section aria-label="缺陷详情"><h3>BUG-{current.displayNumber} · {current.currentRevision.status} · v{current.rowVersion}</h3>
  <button disabled={locked} onClick={()=>void refresh()}>刷新缺陷详情</button><p role="status">{busy?'读取或保存缺陷…':message}</p>
  {latest&&<div role="alert">最新服务器 v{latest.rowVersion}：{latest.currentRevision.title} · {latest.currentRevision.status}。草稿仍基于 v{current.rowVersion}。
   <button disabled={locked||closed} onClick={()=>{setCurrent(latest);setLatest(null);setMessage('已明确采用最新基础版本；请确认草稿后保存')}}>采用最新版本并继续编辑</button>
   <button disabled={locked} onClick={()=>{setCurrent(latest);setDraft(defectContent(latest.currentRevision));setDirty(false);setLatest(null)}}>放弃草稿并采用最新结果</button></div>}
  <DefectForm prefix="编辑" value={draft} onChange={d=>{setDraft(d);setDirty(true)}} disabled={locked||closed||!access.permissions.includes('defect:update')}/>
  {access.permissions.includes('defect:update')&&!closed&&<button disabled={locked||conflict} onClick={()=>void persist('edit')}>保存缺陷修订</button>}
  {access.permissions.includes('defect:transition')&&!closed&&<fieldset disabled={locked||conflict||dirty}><legend>状态流转（先保存或放弃内容草稿）</legend><label>修复/流转说明<textarea value={reason} onChange={e=>setReason(e.target.value)} maxLength={10000}/></label>{edges[current.currentRevision.status].map(s=><button key={s} onClick={()=>void persist('transition',s)}>转为 {s}</button>)}</fieldset>}
  {intent&&!busy&&<button onClick={()=>void persist(intent.kind,intent.status)}>重试原缺陷写意图</button>}
  {source&&<div><h4>原始失败来源（不可变）</h4><p>{source.sourceConclusion} · 测试 {source.testCaseId} · 修订 {source.testRevisionId} · 运行 {source.runId} · 尝试 {source.attemptId}</p><p>原实际：{source.actualResult}</p><p>原预期：{source.expectedResult}</p><button disabled={locked} onClick={()=>setAttempt({runId:source.runId!,attemptId:source.attemptId!})}>查看原失败尝试</button>
   {current.currentRevision.status==='RESOLVED'&&access.permissions.includes('run:write')&&access.permissions.includes('defect:update')&&<button disabled={locked||conflict||dirty} onClick={()=>void persist('retest')}>开始复测</button>}</div>}
  {current.links.filter(l=>l.kind==='REQUIREMENT').map(l=><p key={l.id}>关联需求 {l.requirementId} · 冻结修订 {l.requirementRevisionId}</p>)}
  <p>PASS verification：{verification?verification.eligible?'可用相关 PASS':'未满足关闭证据':'待刷新确认'}</p>
  <h4>不可变状态/内容历史</h4>{history.map(r=><button disabled={locked} key={r.id} onClick={()=>void run(signal=>defectsApi.revision(projectId,id,r.id,{signal}),setViewed)}>修订 {r.revisionNo} · {r.status}</button>)}
  {historyCursor&&<button disabled={locked} onClick={()=>void run(signal=>defectsApi.history(projectId,id,historyCursor,{signal}),p=>{setHistory(old=>[...old,...p.items]);setHistoryCursor(p.nextCursor)})}>更多缺陷历史</button>}
  {viewed&&<article aria-label="缺陷历史快照"><h4>历史 v{viewed.revisionNo} · {viewed.status}</h4><p>{viewed.title}</p><p>{viewed.description}</p><p>{viewed.reason}</p></article>}
  <h4>明确复测记录</h4>{retests.map(r=><button disabled={locked} key={r.id} onClick={()=>setAttempt({runId:r.runId,attemptId:r.attemptId})}>打开复测尝试 {r.attemptNo} · {r.conclusion??r.status}</button>)}
  {retestCursor&&<button disabled={locked} onClick={()=>void run(signal=>defectsApi.retests(projectId,id,retestCursor,{signal}),p=>{setRetests(old=>[...old,...p.items]);setRetestCursor(p.nextCursor)})}>更多复测记录</button>}
  <AttachmentsPanel projectId={projectId} target={{kind:'DEFECT',id}} access={access} onDenied={onDenied}/>
  {attempt&&<DefectAttempt key={attempt.attemptId} projectId={projectId} target={attempt} writable={!closed&&access.permissions.includes('run:write')} onDenied={onDenied} onLockChange={setAttemptLocked}/>}
 </section>
}
