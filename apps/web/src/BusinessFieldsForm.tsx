import { useEffect, useRef, useState } from 'react'
import { coreApi } from './coreApi'
import type { Field } from './coreApi'
import { businessKeys, businessLabels } from './businessFields'
import type { BusinessFieldValues, BusinessKind } from './businessFields'
type Props={kind:BusinessKind;projectId?:string;value?:BusinessFieldValues;onChange?:(v:BusinessFieldValues)=>void;disabled?:boolean;prefix?:string}
export default function BusinessFieldsForm(props:Props){
 return <BusinessFieldsSession key={`${props.projectId??'snapshot'}:${props.kind}`} {...props}/>
}
function BusinessFieldsSession({kind,projectId,value,onChange,disabled=false,prefix=''}:Props){
 const [definitions,setDefinitions]=useState<Field[]>([]),[cursor,setCursor]=useState<string|null>(null),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false)
 const request=useRef<AbortController|null>(null)
 useEffect(()=>()=>request.current?.abort(),[projectId])
 const load=async(next:string|null)=>{
  request.current?.abort();const c=new AbortController();request.current=c;setBusy(true);setNotice('')
  try{const page=await coreApi.fields(projectId!,next,{signal:c.signal});if(c.signal.aborted)return;setDefinitions(old=>next?[...old,...page.items]:page.items);setCursor(page.nextCursor)}catch{if(!c.signal.aborted)setNotice('字段配置读取失败，请重试')}finally{if(!c.signal.aborted)setBusy(false)}
 }
 if(!onChange)return <dl aria-label="业务字段快照">{businessKeys[kind].map(key=><div key={key}><dt>{businessLabels[key]}</dt><dd>{value?.[key]?.value||'未指定'}{value?.[key]?.revision&&<small> · 字段定义 v{value[key].revision}</small>}</dd></div>)}</dl>
 return <fieldset disabled={disabled||busy}><legend>{prefix}业务字段</legend>
  {businessKeys[kind].map(key=>{const entry=value?.[key],d=entry?.definition;const edit=(text:string)=>onChange({...value,[key]:{...entry,value:text}});return <div key={key}>
   <label>{prefix}{businessLabels[key]}{d?.type==='ENUM'?<select value={entry?.value??''} onChange={e=>edit(e.target.value)} required={d.required}><option value="">请选择</option>{d.options?.map(x=><option key={x}>{x}</option>)}</select>:key==='test_purpose'?<textarea value={entry?.value??''} onChange={e=>edit(e.target.value)} maxLength={d?.maxLength??4000} required={d?.required}/>:<input value={entry?.value??''} onChange={e=>edit(e.target.value)} maxLength={d?.type==='NUMBER'?100:d?.maxLength??(key==='reviewer'?36:200)} required={d?.required} inputMode={d?.type==='NUMBER'?'decimal':undefined}/>}</label>
   {entry?.revision&&<small>字段定义 v{entry.revision} · {entry.kind??'CUSTOM'}</small>}
   {definitions.filter(f=>f.definition.key===key).map(f=><button type="button" key={f.id} onClick={()=>onChange({...value,[key]:{fieldId:f.id,revision:f.rowVersion,kind:'CUSTOM',definition:f.definition,value:entry?.value??f.definition.defaultValue??null}})}>采用 {key} v{f.rowVersion}</button>)}
  </div>})}
  {projectId&&<><button type="button" onClick={()=>void load(null)}>读取项目字段配置</button>{cursor&&<button type="button" onClick={()=>void load(cursor)}>更多字段配置</button>}</>}
  <p role="status">{notice}</p>
 </fieldset>
}
