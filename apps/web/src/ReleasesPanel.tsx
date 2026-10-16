import {useCallback,useEffect,useState} from 'react'
import type {ProjectAccess} from './projectAccess'
import {appendReleaseItems,releasesApi} from './releasesApi'
import type {Release} from './releasesApi'
import {useCoreRequest} from './useCoreRequest'
import ReleaseEditor from './ReleaseEditor'
import ReleaseDetail from './ReleaseDetail'

export default function ReleasesPanel({projectId,access,resetSignal=0}:{projectId:string;access:ProjectAccess|null;resetSignal?:number}) {
 if(access?.projectId!==projectId||!access.permissions.includes('release:read'))return null
 return <ReleaseScope key={`${projectId}:${access.principalId}:${access.permissions.join(',')}:${resetSignal}`} projectId={projectId} access={access}/>
}
function ReleaseScope({projectId,access}:{projectId:string;access:ProjectAccess}) {
 const {run,busy,message,denied,cancel}=useCoreRequest(),[items,setItems]=useState<Release[]>([]),[cursor,setCursor]=useState<string|null>(null),[selected,setSelected]=useState<string|null>(null)
 const [createLocked,setCreateLocked]=useState(false),[detailLocked,setDetailLocked]=useState(false),[scopeDenied,setScopeDenied]=useState(false)
 const onDenied=useCallback(()=>{cancel();setItems([]);setCursor(null);setSelected(null);setScopeDenied(true)},[cancel])
 const load=useCallback((after:string|null)=>run(signal=>releasesApi.list(projectId,after,{signal}),p=>{setItems(old=>after?appendReleaseItems(old,p.items):p.items);setCursor(p.nextCursor)}),[projectId,run])
 useEffect(()=>{void load(null)},[load])
 const saved=useCallback((v:Release)=>setItems(old=>[v,...old.filter(x=>x.id!==v.id)]),[])
 if(denied||scopeDenied)return <section aria-label="发布与周期工作台"><p role="alert">发布范围不可访问，旧内容已清理；请重新加载项目权限。</p></section>
 const locked=busy||createLocked||detailLocked
 return <section className="version-panel releases-panel" aria-label="发布与周期工作台"><h2>发布与周期工作台</h2>
  <button disabled={locked} onClick={()=>void load(null)}>刷新发布</button>
  {!busy&&!items.length&&<p>当前项目暂无发布。</p>}
  <ul>{items.map(r=><li key={r.id}><button disabled={locked} onClick={()=>setSelected(r.id)}>REL-{r.displayNumber} · {r.name}</button> · {r.status}</li>)}</ul>
  {cursor&&<button disabled={locked} onClick={()=>void load(cursor)}>更多发布</button>}
  <p role="status">{busy?'正在读取发布列表…':message}</p>
  {access.permissions.includes('release:create')&&<ReleaseEditor projectId={projectId} writable disabled={busy||detailLocked} onSaved={v=>saved(v as Release)} onLockChange={setCreateLocked} onDenied={onDenied}/>}
  {selected&&<ReleaseDetail key={selected} projectId={projectId} id={selected} access={access} onLockChange={setDetailLocked} onDenied={onDenied} onChanged={saved}/>}
 </section>
}
