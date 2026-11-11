import { expect, it } from 'vitest'
import { checkDefectCompletion, readDefectProof, selectCycleRetest, type DefectProof } from '../e2e/oqDefectResume'

it('never borrows a historical PASS when the current resolution has no retest', () => {
 expect(selectCycleRetest([{id:'old-pass',resolvedRevisionId:'old',runId:'run',attemptId:'pass'}], 'current')).toBeUndefined()
})
const proof:DefectProof={id:'d',revisionId:'reopened',stateEntryId:'reopened',rowVersion:3,status:'REOPENED',resolutionId:'resolution',resolutionEntries:['resolution'],retests:[{id:'rt',resolvedRevisionId:'resolution',runId:'run',attemptId:'pass',status:'FINISHED',conclusion:'PASS'}]}
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
