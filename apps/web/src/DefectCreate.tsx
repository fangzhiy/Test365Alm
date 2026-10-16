import { useEffect,useState } from 'react'
import DefectForm,{emptyDefect} from './DefectForm'
import { defectsApi } from './defectsApi'
import type { Defect,DefectContent,DefectSource } from './defectsApi'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'

export default function DefectCreate({projectId,source=null,onCreated,onLockChange,onDenied}:{projectId:string;source?:DefectSource|null;onCreated?:(d:Defect)=>void;onLockChange?:(v:boolean)=>void;onDenied?:()=>void}) {
 const {run,busy,message,setMessage,denied}=useCoreRequest(),[draft,setDraft]=useState(emptyDefect)
 const [intent,setIntent]=useState<{key:string;content:DefectContent;source:DefectSource|null}|null>(null)
 useEffect(()=>{onLockChange?.(busy||intent!==null);return()=>onLockChange?.(false)},[busy,intent,onLockChange])
 useEffect(()=>{if(denied)onDenied?.()},[denied,onDenied])
 const persist=async()=>{
  const command=intent??{key:newIdempotencyKey(),content:{...draft},source};setIntent(command)
  const result=await run(signal=>defectsApi.create(projectId,command.content,command.source,{signal,idempotencyKey:command.key}),d=>{setDraft(emptyDefect());setMessage(`缺陷已创建 BUG-${d.displayNumber}；可在工作台刷新重读`);onCreated?.(d)})
  if(result==='ok'||result==='rejected')setIntent(null)
 }
 if(denied)return <p role="alert">缺陷范围失效，请重新加载项目权限。</p>
 return <section aria-label={source?'从失败步骤创建缺陷':'新建缺陷'}>
  {source&&<p>冻结来源：运行 {source.runId} · 尝试 {source.attemptId} · 步骤 {source.stepKey}</p>}
  <DefectForm value={draft} onChange={setDraft} disabled={busy||intent!==null}/>
  <button disabled={busy||intent!==null} onClick={()=>void persist()}>创建缺陷</button>
  {intent&&!busy&&<button onClick={()=>void persist()}>重试原创建意图</button>}
  <p role="status">{busy?'正在创建缺陷…':message}</p>
 </section>
}
