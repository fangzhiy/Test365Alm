import {useCallback,useEffect,useState} from 'react'
import {appendReleaseItems,releasesApi} from './releasesApi'
import type {Cycle,Metric,QualitySnapshot,Release,ReleasePage,ScopeChoice,ScopeEntry,ScopeInput} from './releasesApi'
import type {ProjectAccess} from './projectAccess'
import {useCoreRequest} from './useCoreRequest'
import {newIdempotencyKey} from './requirements'
import ReleaseEditor from './ReleaseEditor'

type Intent={key:string;kind:'evaluate'}|{key:string;kind:'add';input:ScopeInput}|{key:string;kind:'remove';id:string}
const empty=<T,>():ReleasePage<T>=>({items:[],nextCursor:null})
export default function ReleaseDetail({projectId,id,access,onLockChange,onDenied,onChanged}:{projectId:string;id:string;access:ProjectAccess;onLockChange:(v:boolean)=>void;onDenied:()=>void;onChanged:(v:Release)=>void}) {
 const read=useCoreRequest(),write=useCoreRequest()
 const readRun=read.run
 const [release,setRelease]=useState<Release|null>(null),[cycles,setCycles]=useState(empty<Cycle>),[scope,setScope]=useState(empty<ScopeEntry>),[snapshots,setSnapshots]=useState(empty<QualitySnapshot>),[snapshot,setSnapshot]=useState<QualitySnapshot|null>(null)
 const [cycle,setCycle]=useState<Cycle|null>(null),[intent,setIntent]=useState<Intent|null>(null),[editorLocks,setEditorLocks]=useState<Set<string>>(()=>new Set())
 const [kind,setKind]=useState<'REQUIREMENT'|'TEST_CASE'>('REQUIREMENT'),[choices,setChoices]=useState(empty<ScopeChoice>),[target,setTarget]=useState(''),[cycleId,setCycleId]=useState('')
 const lock=useCallback((key:string,v:boolean)=>setEditorLocks(old=>{const next=new Set(old);if(v)next.add(key);else next.delete(key);return next}),[])
 const releaseLock=useCallback((v:boolean)=>lock('release',v),[lock]),createCycleLock=useCallback((v:boolean)=>lock('createCycle',v),[lock]),cycleLock=useCallback((v:boolean)=>lock('cycle',v),[lock])
 const writeLocked=write.busy||intent!==null||editorLocks.size>0,locked=read.busy||writeLocked
 useEffect(()=>{onLockChange(writeLocked);return()=>onLockChange(false)},[writeLocked,onLockChange])
 useEffect(()=>{if(read.denied||write.denied)onDenied()},[read.denied,write.denied,onDenied])
 const load=useCallback(()=>readRun(signal=>Promise.all([releasesApi.get(projectId,id,{signal}),releasesApi.cycles(projectId,id,null,{signal}),releasesApi.scope(projectId,id,null,{signal}),releasesApi.snapshots(projectId,id,null,{signal})]),([r,c,s,q])=>{setRelease(r);setCycles(c);setScope(s);setSnapshots(q)}),[projectId,id,readRun])
 useEffect(()=>{void load()},[load])
 const save=async(command?:Intent)=>{
  const op=intent??command;if(!op)return;setIntent(op)
  const result=await write.run(async signal=>{const o={signal,idempotencyKey:op.key};if(op.kind==='evaluate')return {snapshot:await releasesApi.evaluate(projectId,id,o)};if(op.kind==='add')return {scope:await releasesApi.addScope(projectId,id,op.input,o)};await releasesApi.removeScope(projectId,id,op.id,o);return {removed:op.id}},v=>{
   if(v.snapshot){setSnapshot(v.snapshot);setSnapshots(old=>({...old,items:[v.snapshot!,...old.items.filter(x=>x.id!==v.snapshot!.id)]}));write.setMessage('快照写入已确认；历史刷新仅执行 GET')}
   if(v.scope){setScope(old=>({...old,items:[v.scope!,...old.items.filter(x=>x.id!==v.scope!.id)]}));write.setMessage('范围写入已确认；冻结修订不会自动变化')}
   if(v.removed){setScope(old=>({...old,items:old.items.filter(x=>x.id!==v.removed)}));write.setMessage('范围移除已确认；旧快照保持不变')}
  });if(result==='ok'||result==='rejected')setIntent(null)
 }
 const loadChoices=(after:string|null)=>read.run(signal=>releasesApi.choices(projectId,kind,after,{signal}),p=>{setChoices(old=>({...p,items:after?appendReleaseItems(old.items,p.items):p.items}));if(!after)setTarget('')})
 if(!release)return <section aria-label="发布详情"><button disabled={locked} onClick={()=>void load()}>重新读取发布详情</button><p role="status">{read.busy?'发布详情读取中…':read.message}</p></section>
 const mutable=release.status==='PLANNED'||release.status==='ACTIVE',admin=access.permissions.includes('release:update')
 const savedCycle=(v:Cycle)=>{setCycles(old=>({...old,items:[v,...old.items.filter(x=>x.id!==v.id)]}));if(cycle?.id===v.id)setCycle(v)}
 return <section aria-label="发布详情"><h3>发布详情 · {release.name} · {release.status}</h3>
  <ReleaseEditor key={release.id} projectId={projectId} initial={release} writable={admin} disabled={read.busy||write.busy||intent!==null||[...editorLocks].some(k=>k!=='release')} onSaved={v=>{setRelease(v as Release);onChanged(v as Release)}} onLockChange={releaseLock} onDenied={onDenied}/>
  <h4>发布周期</h4><ul>{cycles.items.map(c=><li key={c.id}><button disabled={locked} onClick={()=>void read.run(signal=>releasesApi.cycle(projectId,id,c.id,{signal}),setCycle)}>{c.name} · {c.startDate} — {c.endDate}</button> · {c.status}</li>)}</ul>
  {cycles.nextCursor&&<button disabled={locked} onClick={()=>void read.run(signal=>releasesApi.cycles(projectId,id,cycles.nextCursor,{signal}),p=>setCycles(old=>({...p,items:appendReleaseItems(old.items,p.items)})))}>更多周期</button>}
  {mutable&&access.permissions.includes('cycle:create')&&<ReleaseEditor projectId={projectId} releaseId={id} writable disabled={read.busy||write.busy||intent!==null||[...editorLocks].some(k=>k!=='createCycle')} onSaved={v=>savedCycle(v as Cycle)} onLockChange={createCycleLock} onDenied={onDenied}/>}
  {cycle&&<ReleaseEditor key={cycle.id} projectId={projectId} releaseId={id} initial={cycle} writable={mutable&&access.permissions.includes('cycle:update')} disabled={read.busy||write.busy||intent!==null||[...editorLocks].some(k=>k!=='cycle')} onSaved={v=>savedCycle(v as Cycle)} onLockChange={cycleLock} onDenied={onDenied}/>}
  <h4>发布范围（需求与测试均冻结修订）</h4><ul>{scope.items.map(s=><li key={s.id}>{s.kind} · {s.requirementId??s.testCaseId} · 冻结修订 {s.requirementRevisionId??s.testRevisionId} · {s.cycleId?`周期 ${s.cycleId}`:'发布直属'}{mutable&&access.permissions.includes('scope:update')&&<button disabled={locked} onClick={()=>void save({kind:'remove',id:s.id,key:newIdempotencyKey()})}>移除范围 {s.id}</button>}</li>)}</ul>
  {scope.nextCursor&&<button disabled={locked} onClick={()=>void read.run(signal=>releasesApi.scope(projectId,id,scope.nextCursor,{signal}),p=>setScope(old=>({...p,items:appendReleaseItems(old.items,p.items)})))}>更多范围</button>}
  {mutable&&access.permissions.includes('scope:update')&&<fieldset disabled={locked}><legend>添加发布范围</legend>
   <label>范围类型<select value={kind} onChange={e=>{setKind(e.target.value as typeof kind);setChoices(empty());setTarget('')}}><option value="REQUIREMENT">需求</option><option value="TEST_CASE">测试用例</option></select></label>
   <button onClick={()=>void loadChoices(null)}>加载范围候选</button><label>范围对象<select value={target} onChange={e=>setTarget(e.target.value)}><option value="">选择已有对象</option>{choices.items.map(c=><option key={c.id} value={c.id}>{c.title}</option>)}</select></label>
   {choices.nextCursor&&<button onClick={()=>void loadChoices(choices.nextCursor)}>更多范围候选</button>}
   <label>所属周期<select value={cycleId} onChange={e=>setCycleId(e.target.value)}><option value="">发布直属</option>{cycles.items.filter(c=>c.status!=='CLOSED').map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
   <button disabled={!target} onClick={()=>void save({kind:'add',key:newIdempotencyKey(),input:{kind,...(kind==='REQUIREMENT'?{requirementId:target}:{testCaseId:target}),...(cycleId?{cycleId}:{})} as ScopeInput})}>加入发布范围</button>
  </fieldset>}
  <h4>质量里程碑（当前发布含全部周期范围）</h4>
  {release.status!=='ARCHIVED'&&access.permissions.includes('milestone:evaluate')&&<button disabled={locked} onClick={()=>void save({kind:'evaluate',key:newIdempotencyKey()})}>生成质量快照</button>}
  <button disabled={locked} onClick={()=>void read.run(signal=>releasesApi.snapshots(projectId,id,null,{signal}),setSnapshots)}>刷新快照历史</button>
  {snapshots.items.map(s=><button key={s.id} disabled={locked} onClick={()=>{setSnapshot(null);void read.run(signal=>releasesApi.snapshot(projectId,id,s.id,{signal}),setSnapshot)}}>查看快照 {s.id} · {s.evaluatedAt}</button>)}
  {snapshots.nextCursor&&<button disabled={locked} onClick={()=>void read.run(signal=>releasesApi.snapshots(projectId,id,snapshots.nextCursor,{signal}),p=>setSnapshots(old=>({...p,items:appendReleaseItems(old.items,p.items)})))}>更多快照</button>}
  {snapshot&&<Snapshot value={snapshot}/>}
  {intent&&!write.busy&&<button onClick={()=>void save()}>重试原范围或快照意图</button>}
  <p role="status">{write.busy?'写入处理中…':write.message}</p><p role="status">{read.busy?'正在读取发布数据…':read.message}</p>
 </section>
}
function Snapshot({value}:{value:QualitySnapshot}) {
 const metrics:[string,string,Metric][]=[['coverage','需求覆盖',value.coverage],['plannedTests','计划测试',value.plannedTests],['completed','执行完成',value.completed],['effectivePass','有效 PASS',value.effectivePass],['openDefects','未关闭缺陷',value.openDefects]]
 return <article aria-label="质量快照详情"><h4>这是历史冻结结果</h4><p>快照 {value.id} · {value.evaluatedAt} · 范围版本 {value.scopeVersion}</p><p>规则 v{value.metricVersion}：{value.rule}</p><p>需求范围：{value.requirementCount}</p>
  <dl>{metrics.map(([key,label,m])=><div key={key} data-testid={`metric-${key}`}><dt>{label}</dt><dd>{m.numerator} / {m.denominator} · {m.denominator?`${(m.numerator/m.denominator*100).toFixed(1)}%`:'不适用'}</dd></div>)}</dl>
  <p>范围指纹 {value.scopeFingerprint}。新执行、范围或缺陷变化不会修改此快照；再次评估生成新快照。</p>
 </article>
}
