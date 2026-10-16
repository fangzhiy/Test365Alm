import { useCallback,useState } from 'react'
import DefectCreate from './DefectCreate'
import type { DefectSource } from './defectsApi'
export default function FailedStepDefect({projectId,source,disabled,onLockChange,onDenied}:{projectId:string;source:DefectSource;disabled:boolean;onLockChange:(id:string,v:boolean)=>void;onDenied:()=>void}) {
 const [open,setOpen]=useState(false)
 const id=`${source.attemptId}:${source.stepKey}`
 const lock=useCallback((v:boolean)=>onLockChange(id,v),[id,onLockChange])
 return <div>{!open?<button disabled={disabled} onClick={()=>setOpen(true)}>从失败步骤创建缺陷</button>:<DefectCreate projectId={projectId} source={source} onLockChange={lock} onDenied={onDenied}/>}</div>
}
