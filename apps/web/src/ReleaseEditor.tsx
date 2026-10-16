import {useEffect,useState} from 'react'
import {emptyPlanning,planningContent,releasesApi} from './releasesApi'
import type {Cycle,PlanningContent,Release,ReleaseStatus} from './releasesApi'
import {newIdempotencyKey} from './requirements'
import {useCoreRequest} from './useCoreRequest'

type Entity=Release|Cycle
type Intent={key:string;content:PlanningContent;target:Entity|null}
export default function ReleaseEditor({projectId,releaseId,initial=null,writable,disabled=false,onSaved,onLockChange,onDenied}:{projectId:string;releaseId?:string;initial?:Entity|null;writable:boolean;disabled?:boolean;onSaved:(v:Entity)=>void;onLockChange:(v:boolean)=>void;onDenied:()=>void}) {
 const cycle=!!releaseId,label=cycle?'周期':'发布'
 const {run,busy,message,setMessage,denied}=useCoreRequest()
 const [base,setBase]=useState(initial),[latest,setLatest]=useState<Entity|null>(null),[draft,setDraft]=useState<PlanningContent>(()=>initial?planningContent(initial,cycle):emptyPlanning()),[dirty,setDirty]=useState(false),[intent,setIntent]=useState<Intent|null>(null)
 const [confirmed,setConfirmed]=useState(false)
 useEffect(()=>{onLockChange(busy||intent!==null);return()=>onLockChange(false)},[busy,intent,onLockChange])
 useEffect(()=>{if(denied)onDenied()},[denied,onDenied])
 const refresh=()=>{if(!base)return;void run<Entity>(signal=>cycle?releasesApi.cycle(projectId,releaseId!,base.id,{signal}):releasesApi.get(projectId,base.id,{signal}),v=>{if(dirty)setLatest(v);else{setBase(v);setDraft(planningContent(v,cycle));onSaved(v)}setMessage('详情已从服务器重新读取；未重发写入')})}
 const save=async(status?:ReleaseStatus)=>{
  const command=intent??{key:newIdempotencyKey(),target:base,content:status?{...planningContent(base!,cycle),status}:{...draft}};setIntent(command)
  const result=await run<Entity>(signal=>{
   const o={signal,idempotencyKey:command.key}
   if(cycle)return command.target?releasesApi.updateCycle(projectId,releaseId!,command.target as Cycle,command.content,o):releasesApi.createCycle(projectId,releaseId!,command.content,o)
   return command.target?releasesApi.update(projectId,command.target as Release,command.content,o):releasesApi.create(projectId,command.content,o)
  },v=>{if(initial){setBase(v);setDraft(planningContent(v,cycle))}else setDraft(emptyPlanning());setDirty(false);setLatest(null);setConfirmed(true);onSaved(v);setMessage('后续刷新仅执行 GET')})
  if(result==='ok'||result==='rejected')setIntent(null)
 }
 const locked=disabled||busy||intent!==null,readonly=base?.status==='CLOSED'||base?.status==='ARCHIVED'||latest?.status==='CLOSED'||latest?.status==='ARCHIVED',conflict=latest!==null&&latest.rowVersion!==base?.rowVersion
 const edges:ReleaseStatus[]=base?.status==='PLANNED'?['ACTIVE','CLOSED']:base?.status==='ACTIVE'?['CLOSED']:[]
 return <section aria-label={`${initial?'编辑':'新建'}${label}`}><h4>{initial?`编辑${label} · v${base?.rowVersion}`:`新建${label}`}</h4>
  {base&&<button disabled={locked} onClick={refresh}>读取最新{label}</button>}
  {latest&&<div role="alert">最新服务器 v{latest.rowVersion}：{latest.name} · {latest.status}。草稿基础仍为 v{base?.rowVersion}。
   <button disabled={locked||readonly} onClick={()=>{setBase(latest);setLatest(null);setMessage('已明确采用最新基础版本，请确认草稿')}}>采用最新基础版本继续编辑</button>
   <button disabled={locked} onClick={()=>{setBase(latest);setDraft(planningContent(latest,cycle));setLatest(null);setDirty(false);onSaved(latest)}}>放弃草稿采用最新结果</button>
  </div>}
  <fieldset disabled={locked||readonly||!writable}><legend>{label}内容</legend>
   <label>{label}名称<input value={draft.name} maxLength={200} onChange={e=>{setDraft({...draft,name:e.target.value});setDirty(true)}}/></label>
   {!cycle&&<label>发布描述<textarea value={draft.description??''} maxLength={10000} onChange={e=>{setDraft({...draft,description:e.target.value});setDirty(true)}}/></label>}
   <label>开始日期<input type="date" value={draft.startDate} onChange={e=>{setDraft({...draft,startDate:e.target.value});setDirty(true)}}/></label>
   <label>结束日期<input type="date" value={draft.endDate} onChange={e=>{setDraft({...draft,endDate:e.target.value});setDirty(true)}}/></label>
  </fieldset>
  {writable&&!readonly&&<button disabled={locked||conflict} onClick={()=>void save()}>{initial?'保存':'创建'}{label}</button>}
  {base&&writable&&<fieldset disabled={locked||conflict||dirty}><legend>{label}状态 {base.status}</legend>{!readonly&&edges.map(s=><button key={s} onClick={()=>void save(s)}>{label}转为 {s}</button>)}{!cycle&&base.status!=='ARCHIVED'&&<button onClick={()=>void save('ARCHIVED')}>归档发布</button>}</fieldset>}
  {intent&&!busy&&<button disabled={disabled} onClick={()=>void save()}>重试原{label}意图</button>}
  <p>{intent?(busy?'写入确认中…':'结果未知：保留原内容、版本和幂等键重试'):`${dirty?'存在未保存草稿。':''}${confirmed?`${label}写入已确认。`:''}`}</p>
  <p role="status">{busy?`${label}请求处理中…`:message}</p>
 </section>
}
