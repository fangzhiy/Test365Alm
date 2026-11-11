export type RetestRef = { id: string; resolvedRevisionId: string; runId: string; attemptId: string; status?:string; conclusion?:string|null }
export type DefectCompletion = {
 defectId:string; revisionId:string; rowVersion:number; status:string; resolutionId:string
 retestId:string; runId:string; attemptId:string; retestIds:string[]
}
export type DefectProof = {
 id:string; revisionId:string; stateEntryId:string; rowVersion:number; status:string; resolutionId:string|null
 // Immutable revision history, in revision-number order, establishes cycle entries.
 resolutionEntries:string[]; retests:RetestRef[]
}
type Revision = {id:string; revisionNo:number; status:string}
type Defect = {id:string;projectId:string;rowVersion:number;currentRevision:Revision}
type ReportItem = {id:string;revision:{id:string;status:string};resolved_revision_id:string|null}
type CompletedStage = {
 defectId?:string; expectedStatus:string; marker?:DefectCompletion; completed:boolean
 record:(marker:DefectCompletion)=>void
}

// The scene runner and isolated regression use this same boundary: every
// existing defect must be read-verified before any remaining business work,
// including interrupted v1.1 checkpoints that predate completion markers.
export async function resumeAfterDefectPreflight(stages:CompletedStage[], read:(id:string)=>Promise<DefectProof>, work:()=>Promise<void>) {
 const verified:Array<{stage:CompletedStage;marker:DefectCompletion}>=[]
 for(const stage of stages){
  if(!stage.defectId){
   if(stage.marker || stage.completed)throw new Error('演示恢复冲突：已完成偏差身份缺失；保留现场，不追加流转')
   continue // A new scene has no defect yet; creation remains in the work phase.
  }
  const proof=await read(stage.defectId)
  if(!stage.marker && proof.resolutionEntries.length>1)throw new Error(`演示恢复冲突：偏差 ${stage.defectId} 的旧检查点缺少原周期身份，服务端存在多个解决周期；保留现场，不追加流转`)
  const marker=checkDefectCompletion(proof,stage.expectedStatus,stage.marker,stage.completed)
  if(marker)verified.push({stage,marker})
 }
 for(const {stage,marker} of verified)stage.record(marker)
 await work()
}

export async function readDefectProof(read:(path:string)=>Promise<unknown>, all:(path:string)=>Promise<unknown[]>, root:string, id:string):Promise<DefectProof> {
 const d=await read(`${root}/defects/${id}`) as Defect
 const report=await read(`${root}/reports/defects`) as {items:ReportItem[]}
 const item=report.items.find(r=>r.id===id)
 const revisions=(await all(`${root}/defects/${id}/revisions`)) as Revision[]
 const retests=(await all(`${root}/defects/${id}/retests`)) as RetestRef[]
 const ordered=[...revisions].sort((a,b)=>a.revisionNo-b.revisionNo)
 const entries=ordered.filter((r,i)=>r.status==='RESOLVED' && ordered[i-1]?.status!=='RESOLVED').map(r=>r.id)
 if(d.id!==id || !root.endsWith('/'+d.projectId) || !item || item.revision.id!==d.currentRevision.id || item.revision.status!==d.currentRevision.status || ordered.at(-1)?.id!==d.currentRevision.id || item.resolved_revision_id!==(entries.at(-1)??null))throw new Error(`演示恢复冲突：偏差 ${id} 的来源或周期读取不一致，请重新核对；不写入`)
 const stateEntry=ordered.filter((r,i)=>r.status!==ordered[i-1]?.status).at(-1)
 if(!stateEntry)throw new Error(`演示恢复冲突：偏差 ${id} 缺少状态进入修订`)
 return {id,revisionId:d.currentRevision.id,stateEntryId:stateEntry.id,rowVersion:d.rowVersion,status:d.currentRevision.status,resolutionId:item.resolved_revision_id,resolutionEntries:entries,retests}
}

// Extracted from the scene runner so recovery choices can be tested directly.
export function selectCycleRetest(retests: RetestRef[], resolutionId: string) {
 if(!resolutionId)throw new Error('演示恢复冲突：缺少经服务端核验的解决周期')
 const matches=retests.filter(r => r.resolvedRevisionId === resolutionId)
 if(matches.length>1)throw new Error('演示恢复冲突：同一解决周期存在多个复测，请人工核对')
 return matches[0]
}

export function checkDefectCompletion(proof:DefectProof, expectedStatus:string, saved?:DefectCompletion, completed=false) {
 const conflict=(reason:string):never=>{throw new Error(`演示恢复冲突：偏差 ${proof.id} ${reason}；保留现场，不追加流转`)}
 if(saved){
  if(saved.defectId!==proof.id || saved.revisionId!==proof.revisionId || saved.rowVersion!==proof.rowVersion || saved.status!==proof.status || saved.resolutionId!==proof.resolutionId)conflict('已完成对象的状态、修订或解决周期已改变')
  if(JSON.stringify(saved.retestIds)!==JSON.stringify(proof.retests.map(r=>r.id).sort()))conflict('已完成对象的复测关系已改变')
 }
 if(completed && proof.status!==expectedStatus)conflict(`已完成阶段预期 ${expectedStatus}，服务端为 ${proof.status}`)
 if(saved || completed || proof.status===expectedStatus){
  if(proof.stateEntryId!==proof.revisionId)conflict('终态流转后存在内容修改，不能补认原完成修订')
  if(proof.status!==expectedStatus || !proof.resolutionId || proof.resolutionEntries.length!==1 || proof.resolutionEntries[0]!==proof.resolutionId)conflict('无法确认原场景的唯一解决周期')
  const retest=selectCycleRetest(proof.retests,proof.resolutionId!)
  if(!retest || retest.status!=='FINISHED' || retest.conclusion!=='PASS')conflict('原解决周期缺少确切已完成 PASS 复测')
  const marker:DefectCompletion={defectId:proof.id,revisionId:proof.revisionId,rowVersion:proof.rowVersion,status:proof.status,resolutionId:proof.resolutionId!,retestId:retest!.id,runId:retest!.runId,attemptId:retest!.attemptId,retestIds:proof.retests.map(r=>r.id).sort()}
  if(saved && (saved.retestId!==marker.retestId || saved.runId!==marker.runId || saved.attemptId!==marker.attemptId))conflict('原复测身份已改变')
  return marker
 }
 return undefined
}
