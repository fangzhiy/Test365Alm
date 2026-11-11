import { expect, type Page, test } from '@playwright/test'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { captureApiWrites } from './oqRequestAudit'
import { checkDefectCompletion, readDefectProof, resumeAfterDefectPreflight, selectCycleRetest, type DefectCompletion } from './oqDefectResume'

// Reuse the runner's actual OIDC/CSRF transport; no intercepted API or SQL fixture.
export async function oqFixRegressions(page:Page, admin:Page, scope:{tenantId:string;domainId:string}, memberId:string, rootDir:string,
 get: (page:Page,path:string)=>Promise<unknown>,
 write:(page:Page,path:string,data:unknown,method?:string,version?:number)=>Promise<unknown>,
 all:(page:Page,path:string)=>Promise<unknown[]>, choose:(page:Page,tenant:string,project:string)=>Promise<void>) {
 let phase='create-project'
 const diagnostics=resolve(rootDir,'local-evidence/r03/oq-demo-01');mkdirSync(diagnostics,{recursive:true})
 try {
 // These are the actual server wire shapes, confined to the test boundary.
 type Revision={id:string;title:string;description:string;severity:string;priority:string;businessFields:unknown;status:string}
 type Defect={id:string;rowVersion:number;currentRevision:Revision}
 type Attempt={id:string;rowVersion:number;status:string;steps:{stepKey:string;rowVersion:number;conclusion:string}[]}
 const read=<T,>(path:string)=>get(page,path) as Promise<T>
 const put=<T,>(path:string,data:unknown,method='POST',version?:number)=>write(page,path,data,method,version) as Promise<T>
 const project=await write(admin,'/api/v1/projects',{tenantId:scope.tenantId,domainId:scope.domainId,code:'SYNTHETIC-OQD-FIX01',name:'SYNTHETIC FIX01 隔离回归'}) as {id:string}
 const root=`/api/v1/projects/${project.id}`
 await write(admin,`${root}/members/${memberId}`,{roles:['PROJECT_MEMBER'],authorizationVersion:0},'PUT')
 const c=await put<{id:string;currentRevision:{id:string}}>(root+'/tests',{testType:'MANUAL',title:'SYNTHETIC FIX01 导航与恢复',description:'独立故障回归，不计入标准十四实例',preconditions:'合成隔离环境',steps:[{ordinal:1,action:'合成操作',expected:'合成预期'}]})
 const set=await put<{id:string}>(root+'/test-sets',{name:'SYNTHETIC FIX01 回归测试集',description:'不改变标准演示口径'})
 const instance=await put<{id:string}>(`${root}/test-sets/${set.id}/instances`,{testCaseId:c.id,testRevisionId:c.currentRevision.id})
 const run=await put<{run:{id:string};currentAttempt:Attempt}>(root+'/runs',{testInstanceId:instance.id})
 const path=(id:string)=>`${root}/runs/${run.run.id}/attempts/${id}`
 async function finish(a:Attempt,conclusion:string){
  for(const s of a.steps)if(s.conclusion==='NOT_RUN')a=await put(path(a.id)+'/steps/'+s.stepKey,{actualResult:'SYNTHETIC FIX01 合成观察',conclusion},'PUT',s.rowVersion)
  return put<Attempt>(path(a.id)+'/finish',{},'POST',a.rowVersion)
 }
 const original=await finish(run.currentAttempt,'FAIL')
 phase='source-fail-finished'
 const proof=(id:string)=>readDefectProof(p=>get(page,p),p=>all(page,p),root,id)
 const evidence:Record<string,unknown>={classification:'SYNTHETIC',projectId:project.id,setId:set.id,instanceId:instance.id,runId:run.run.id,originalAttemptId:original.id}
 await test.step('FIX01：已完成后人工开启新周期，恢复只读拒绝且不追加业务记录',async()=>{
  let d=await put<Defect>(root+'/defects/from-step',{title:'SYNTHETIC FIX01 重开偏差',description:'正常API建立的独立冲突样本',severity:'HIGH',priority:'HIGH',runId:run.run.id,attemptId:original.id,stepKey:original.steps[0].stepKey})
  const transition=async(status:string)=>{d=await put(`${root}/defects/${d.id}/transitions`,{status,reason:'SYNTHETIC FIX01 合法流转'},'POST',d.rowVersion)}
  await transition('RESOLVED')
  phase='first-resolution'
  const oldCycle=(await proof(d.id)).resolutionId!
  const singleCycleReads:{status:string;proofReads:number;continued:number;backfilled:boolean}[]=[]
  const resumeSingleCycle=async()=>{
   let proofReads=0,continued=0,backfilled:DefectCompletion|undefined
   await resumeAfterDefectPreflight([{defectId:d.id,expectedStatus:'REOPENED',completed:false,record:m=>{backfilled=m}}],async id=>{proofReads++;return proof(id)},async()=>{continued++})
   expect(proofReads).toBe(1);expect(continued).toBe(1)
   singleCycleReads.push({status:d.currentRevision.status,proofReads,continued,backfilled:!!backfilled})
   return backfilled
  }
  expect(await resumeSingleCycle()).toBeUndefined()
  d=await put(`${root}/defects/${d.id}`,{title:d.currentRevision.title,description:'SYNTHETIC FIX03 原解决周期内编辑',severity:d.currentRevision.severity,priority:d.currentRevision.priority,businessFields:d.currentRevision.businessFields},'PATCH',d.rowVersion)
  expect((await proof(d.id)).resolutionId).toBe(oldCycle)
  expect(d.currentRevision.id).not.toBe(oldCycle)
  expect(await resumeSingleCycle()).toBeUndefined()
  const rt=await put<{attemptId:string}>(`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion)
  expect((await read<Attempt>(path(rt.attemptId))).status).toBe('RUNNING')
  expect(await resumeSingleCycle()).toBeUndefined()
  await finish(await read<Attempt>(path(rt.attemptId)),'PASS');await transition('REOPENED')
  phase='completed-reopened'
  const completed=await proof(d.id),marker=checkDefectCompletion(completed,'REOPENED',undefined,true)!
  const checkpointFile=resolve(diagnostics,'fix02-recovery-checkpoint.json')
  writeFileSync(checkpointFile,JSON.stringify({version:'1.1',projectId:project.id,defectCompletion:marker},null,2))
  type LegacyCase={testId:string;revisionId:string;instanceId:string;runId:string;originalAttemptId:string;defectId:string;stages:string[];defectCompletion?:DefectCompletion;resolutionId?:string}
  type LegacyCheckpoint={scenario:string;version:string;projectId:string;tenantId:string;setId:string;cases:Record<string,LegacyCase>;stages:string[]}
  const legacyFile=resolve(diagnostics,'fix03-legacy-checkpoint.json')
  // Save the actual interrupted shape before the manual new cycle. Never
  // backfill this checkpoint or manufacture completed=true for its preflight.
  const legacy:LegacyCheckpoint={scenario:'OQ-DEMO-01',version:'1.1',projectId:project.id,tenantId:scope.tenantId,setId:set.id,
   cases:{'OQD-13':{testId:c.id,revisionId:c.currentRevision.id,instanceId:instance.id,runId:run.run.id,originalAttemptId:original.id,defectId:d.id,stages:[]}},stages:['fourteen-reviewed-signed-executed']}
  writeFileSync(legacyFile,JSON.stringify(legacy,null,2))
  // Lost local marker after a successful terminal response is recovered by read-only proof.
  expect(checkDefectCompletion(completed,'REOPENED')).toEqual(marker)
  const snapshot=async()=>{
   const revisions=await all(page,`${root}/defects/${d.id}/revisions`) as Revision[]
   const attempts=await all(page,`${root}/runs/${run.run.id}/attempts/page`) as Attempt[]
   return {defect:await read(`${root}/defects/${d.id}`),revisions,retests:await all(page,`${root}/defects/${d.id}/retests`),audit:await all(page,`${root}/history/defect/${d.id}`),attempts,
    signatures:{closure:await Promise.all(revisions.map(r=>read(`${root}/signatures/DEVIATION_CLOSURE/${d.id}/${r.id}`))),
     completion:await Promise.all(attempts.map(a=>read(`${root}/signatures/RUN_COMPLETION/${run.run.id}/${a.id}`))),
     approval:await read(`${root}/signatures/TEST_APPROVAL/${c.id}/${c.currentRevision.id}`)}}
  }
  const stable=await snapshot()
  expect(await resumeSingleCycle()).toEqual(marker)
  expect(checkDefectCompletion(await proof(d.id),'REOPENED',marker,true)).toEqual(marker)
  expect(checkDefectCompletion(await proof(d.id),'REOPENED',marker,true)).toEqual(marker)
  expect(await snapshot()).toEqual(stable)
  phase='stable-completed-resume'
  await transition('RESOLVED')
  const audit=captureApiWrites(page.request,root)
  let conflict='',actionsExecuted=0
  let before:Awaited<ReturnType<typeof snapshot>>,after:Awaited<ReturnType<typeof snapshot>>
  let probeWrites:string[]=[]
  let legacyRecovery:Record<string,unknown>|undefined
  try{
   // Genuine, isolated business write proves this observes APIRequestContext.
   // It is not blocked/intercepted and its real 2xx response is required.
   await put(root+'/test-sets',{name:'SYNTHETIC FIX02 write-counter probe',description:'Independent controlled write'})
   probeWrites=[...audit.writes]
   expect(probeWrites).toEqual([`POST ${root}/test-sets`])
   audit.clear()
   before=await snapshot()
   try{
    const checkpoint=JSON.parse(readFileSync(checkpointFile,'utf8')) as {projectId:string;defectCompletion:DefectCompletion}
    expect(checkpoint.projectId).toBe(project.id)
    await resumeAfterDefectPreflight([{defectId:d.id,expectedStatus:'REOPENED',marker:checkpoint.defectCompletion,completed:true,record:()=>{}}],proof,async()=>{
     actionsExecuted++
     await transition('REOPENED')
    })
   }catch(e){conflict=(e as Error).message}
   after=await snapshot()
   expect(actionsExecuted).toBe(0);expect(audit.writes).toEqual([]);expect(after).toEqual(before)
   audit.clear()
   const legacyText=readFileSync(legacyFile,'utf8'),checkpoint=JSON.parse(legacyText) as LegacyCheckpoint
   const item=checkpoint.cases['OQD-13'],completed=checkpoint.stages.includes('closed-and-reopened-defects')
   const checkpointHasDefectCompletion=Object.hasOwn(item,'defectCompletion')
   const checkpointHasResolutionId=Object.hasOwn(item,'resolutionId')
   const checkpointHasGlobalCompletion=completed
   expect(checkpoint.projectId).toBe(project.id)
   expect(item).toMatchObject({testId:c.id,revisionId:c.currentRevision.id,instanceId:instance.id,runId:run.run.id,originalAttemptId:original.id,defectId:d.id})
   expect(checkpointHasDefectCompletion).toBe(false);expect(checkpointHasResolutionId).toBe(false);expect(checkpointHasGlobalCompletion).toBe(false)
   let proofReads=0,legacyActions=0,backfills=0,legacyConflict=''
   let resolutionEntries:string[]=[]
   const legacyBefore=await snapshot()
   try{
    await resumeAfterDefectPreflight([{defectId:item.defectId,expectedStatus:'REOPENED',marker:item.defectCompletion,completed,record:m=>{
     backfills++;item.defectCompletion=m;writeFileSync(legacyFile,JSON.stringify(checkpoint,null,2))
    }}],async id=>{proofReads++;const p=await proof(id);resolutionEntries=p.resolutionEntries;return p},async()=>{
     legacyActions++;await transition('REOPENED')
    })
   }catch(e){legacyConflict=(e as Error).message}
   const legacyAfter=await snapshot(),writes=[...audit.writes],checkpointUnchanged=readFileSync(legacyFile,'utf8')===legacyText
   expect(proofReads).toBe(1);expect(resolutionEntries).toHaveLength(2)
   expect(legacyConflict).toContain('多个解决周期');expect(legacyActions).toBe(0);expect(backfills).toBe(0)
   expect(writes).toEqual([]);expect(legacyAfter).toEqual(legacyBefore);expect(checkpointUnchanged).toBe(true)
   legacyRecovery={checkpointHasDefectCompletion,checkpointHasResolutionId,checkpointHasGlobalCompletion,proofReads,resolutionEntries,
    actionsExecuted:legacyActions,backfills,writes,before:legacyBefore,after:legacyAfter,checkpointUnchanged,conflict:legacyConflict}
  }finally{audit.stop()}
  const current=await proof(d.id),writes=[...audit.writes]
  expect(conflict).toContain('演示恢复冲突');expect(actionsExecuted).toBe(0);expect(writes).toEqual([]);expect(after!).toEqual(before!)
  phase='new-cycle-conflict-without-writes'
  expect(selectCycleRetest(current.retests,current.resolutionId!)).toBeUndefined()
  const currentRetest=await put<{attemptId:string}>(`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion)
  // An ordinary edit in RESOLVED advances the content revision, not the solution cycle.
  d=await put(`${root}/defects/${d.id}`,{title:d.currentRevision.title,description:'SYNTHETIC RESOLVED 内容编辑',severity:d.currentRevision.severity,priority:d.currentRevision.priority,businessFields:d.currentRevision.businessFields},'PATCH',d.rowVersion)
  const edited=await proof(d.id),selected=selectCycleRetest(edited.retests,current.resolutionId!)!
  expect(edited.resolutionId).toBe(current.resolutionId);expect(edited.revisionId).not.toBe(edited.resolutionId)
  expect(selected.attemptId).toBe(currentRetest.attemptId);expect(selected.resolvedRevisionId).not.toBe(oldCycle)
  evidence.recovery={defectId:d.id,marker,newCycle:current.resolutionId,editedRevision:edited.revisionId,oldCycle,selected,conflict,writes,probeWrites,actionsExecuted,before:before!,after:after!}
  evidence.legacyRecovery=legacyRecovery
  evidence.singleCycleRecovery=singleCycleReads
  writeFileSync(resolve(diagnostics,'fix01-regressions.json'),JSON.stringify(evidence,null,2))
  await finish(await read<Attempt>(path(currentRetest.attemptId)),'PASS')
  phase='current-cycle-retest-finished'
 })
 await test.step('FIX01：导航保持打开，刷新后的进行中与完成 Attempt 使用确切报告目标',async()=>{
  await page.goto('/');await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();await choose(page,scope.tenantId,project.id)
  const panel=page.locator('section.execution-panel')
  await panel.getByRole('button',{name:/^SYNTHETIC FIX01 回归测试集/}).filter({has:page.locator('small')}).click()
  await panel.getByRole('button',{name:'查看绑定修订 SYNTHETIC FIX01 导航与恢复'}).click()
  const nav=panel.getByRole('region',{name:'实例修订与闭环导航'})
  await expect(nav).toContainText('已完成')
  const started=await put<Attempt>(`${root}/runs/${run.run.id}/attempts`,{})
  phase='navigation-new-attempt'
  const refresh=async()=>{const loaded=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/test-sets/${set.id}/execution-grid`&&r.request().method()==='GET');await panel.getByRole('button',{name:'刷新实例网格'}).click();expect((await loaded).status()).toBe(200)}
  await refresh();await expect(nav).toContainText('运行中');await expect(nav.getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).toHaveCount(0)
  let active=await put<Attempt>(path(started.id)+'/pause',{},'POST',started.rowVersion)
  await refresh();await expect(nav).toContainText('已暂停');await expect(nav.getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'})).toHaveCount(0)
  phase='navigation-paused'
  active=await put(path(started.id)+'/resume',{},'POST',active.rowVersion);await finish(active,'PASS');await refresh()
  await expect(nav).toContainText('已完成');const reportPath=`${root}/reports/runs/${run.run.id}/attempts/${started.id}`,response=page.waitForResponse(r=>new URL(r.url()).pathname===reportPath)
  await nav.getByRole('button',{name:'实例最新 Attempt 与关联偏差报告'}).click();expect((await response).status()).toBe(200)
  const dialog=page.getByRole('dialog',{name:'业务报告'});await expect(dialog).toContainText('数据完整加载');await expect(dialog).toContainText(started.id)
  const dir=resolve(rootDir,'local-evidence/r03/oq-demo-01');mkdirSync(dir,{recursive:true});await dialog.screenshot({path:resolve(rootDir,'output/pdf/oq-demo-01/fix01-navigation.png')})
  evidence.navigation={attemptId:started.id,reportPath,status:'FINISHED',conclusion:'PASS'}
  await dialog.getByRole('button',{name:'返回业务页面'}).click()
  writeFileSync(resolve(dir,'fix01-regressions.json'),JSON.stringify(evidence,null,2))
  phase='complete'
 })
 }finally{writeFileSync(resolve(diagnostics,'fix01-progress.json'),JSON.stringify({phase,classification:'SYNTHETIC'},null,2))}
}
