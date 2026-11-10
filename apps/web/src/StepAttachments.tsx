import { useCallback, useState } from 'react'
import AttachmentsPanel from './AttachmentsPanel'
import type { ProjectAccess } from './projectAccess'

export default function StepAttachments({projectId,stepId,ordinal,writable,access,resetSignal,onLock}:{
  projectId:string;stepId:string;ordinal:number;writable:boolean;access:ProjectAccess;resetSignal:number;onLock:(id:string,locked:boolean)=>void
}) {
  const [open,setOpen] = useState(false), [locked,setLocked] = useState(false)
  const lock = useCallback((value:boolean)=>{setLocked(value);onLock(`attachment:${stepId}`,value)},[stepId,onLock])
  if(!access.permissions.includes('attachment:read') || !access.permissions.includes('asset:read')) return null
  const permissions = writable ? access : {...access,permissions:access.permissions.filter(p=>!['attachment:create','asset:upload','asset:version:create'].includes(p))}
  return <section aria-label={`步骤 ${ordinal} 附件`}>
    <button type="button" className="secondary-button" aria-expanded={open} disabled={locked} onClick={()=>setOpen(value=>!value)}>{open?'收起':'查看'}步骤 {ordinal} 附件</button>
    {open && <AttachmentsPanel projectId={projectId} target={{kind:'RUN_STEP',id:stepId}} access={permissions} resetSignal={resetSignal} onLockChange={lock}/>}
  </section>
}
