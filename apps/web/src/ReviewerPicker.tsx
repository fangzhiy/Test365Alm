import { useEffect, useRef, useState } from 'react'
import { reviewerApi } from './reviewerApi'
import type { ReviewerCandidate } from './reviewerApi'
type Props={projectId?:string;value?:string|null;onChange?:(id:string)=>void;prefix?:string;required?:boolean}
export default function ReviewerPicker({projectId,value,onChange,prefix='',required}:Props){
 const [selected,setSelected]=useState<ReviewerCandidate|null>(null),[items,setItems]=useState<ReviewerCandidate[]>([]),[search,setSearch]=useState(''),[cursor,setCursor]=useState<string|null>(null),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false)
 const request=useRef<AbortController|null>(null)
 useEffect(()=>{const c=new AbortController();if(projectId&&value)void reviewerApi.list(projectId,'',null,value,{signal:c.signal}).then(page=>{if(!c.signal.aborted)setSelected(page.items.find(x=>x.principalId===value)??null)}).catch(()=>{if(!c.signal.aborted)setSelected(null)});return()=>c.abort()},[projectId,value])
 useEffect(()=>()=>request.current?.abort(),[])
 const load=async(next:string|null)=>{if(!projectId)return;request.current?.abort();const c=new AbortController();request.current=c;setBusy(true);setNotice('');try{const page=await reviewerApi.list(projectId,search,next,undefined,{signal:c.signal});if(!c.signal.aborted){setItems(old=>next?[...old,...page.items]:page.items);setCursor(page.nextCursor);if(!page.items.length)setNotice('没有符合条件的有效项目成员')}}catch{if(!c.signal.aborted)setNotice('成员读取失败，请重试')}finally{if(!c.signal.aborted)setBusy(false)}}
 const current=selected?.principalId===value?selected:null
 if(!onChange)return <span>{!value?'未指定':current?`${current.displayName}（当前成员姓名）`:'已指定的复核人不可用'}</span>
 return <div><label>{prefix}指定复核人<select value={value??''} required={required} onChange={e=>onChange(e.target.value)}><option value="">未指定</option>{value&&!items.some(x=>x.principalId===value)&&<option value={value}>{current?.displayName??'已指定的复核人不可用'}</option>}{items.map(x=><option key={x.principalId} value={x.principalId}>{x.displayName}</option>)}</select></label>{projectId&&<><label>{prefix}复核人姓名<input value={search} maxLength={200} onChange={e=>{request.current?.abort();setBusy(false);setSearch(e.target.value);setItems([]);setCursor(null)}}/></label><button type="button" disabled={busy} onClick={()=>void load(null)}>查找复核人</button>{cursor&&<button type="button" disabled={busy} onClick={()=>void load(cursor)}>更多成员</button>}</>}<span role="status">{notice}</span></div>
}
