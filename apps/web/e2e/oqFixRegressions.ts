import { expect, type Page, test } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { checkDefectCompletion, readDefectProof, selectCycleRetest } from './oqDefectResume'

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
  const rt=await put<{attemptId:string}>(`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion)
  await finish(await read<Attempt>(path(rt.attemptId)),'PASS');await transition('REOPENED')
  phase='completed-reopened'
  const completed=await proof(d.id),marker=checkDefectCompletion(completed,'REOPENED',undefined,true)!
  // Lost local marker after a successful terminal response is recovered by read-only proof.
  expect(checkDefectCompletion(completed,'REOPENED')).toEqual(marker)
  const snapshot=async()=>({defect:await read(`${root}/defects/${d.id}`),retests:await all(page,`${root}/defects/${d.id}/retests`),audit:await all(page,`${root}/history/defect/${d.id}`),attempts:await all(page,`${root}/runs/${run.run.id}/attempts/page`),signatures:await read(`${root}/signatures/DEVIATION_CLOSURE/${d.id}/${d.currentRevision.id}`)})
  const stable=await snapshot()
  expect(checkDefectCompletion(await proof(d.id),'REOPENED',marker,true)).toEqual(marker)
  expect(checkDefectCompletion(await proof(d.id),'REOPENED',marker,true)).toEqual(marker)
  expect(await snapshot()).toEqual(stable)
  phase='stable-completed-resume'
  await transition('RESOLVED')
  const before=await snapshot(),current=await proof(d.id),writes:string[]=[]
  const track=(request:import('@playwright/test').Request)=>{if(request.method()!=='GET'&&request.url().includes(root))writes.push(request.method()+' '+new URL(request.url()).pathname)}
  page.on('request',track)
  let conflict=''
  try{checkDefectCompletion(await proof(d.id),'REOPENED',marker,true)}catch(e){conflict=(e as Error).message}
  page.off('request',track)
  const after=await snapshot()
  expect(conflict).toContain('演示恢复冲突');expect(writes).toEqual([]);expect(after).toEqual(before)
  phase='new-cycle-conflict-without-writes'
  expect(selectCycleRetest(current.retests,current.resolutionId!)).toBeUndefined()
  const currentRetest=await put<{attemptId:string}>(`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion)
  // An ordinary edit in RESOLVED advances the content revision, not the solution cycle.
  d=await put(`${root}/defects/${d.id}`,{title:d.currentRevision.title,description:'SYNTHETIC RESOLVED 内容编辑',severity:d.currentRevision.severity,priority:d.currentRevision.priority,businessFields:d.currentRevision.businessFields},'PATCH',d.rowVersion)
  const edited=await proof(d.id),selected=selectCycleRetest(edited.retests,current.resolutionId!)!
  expect(edited.resolutionId).toBe(current.resolutionId);expect(edited.revisionId).not.toBe(edited.resolutionId)
  expect(selected.attemptId).toBe(currentRetest.attemptId);expect(selected.resolvedRevisionId).not.toBe(oldCycle)
  evidence.recovery={defectId:d.id,marker,newCycle:current.resolutionId,editedRevision:edited.revisionId,oldCycle,selected,conflict,writes,before,after}
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
