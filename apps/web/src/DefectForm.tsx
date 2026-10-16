import type { DefectContent } from './defectsApi'
export const emptyDefect = ():DefectContent=>({title:'',description:'',severity:'MEDIUM',priority:'MEDIUM'})
export default function DefectForm({value,onChange,disabled,prefix=''}:{value:DefectContent;onChange:(v:DefectContent)=>void;disabled:boolean;prefix?:string}) {
 return <fieldset disabled={disabled}><legend>{prefix}缺陷内容</legend>
  <label>{prefix}缺陷标题<input value={value.title} maxLength={500} onChange={e=>onChange({...value,title:e.target.value})}/></label>
  <label>{prefix}缺陷描述<textarea value={value.description} maxLength={100000} onChange={e=>onChange({...value,description:e.target.value})}/></label>
  <label>{prefix}缺陷严重度<select value={value.severity} onChange={e=>onChange({...value,severity:e.target.value})}>{['LOW','MEDIUM','HIGH','CRITICAL'].map(x=><option key={x}>{x}</option>)}</select></label>
  <label>{prefix}缺陷优先级<select value={value.priority} onChange={e=>onChange({...value,priority:e.target.value})}>{['LOW','MEDIUM','HIGH','CRITICAL'].map(x=><option key={x}>{x}</option>)}</select></label>
 </fieldset>
}
