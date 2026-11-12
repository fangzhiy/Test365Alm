import { expect, it } from 'vitest'
import { checkDefectCompletion, readDefectProof, resumeAfterDefectPreflight, selectCycleRetest, type DefectProof, type DefectCompletion } from '../e2e/oqDefectResume'

it('never borrows a historical PASS when the current resolution has no retest', () => {
 expect(selectCycleRetest([{id:'old-pass',resolvedRevisionId:'old',runId:'run',attemptId:'pass'}], 'current')).toBeUndefined()
})
const proof:DefectProof={id:'d',revisionId:'reopened',stateEntryId:'reopened',rowVersion:3,status:'REOPENED',resolutionId:'resolution',resolutionEntries:['resolution'],retests:[{id:'rt',resolvedRevisionId:'resolution',runId:'run',attemptId:'pass',status:'FINISHED',conclusion:'PASS'}]}
it('rejects the exact interrupted v1.1 shape with an existing defect and a second server cycle before work or backfill',async()=>{
 const checkpoint={version:'1.1',projectId:'p',cases:{'OQD-13':{testId:'t',revisionId:'v',runId:'run',originalAttemptId:'fail',defectId:'d',stages:[]}},stages:['fourteen-reviewed-signed-executed']}
 const item=checkpoint.cases['OQD-13']
 expect(item).not.toHaveProperty('defectCompletion');expect(item).not.toHaveProperty('resolutionId')
 const completed=checkpoint.stages.includes('closed-and-reopened-defects')
 expect(completed).toBe(false)
 let proofReads=0,actionsExecuted=0,recorded=0,conflict:string|null=null
 try{
  await resumeAfterDefectPreflight([{defectId:item.defectId,expectedStatus:'REOPENED',marker:undefined,completed,record:()=>{recorded++}}],async()=>{
   proofReads++
   return {...proof,status:'RESOLVED',revisionId:'second-resolution',stateEntryId:'second-resolution',resolutionId:'second-resolution',resolutionEntries:['resolution','second-resolution']}
  },async()=>{actionsExecuted++})
 }catch(e){conflict=(e as Error).message}
 // The regression fails on a67be50 with reads=0/actions=1/conflict=null.
 expect({proofReads,actionsExecuted,conflict,recorded}).toEqual({proofReads:1,actionsExecuted:0,conflict:expect.stringContaining('演示恢复冲突'),recorded:0})
 expect(item).not.toHaveProperty('defectCompletion');expect(item).not.toHaveProperty('resolutionId')
})
it('the actual resume orchestrator rejects preflight before any subsequent business action',async()=>{
 const marker=checkDefectCompletion(proof,'REOPENED',undefined,true)!
 let actions=0,recorded=0
 const stages=[{defectId:'d',expectedStatus:'REOPENED',marker,completed:true,record:()=>{recorded++}}]
 await expect(resumeAfterDefectPreflight(stages,async()=>({...proof,status:'RESOLVED',revisionId:'new'}),async()=>{actions++})).rejects.toThrow('演示恢复冲突')
 expect(actions).toBe(0);expect(recorded).toBe(0)
 await resumeAfterDefectPreflight(stages,async()=>proof,async()=>{actions++})
 expect(actions).toBe(1);expect(recorded).toBe(1)
})
it.each([
 ['open before first resolution','OPEN',null,[],'open','open',[]],
 ['first resolution without retest','RESOLVED','resolution',['resolution'],'resolution','resolution',[]],
 ['content edit within first resolution','RESOLVED','resolution',['resolution'],'content-edit','resolution',[]],
 ['unfinished retest in first resolution','RESOLVED','resolution',['resolution'],'resolution','resolution',[{...proof.retests[0],status:'RUNNING',conclusion:null}]],
 ['successful terminal transition without local marker','REOPENED','resolution',['resolution'],'reopened','reopened',proof.retests],
] as const)('reads and safely resumes an unmarked single-cycle checkpoint: %s',async(label,status,resolutionId,entries,revisionId,stateEntryId,retests)=>{
 let proofReads=0,work=0,marker:DefectCompletion|undefined
 await resumeAfterDefectPreflight([{defectId:'d',expectedStatus:'REOPENED',completed:false,record:m=>{marker=m}}],async()=>{
  proofReads++
  return {...proof,status,resolutionId,resolutionEntries:[...entries],revisionId,stateEntryId,retests:[...retests]}
 },async()=>{work++})
 expect(proofReads,label).toBe(1);expect(work).toBe(1)
 if(status==='REOPENED')expect(marker).toMatchObject({resolutionId:'resolution',revisionId:'reopened',attemptId:'pass'})
 else expect(marker).toBeUndefined()
})
it('allows a new scene but verifies every existing defect before any completion backfill',async()=>{
 let reads=0,work=0,records=0
 await resumeAfterDefectPreflight([{expectedStatus:'REOPENED',completed:false,record:()=>{records++}}],async()=>{reads++;return proof},async()=>{work++})
 expect({reads,work,records}).toEqual({reads:0,work:1,records:0})
 work=0
 await expect(resumeAfterDefectPreflight([
  {defectId:'d',expectedStatus:'REOPENED',completed:false,record:()=>{records++}},
  {defectId:'other',expectedStatus:'REOPENED',completed:false,record:()=>{records++}},
 ],async id=>{
  reads++
  return id==='d'?proof:{...proof,id,resolutionEntries:['resolution','user-cycle']}
 },async()=>{work++})).rejects.toThrow('多个解决周期')
 expect({reads,work,records}).toEqual({reads:2,work:0,records:0})
})
it('backfills a v1.1 completion by reading a unique original cycle, then rejects a new user cycle',()=>{
 const marker=checkDefectCompletion(proof,'REOPENED',undefined,true)!
 expect(marker).toMatchObject({revisionId:'reopened',resolutionId:'resolution',attemptId:'pass'})
 expect(checkDefectCompletion(proof,'REOPENED',marker,true)).toEqual(marker)
 expect(()=>checkDefectCompletion({...proof,status:'RESOLVED',revisionId:'new-resolution',rowVersion:4,resolutionId:'new-resolution',resolutionEntries:['resolution','new-resolution']},'REOPENED',marker,true)).toThrow('演示恢复冲突')
 expect(()=>checkDefectCompletion({...proof,status:'RESOLVED',revisionId:'new-resolution'},'REOPENED',undefined,true)).toThrow('服务端为 RESOLVED')
})
it('rejects changed completed revision and extra retests, and preserves interrupted terminal writes without a marker',()=>{
 const marker=checkDefectCompletion(proof,'REOPENED')!
 expect(()=>checkDefectCompletion({...proof,revisionId:'manual-edit',rowVersion:4},'REOPENED',marker)).toThrow('已完成对象')
 expect(()=>checkDefectCompletion({...proof,retests:[...proof.retests,{...proof.retests[0],id:'extra'}]},'REOPENED',marker)).toThrow('复测关系')
 expect(checkDefectCompletion({...proof,status:'RESOLVED',revisionId:'edited-resolved'},'REOPENED')).toBeUndefined()
})
it('does not adopt a manual edit after a terminal transition when backfilling an old checkpoint',()=>{
 expect(()=>checkDefectCompletion({...proof,revisionId:'later-edit',rowVersion:4},'REOPENED',undefined,true)).toThrow('演示恢复冲突')
})
it('selects the exact resolution cycle independently of list or UUID order', () => {
 const current={id:'aaa',resolvedRevisionId:'current',runId:'run',attemptId:'current-attempt'}
 expect(selectCycleRetest([{id:'zzz',resolvedRevisionId:'old',runId:'run',attemptId:'old-pass'},current], 'current')).toBe(current)
})
it('gets a resolution entry from the authorized report and history, not an edit made while RESOLVED',async()=>{
 const read=async(path:string)=>path.endsWith('/defects/d')?{id:'d',projectId:'p',rowVersion:3,currentRevision:{id:'content-edit',status:'RESOLVED'}}:{items:[{id:'d',revision:{id:'content-edit',status:'RESOLVED'},resolved_revision_id:'resolution'}]}
 const all=async(path:string)=>path.endsWith('/retests')?[]:[{id:'open',revisionNo:1,status:'OPEN'},{id:'resolution',revisionNo:2,status:'RESOLVED'},{id:'content-edit',revisionNo:3,status:'RESOLVED'}]
 expect(await readDefectProof(read,all,'/api/v1/projects/p','d')).toMatchObject({resolutionId:'resolution',revisionId:'content-edit',resolutionEntries:['resolution']})
})
