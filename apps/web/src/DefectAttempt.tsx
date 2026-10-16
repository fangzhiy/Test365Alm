import { useCallback,useEffect,useState } from 'react'
import { executionApi } from './testExecution'
import type { RunAttempt,StepOutcome } from './testExecution'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'
type Draft={actual:string;outcome:StepOutcome;version:number}
type Intent={key:string;kind:'step'|'finish';step?:string;draft?:Draft;version:number}
export default function DefectAttempt({projectId,target,writable,onDenied,onLockChange}:{projectId:string;target:{runId:string;attemptId:string};writable:boolean;onDenied:()=>void;onLockChange?:(v:boolean)=>void}) {
 const {run,busy,message,setMessage,denied}=useCoreRequest(),[attempt,setAttempt]=useState<RunAttempt|null>(null),[latest,setLatest]=useState<RunAttempt|null>(null),[drafts,setDrafts]=useState<Record<string,Draft>>({}),[intent,setIntent]=useState<Intent|null>(null)
 const load=useCallback(()=>run(signal=>executionApi.getAttempt(projectId,target.runId,target.attemptId,{signal}),setAttempt),[projectId,target.runId,target.attemptId,run])
 useEffect(()=>{void load()},[load]);useEffect(()=>{if(denied)onDenied()},[denied,onDenied])
 useEffect(()=>{onLockChange?.(busy||intent!==null);return()=>onLockChange?.(false)},[busy,intent,onLockChange])
 const refresh=()=>run(signal=>executionApi.getAttempt(projectId,target.runId,target.attemptId,{signal}),a=>{if(Object.keys(drafts).length)setLatest(a);else setAttempt(a);setMessage('尝试已从服务器读取')})
 const persist=async(kind:Intent['kind'],step?:string)=>{
  if(!attempt||!attempt.rowVersion)return
  const s=attempt.steps.find(s=>s.stepKey===step),d=step?drafts[step]??(s?.rowVersion?{actual:s.actual,outcome:s.outcome,version:s.rowVersion}:undefined):undefined
  if(kind==='step'&&!d)return
  const command=intent??{key:newIdempotencyKey(),kind,step,draft:d,version:attempt.rowVersion};setIntent(command)
  const result=await run(signal=>command.kind==='finish'?executionApi.finish(projectId,target.runId,target.attemptId,command.version,{signal,idempotencyKey:command.key}):executionApi.saveStep(projectId,target.runId,target.attemptId,command.step!,{actual:command.draft!.actual,outcome:command.draft!.outcome,rowVersion:command.draft!.version},{signal,idempotencyKey:command.key}),a=>{setAttempt(a);if(command.step)setDrafts(old=>{const copy={...old};delete copy[command.step!];return copy});setMessage(command.kind==='finish'?'复测完成已确认；请刷新缺陷验证':'复测步骤已保存')})
  if(result==='ok'||result==='rejected')setIntent(null)
 }
 const locked=busy||intent!==null,editable=writable&&attempt?.state==='RUNNING'&&latest===null
 return <section aria-label="缺陷关联尝试"><h4>关联尝试 {attempt?.attemptNo} · {attempt?.state} · {attempt?.outcome}</h4>
  <button disabled={locked} onClick={()=>void refresh()}>刷新关联尝试</button><p role="status">{busy?'读取或写入关联尝试…':message}</p>
  {latest&&<div role="alert">最新尝试 {latest.state} · {latest.outcome}，旧草稿基础版本未改。<button disabled={locked} onClick={()=>{setAttempt(latest);setLatest(null);setDrafts({})}}>放弃步骤草稿并采用最新结果</button></div>}
  {attempt?.steps.map(s=><article key={s.stepKey}><p>步骤 {s.ordinal} · {s.action}</p><p>预期：{s.expected}</p>
   <label>复测步骤 {s.ordinal} 实际结果<textarea value={drafts[s.stepKey]?.actual??s.actual} disabled={locked||!editable} onChange={e=>setDrafts(old=>({...old,[s.stepKey]:{actual:e.target.value,outcome:old[s.stepKey]?.outcome??s.outcome,version:old[s.stepKey]?.version??s.rowVersion!}}))}/></label>
   <label>复测步骤 {s.ordinal} 结论<select value={drafts[s.stepKey]?.outcome??s.outcome} disabled={locked||!editable} onChange={e=>setDrafts(old=>({...old,[s.stepKey]:{actual:old[s.stepKey]?.actual??s.actual,outcome:e.target.value as StepOutcome,version:old[s.stepKey]?.version??s.rowVersion!}}))}>{['NOT_RUN','PASS','FAIL','BLOCKED'].map(x=><option key={x}>{x}</option>)}</select></label>
   {writable&&attempt.state==='RUNNING'&&<button disabled={locked||!editable} onClick={()=>void persist('step',s.stepKey)}>保存复测步骤 {s.ordinal}</button>}
  </article>)}
  {writable&&attempt?.state==='RUNNING'&&<button disabled={locked||!editable||Object.keys(drafts).length>0} onClick={()=>void persist('finish')}>完成复测</button>}
  {intent&&!busy&&<button onClick={()=>void persist(intent.kind,intent.step)}>重试原复测意图</button>}
 </section>
}
