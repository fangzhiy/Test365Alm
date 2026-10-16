import { useCallback,useEffect,useState } from 'react'
import { defectsApi } from './defectsApi'
import type { Defect } from './defectsApi'
import type { ProjectAccess } from './projectAccess'
import { useCoreRequest } from './useCoreRequest'
import DefectCreate from './DefectCreate'
import DefectDetail from './DefectDetail'

export default function DefectsPanel({projectId,access,resetSignal=0}:{projectId:string;access:ProjectAccess|null;resetSignal?:number}) {
 if(access?.projectId!==projectId||!access.permissions.includes('defect:read'))return null
 return <Scope key={`${projectId}:${access.principalId}:${access.permissions.join(',')}:${resetSignal}`} projectId={projectId} access={access}/>
}
function Scope({projectId,access}:{projectId:string;access:ProjectAccess}) {
 const {run,busy,message,denied,cancel}=useCoreRequest(),[items,setItems]=useState<Defect[]>([]),[cursor,setCursor]=useState<string|null>(null),[q,setQ]=useState(''),[selected,setSelected]=useState<string|null>(null)
 const [detailLocked,setDetailLocked]=useState(false),[creationLocked,setCreationLocked]=useState(false),[scopeDenied,setScopeDenied]=useState(false)
 const onDenied=useCallback(()=>{cancel();setItems([]);setSelected(null);setCursor(null);setQ('');setScopeDenied(true)},[cancel])
 const load=useCallback((search:string,after:string|null)=>run(signal=>defectsApi.list(projectId,search,after,{signal}),page=>{setItems(old=>after?[...old,...page.items]:page.items);setCursor(page.nextCursor)}),[projectId,run])
 useEffect(()=>{void load('',null)},[load])
 if(denied||scopeDenied)return <section aria-label="缺陷工作台"><p role="alert">缺陷范围不可访问，旧内容已清理；请重新加载项目权限。</p></section>
 return <section aria-label="缺陷工作台" className="version-panel defects-panel"><h2>缺陷工作台</h2>
  <label>查询缺陷标题<input value={q} maxLength={200} onChange={e=>setQ(e.target.value)} disabled={busy||detailLocked||creationLocked}/></label>
  <button disabled={busy||detailLocked||creationLocked} onClick={()=>void load(q,null)}>查询缺陷</button><button disabled={busy||detailLocked||creationLocked} onClick={()=>void load(q,null)}>刷新缺陷</button>
  {!items.length&&!busy&&<p>当前范围暂无缺陷。</p>}<ul>{items.map(d=><li key={d.id}><button disabled={busy||detailLocked||creationLocked} onClick={()=>setSelected(d.id)}>BUG-{d.displayNumber} · {d.currentRevision.title} · {d.currentRevision.status}</button></li>)}</ul>
  {cursor&&<button disabled={busy||detailLocked||creationLocked} onClick={()=>void load(q,cursor)}>更多缺陷</button>}
  <p role="status">{busy?'正在读取缺陷列表…':message}</p>
  {access.permissions.includes('defect:create')&&<DefectCreate projectId={projectId} disabled={busy} onLockChange={setCreationLocked} onDenied={onDenied} onCreated={d=>{setItems(old=>[d,...old.filter(x=>x.id!==d.id)])}}/>}
  {selected&&<DefectDetail key={selected} projectId={projectId} id={selected} access={access} onLockChange={setDetailLocked} onDenied={onDenied}/>}
 </section>
}
