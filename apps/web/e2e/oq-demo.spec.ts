import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { isOwnedRun, seedProjectAccessScope, assertStepEvidenceFacilities } from './ownedR03'
import { confirmSimulatedSignature } from './signatureUi'
import { checkDefectCompletion, readDefectProof, resumeAfterDefectPreflight, selectCycleRetest, type DefectCompletion } from './oqDefectResume'
import { oqFixRegressions } from './oqFixRegressions'

const envOrigin=()=>process.env.OQ_DEMO_ORIGIN
const rootDir = resolve(process.cwd(),'../..')
const definition = JSON.parse(readFileSync(resolve(rootDir,'tools/scenarios/oq-demo-01-v1.1.json'),'utf8'))
type CaseState = { testId:string; revisionId:string; instanceId?:string; runId?:string; originalAttemptId?:string; defectId?:string; defectCompletion?:DefectCompletion; resolutionId?:string; stages:string[] }
type Checkpoint = { scenario:string;version:string;classification:string;projectId:string;tenantId:string;setId?:string;cases:Record<string,CaseState>;stages:string[];asset?:{assetId:string;revisionId:string;uploadId:string;linked:boolean;newVersionId?:string} }

async function login(page:Page,user:string,password:string) {
 await page.goto('/'); await page.getByRole('link',{name:'登录 Test365Alm'}).click()
 await page.locator('#username').fill(user);await page.locator('#password').fill(password);await page.locator('#kc-login').click()
 await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible()
 return (await (await page.request.get('/api/v1/me')).json()).id as string
}
async function get(page:Page,path:string) { const r=await page.request.get(path);expect(r.status(),path).toBe(200);return r.json() }
async function write(page:Page,path:string,data:unknown,method='POST',version?:number,contentType='application/json') {
 const csrf=await get(page,'/api/v1/csrf')
 const r=await page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID(),'Content-Type':contentType,...(version?{'If-Match':`"${version}"`}:{})},data})
 expect(r.status(),`${method} ${path}`).toBeLessThan(400);return r.json()
}
async function pages(page:Page,path:string) {
 const rows: Array<Awaited<ReturnType<typeof get>>>=[];let cursor:string|null=null;const seen=new Set<string>()
 do {const r=await get(page,path+(path.includes('?')?'&':'?')+'limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));rows.push(...r.items);cursor=r.nextCursor??null;if(cursor){expect(seen.has(cursor)).toBe(false);seen.add(cursor)}}while(cursor)
 return rows
}
async function choose(page:Page,tenant:string,project:string) {
 const region=page.getByRole('region',{name:'租户、项目与成员'})
 await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant)
 const projects=region.locator('.access-selects select').nth(1);await expect(projects.locator(`option[value="${project}"]`)).toHaveCount(1);await projects.selectOption(project)
}

test('real Keycloak UI SYNTHETIC OQ-DEMO-01 fourteen cases complete reviewed signed execution evidence',async({page,browser})=>{
 const persistent=Boolean(process.env.OQ_DEMO_CHECKPOINT)
 test.skip(!persistent&&!isOwnedRun(),'Explicit owned demo or isolated resources required')
 // A separate full fourteen-case scenario (14 real reauth flows and 42 step writes).
 test.setTimeout(600000)
 if(!persistent)assertStepEvidenceFacilities()
 else {
  const guard=JSON.parse(execFileSync('python',['tools/oq_demo.py','--profile','oq-demo-01','--mode','guard'],{cwd:rootDir,encoding:'utf8',timeout:30000}))
  expect(guard).toMatchObject({owned:true,project:'test365alm-demo-oq-demo-01',origin:envOrigin()})
  expect(test.info().project.use.baseURL).toBe(guard.origin)
  expect(resolve(process.env.OQ_DEMO_CHECKPOINT!)).toBe(resolve(rootDir,'local-evidence/demo-oq-demo-01/scenario.json'))
 }
 const env=process.env,dir=resolve(rootDir,persistent?'local-evidence/demo-oq-demo-01':'local-evidence/r03/oq-demo-01')
 mkdirSync(dir,{recursive:true});const output=resolve(rootDir,'output/pdf/oq-demo-01');mkdirSync(output,{recursive:true})
 const checkpoint=process.env.OQ_DEMO_CHECKPOINT??resolve(dir,'scenario.json')
 let saved:Checkpoint|undefined=existsSync(checkpoint)?JSON.parse(readFileSync(checkpoint,'utf8')):undefined
 const progress:{name:string;passed:boolean}[]=[]
 const save=()=>writeFileSync(checkpoint,JSON.stringify(saved,null,2))
 const stage=async(name:string,work:()=>Promise<void>)=>test.step(name,async()=>{let passed=false;try{await work();passed=true}finally{progress.push({name,passed});writeFileSync(resolve(dir,'stages.json'),JSON.stringify(progress,null,2));save()}})
 const ac=await browser.newContext(),vc=await browser.newContext(),admin=await ac.newPage(),viewer=await vc.newPage()
 try {
 const aid=await login(admin,env.R03_TEST_USER!,env.R03_TEST_USER_PASSWORD!),mid=await login(page,env.R03_MEMBER_USER!,env.R03_MEMBER_PASSWORD!),vid=await login(viewer,env.R03_VIEWER_USER!,env.R03_VIEWER_PASSWORD!)
 const scope=persistent?{tenantId:env.OQ_DEMO_TENANT!,domainId:env.OQ_DEMO_DOMAIN!}:seedProjectAccessScope(aid,vid,mid)
 const projects=await get(admin,`/api/v1/projects?tenantId=${scope.tenantId}`)
 const matching=projects.filter((p:{code:string})=>p.code==='SYNTHETIC-OQ-DEMO-01-V1-1');expect(matching.length).toBeLessThan(2)
 const project=matching[0]??await write(admin,'/api/v1/projects',{tenantId:scope.tenantId,domainId:scope.domainId,code:'SYNTHETIC-OQ-DEMO-01-V1-1',name:'SYNTHETIC · OQ-DEMO-01 十四用例完整演示'})
 const root=`/api/v1/projects/${project.id}`
 if(saved){expect(saved).toMatchObject({scenario:'OQ-DEMO-01',version:definition.version,projectId:project.id,tenantId:scope.tenantId})}
 else{saved={scenario:'OQ-DEMO-01',version:definition.version,classification:'SYNTHETIC',projectId:project.id,tenantId:scope.tenantId,cases:{},stages:[]};save()}
 const state=saved
 const proof=(id:string)=>readDefectProof(path=>get(page,path),path=>pages(page,path),root,id)
 await resumeAfterDefectPreflight(['OQD-12','OQD-13'].map(identity=>({
  defectId:state.cases[identity]?.defectId,expectedStatus:identity==='OQD-12'?'CLOSED':'REOPENED',
  marker:state.cases[identity]?.defectCompletion,completed:state.stages.includes('closed-and-reopened-defects'),
  record:(marker:DefectCompletion)=>{state.cases[identity].defectCompletion=marker;save()},
 })),proof,async()=>{
 await stage('授权及目录：保留旧场景，建立独立固定版本',async()=>{
  const members=await get(admin,root+'/members')
  for(const [id,role]of[[mid,'PROJECT_MEMBER'],[vid,'PROJECT_VIEWER']]){
   const member=members.find((m:{principalId:string})=>m.principalId===id)
   if(!member)await write(admin,`${root}/members/${id}`,{roles:[role],authorizationVersion:0},'PUT')
   else expect(member.roles).toEqual([role])
  }
  const sets=await pages(page,root+'/test-sets/page'),found=sets.filter(s=>s.name==='SYNTHETIC OQ-DEMO-01 v1.1');expect(found.length).toBeLessThan(2)
  const set=found[0]??await write(page,root+'/test-sets',{name:'SYNTHETIC OQ-DEMO-01 v1.1',description:definition.notice})
  if(state.setId)expect(state.setId).toBe(set.id);state.setId=set.id;save()
 })
 async function place(kind:string,id:string,completed=false){
  const nodes=await get(page,root+'/folders?kind='+kind)
  let folder=nodes.find((n:{name:string;parentId:string|null})=>n.name==='SYNTHETIC OQ-DEMO-01 v1.1'&&n.parentId===null)
  if(completed)expect(folder,'Completed directory was manually changed; preserve it').toBeDefined()
  folder??=await write(page,root+'/folders',{kind,parentId:null,name:'SYNTHETIC OQ-DEMO-01 v1.1',sortOrder:0})
  const location=await get(page,`${root}/folder-placements/${kind}/${id}`)
  if(completed)expect(location.folderId,'Completed placement changed; preserve manual organization').toBe(folder.id)
  if(location.folderId!==folder.id){expect(location.folderId,'Manual folder placement preserved').toBeNull();await write(page,`${root}/folder-placements/${kind}/${id}`,{folderId:folder.id},'PUT',location.rowVersion)}
 }
 await place('TEST_LAB',state.setId!,state.stages.includes('organized'));state.stages=[...new Set([...state.stages,'organized'])];save()
 await choose(admin,scope.tenantId,project.id);await choose(page,scope.tenantId,project.id);await choose(viewer,scope.tenantId,project.id)
 for(const scenario of definition.cases){
  await stage(`${scenario.id}：不可变修订、指定人复核、批准模拟签署`,async()=>{
   const found=(await pages(page,root+'/tests')).filter(c=>c.title===scenario.title);expect(found.length).toBeLessThan(2)
   const command={testType:scenario.testType,title:scenario.title,description:scenario.description,preconditions:scenario.preconditions,businessFields:{test_purpose:{value:scenario.purpose},reviewer:{value:aid}},steps:scenario.steps}
   let c=state.cases[scenario.id]?await get(page,root+'/tests/'+state.cases[scenario.id].testId):found[0]?await get(page,root+'/tests/'+found[0].id):await write(page,root+'/tests',command)
   if(!state.cases[scenario.id]){state.cases[scenario.id]={testId:c.id,revisionId:c.currentRevision.id,stages:[]};save()}
   const item=state.cases[scenario.id]
   // Only the planned rejection can append a revision. Any other manual edit is preserved and rejected.
   if(c.currentRevision.id!==item.revisionId) {
    const revisions=await get(page,`${root}/tests/${c.id}/revisions`)
    expect(scenario.id).toBe('OQD-01');expect(revisions.length).toBe(2)
    expect(c.currentRevision.description).toBe(command.description+' 拒绝后补充：核对单据号。')
    item.revisionId=c.currentRevision.id;save()
   }
   let review=await get(admin,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`)
   if(scenario.id==='OQD-01'&&c.currentRevision.revisionNo===1){
    if(review.state==='DRAFT')review=await write(page,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`,{action:'SUBMIT',reason:'合成演示初次提交'},'POST',review.rowVersion)
    if(review.state==='SUBMITTED')review=await write(admin,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`,{action:'REJECT',reason:'合成演示：补充核对单据号'},'POST',review.rowVersion)
    expect(review.state).toBe('REJECTED');c=await get(page,root+'/tests/'+c.id)
    c=await write(page,`${root}/tests/${c.id}/revisions`,{title:command.title,preconditions:command.preconditions,businessFields:command.businessFields,description:command.description+' 拒绝后补充：核对单据号。',steps:c.currentRevision.steps.map((s:{stepKey:string;ordinal:number;action:string;expected:string})=>({stepKey:s.stepKey,ordinal:s.ordinal,action:s.action,expected:s.expected}))},'POST',c.rowVersion)
    item.revisionId=c.currentRevision.id;save();review=await get(admin,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`)
   }
   expect(c.currentRevision.id).toBe(item.revisionId)
   expect(c.currentRevision.businessFields.test_purpose.value).toBe(scenario.purpose)
   expect(c.currentRevision.businessFields.reviewer.value).toBe(aid)
   if(review.state==='DRAFT')review=await write(page,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`,{action:'SUBMIT',reason:'合成演示修订提交'},'POST',review.rowVersion)
   if(review.state==='SUBMITTED')review=await write(admin,`${root}/tests/${c.id}/revisions/${item.revisionId}/review`,{action:'APPROVE',reason:'合成技术演示批准，非正式 OQ'},'POST',review.rowVersion)
   expect(review.state).toBe('APPROVED')
   const signature=await get(admin,`${root}/signatures/TEST_APPROVAL/${c.id}/${item.revisionId}`)
   if(!signature.signature){
    const panel=admin.locator('section.test-cases-panel');await panel.getByRole('button',{name:'刷新用例',exact:true}).click()
    await panel.locator('.test-case-list .requirement-row').filter({hasText:scenario.title}).click()
    await confirmSimulatedSignature(admin,'用例批准确认',env.R03_TEST_USER!,env.R03_TEST_USER_PASSWORD!)
   }
   await place('TEST_PLAN',c.id,item.stages.includes('approved-and-signed'))
   const instances=await pages(page,`${root}/test-sets/${state.setId}/instances/page`),existing=instances.filter(i=>i.testCaseId===c.id)
   expect(existing.length).toBeLessThan(2)
   const instance=existing[0]??await write(page,`${root}/test-sets/${state.setId}/instances`,{testCaseId:c.id,testRevisionId:item.revisionId})
   expect(instance.testRevisionId).toBe(item.revisionId);if(item.instanceId)expect(instance.id).toBe(item.instanceId);item.instanceId=instance.id;save()
   const grid=(await pages(page,`${root}/test-sets/${state.setId}/execution-grid`)).find(i=>i.instance.id===instance.id)
   if(item.stages.includes('approved-and-signed'))expect(grid.assigneeId,'Completed assignment changed; preserve manual choice').toBe(mid)
   if(grid.assigneeId===null)await write(page,`${root}/test-instances/${instance.id}/assignee`,{assigneeId:mid,expectedVersion:grid.assignmentVersion},'PUT',grid.assignmentVersion)
   else expect(grid.assigneeId).toBe(mid)
   item.stages.push('approved-and-signed');item.stages=[...new Set(item.stages)];save()
  })
 }
 // Reload the workbench after API fixture writes; directory selection is a real user operation.
 await page.goto('/');await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();await choose(page,scope.tenantId,project.id)
 const execution=page.locator('section.execution-panel')
 await execution.getByRole('button',{name:'SYNTHETIC OQ-DEMO-01 v1.1',exact:true}).click()
 await execution.getByRole('button',{name:/^SYNTHETIC OQ-DEMO-01 v1.1/}).filter({has:page.locator('small')}).click()
 async function openRun(runId:string){await execution.getByRole('button',{name:'刷新测试集',exact:true}).click();await execution.getByRole('button',{name:'SYNTHETIC OQ-DEMO-01 v1.1',exact:true}).click();await execution.getByRole('button',{name:/^SYNTHETIC OQ-DEMO-01 v1.1/}).filter({has:page.locator('small')}).click();const ready=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/runs/${runId}`&&r.request().method()==='GET');await execution.getByRole('button',{name:new RegExp(`^运行 ${runId} `)}).click();expect((await ready).status()).toBe(200);await expect(execution.getByRole('button',{name:'刷新运行详情'})).toBeEnabled()}
 for(const scenario of definition.cases){await stage(`${scenario.id}：指定负责人执行，步骤实际结果独立保存`,async()=>{
  const item=state.cases[scenario.id],runs=(await pages(page,`${root}/runs/page?testSetId=${state.setId}`)).filter(r=>r.testInstanceId===item.instanceId)
  if(!item.runId){expect(runs.length).toBeLessThan(2);const r=runs[0]?await get(page,`${root}/runs/${runs[0].id}`):await write(page,root+'/runs',{testInstanceId:item.instanceId});item.runId=r.run.id;item.originalAttemptId=r.currentAttempt.id;save()}
  const path=`${root}/runs/${item.runId}/attempts/${item.originalAttemptId}`
  let a=await get(page,path);await openRun(item.runId!)
  const attemptReady=page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()==='GET');await execution.getByRole('button',{name:new RegExp(`^尝试 ${a.attemptNo} ·`)}).click();expect((await attemptReady).status()).toBe(200);await expect(execution.getByRole('button',{name:'刷新运行详情'})).toBeEnabled()
  if(scenario.id==='OQD-01'&&!state.asset){
   const bytes=Buffer.from('SYNTHETIC OQ-DEMO-01 固定执行证据第一版；未执行 SAP。\n')
   const pending=(await pages(page,root+'/uploads')).filter(u=>u.filename==='SYNTHETIC-OQD-01-证据-v1.txt');expect(pending.length).toBeLessThan(2);const upload=pending[0]??await write(page,root+'/uploads',{filename:'SYNTHETIC-OQD-01-证据-v1.txt',size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),contentType:'text/plain'})
   // Save the upload identity before publication; resume inspects the durable session.
   state.asset={assetId:'',revisionId:'',uploadId:upload.id,linked:false};save()
  }
  if(scenario.id==='OQD-01'&&state.asset&&!state.asset.linked){
   let upload=await get(page,`${root}/uploads/${state.asset.uploadId}`)
   if(upload.state==='CREATED')await write(page,`${root}/uploads/${upload.id}/content`,Buffer.from('SYNTHETIC OQ-DEMO-01 固定执行证据第一版；未执行 SAP。\n'),'PUT',undefined,'text/plain')
   upload=await get(page,`${root}/uploads/${upload.id}`)
   if(upload.state==='UPLOADED')upload=await write(page,`${root}/uploads/${upload.id}/complete`,{})
   expect(upload.state).toBe('PUBLISHED');state.asset.assetId=upload.assetId;state.asset.revisionId=upload.revisionId;save()
   const links=await get(page,`${root}/attachments?kind=RUN_STEP&runStepId=${a.steps[0].runStepId}`)
   if(!links.items.some((l:{assetRevisionId:string})=>l.assetRevisionId===upload.revisionId))await write(page,root+'/attachments',{kind:'RUN_STEP',runStepId:a.steps[0].runStepId,assetId:upload.assetId,assetRevisionId:upload.revisionId})
   state.asset.linked=true;save()
  }
  if(a.status!=='FINISHED'){
   if(a.status==='PAUSED'){await execution.getByRole('button',{name:'继续',exact:true}).click();a=await get(page,path)}
   if(scenario.id==='OQD-14'&&!item.stages.includes('paused')){await execution.getByRole('button',{name:'暂停',exact:true}).click();await expect(execution).toContainText('已暂停');item.stages.push('paused');save();await execution.getByRole('button',{name:'继续',exact:true}).click();await expect(execution).toContainText('运行中')}
   for(let n=0;n<3;n++){
    a=await get(page,path);const step=a.steps[n],actual=scenario.observations[n]
    if(step.conclusion!=='NOT_RUN'){expect(step.actualResult).toBe(actual);expect(step.conclusion).toBe(scenario.outcomes[n]);continue}
    await execution.getByLabel(`步骤 ${n+1} 实际结果`).fill(actual);await execution.getByLabel(`步骤 ${n+1} 结论`).selectOption(scenario.outcomes[n]);const recorded=page.waitForResponse(r=>new URL(r.url()).pathname===`${path}/steps/${step.stepKey}`&&r.request().method()==='PUT');await execution.getByRole('button',{name:'保存步骤结果',exact:true}).nth(n).click();expect((await recorded).status()).toBe(200);await expect(execution.getByRole('button',{name:'完成运行',exact:true})).toBeEnabled()
   }
   const finished=page.waitForResponse(r=>new URL(r.url()).pathname===path+'/finish'&&r.request().method()==='POST');await execution.getByRole('button',{name:'完成运行',exact:true}).click();expect((await finished).status()).toBe(200);await expect(execution).toContainText(`已完成 · ${scenario.initialConclusion}`)
  }
  a=await get(page,path);expect(a.conclusion).toBe(scenario.initialConclusion)
  const signed=await get(page,`${root}/signatures/RUN_COMPLETION/${item.runId}/${a.id}`)
  if(!signed.signature)await confirmSimulatedSignature(page,'运行完成确认',env.R03_MEMBER_USER!,env.R03_MEMBER_PASSWORD!)
  item.stages.push('executed-and-signed');item.stages=[...new Set(item.stages)];save()
 })}
 await stage('偏差：真实失败来源、旧周期 PASS、重开和新周期关闭签署',async()=>{
  for(const identity of ['OQD-12','OQD-13']){
   const item=state.cases[identity],source=await get(page,`${root}/runs/${item.runId}/attempts/${item.originalAttemptId}`),title=`SYNTHETIC ${identity} · 预定异常偏差`
   let d=item.defectId?await get(page,`${root}/defects/${item.defectId}`):(await pages(page,root+'/defects')).find(d=>d.currentRevision.title===title)
   if(!d)d=await write(page,root+'/defects/from-step',{title,description:'合成演示：原失败保持，复测另记，未连接 SAP。',severity:'HIGH',priority:'HIGH',businessFields:{occurrence_department:{value:'合成质量部'},occurrence_location:{value:'隔离 Test365Alm 演示环境（未连接 SAP）'},reviewer:{value:aid}},runId:item.runId,attemptId:item.originalAttemptId,stepKey:source.steps[1].stepKey})
   item.defectId=d.id;save()
   let verified=await proof(d.id)
   const recovered=checkDefectCompletion(verified,identity==='OQD-12'?'CLOSED':'REOPENED',item.defectCompletion)
   if(recovered){item.defectCompletion=recovered;save()}
   if(d.currentRevision.status==='OPEN')d=await write(page,`${root}/defects/${d.id}/transitions`,{status:'RESOLVED',reason:'合成演示首次修复'},'POST',d.rowVersion)
   if(d.currentRevision.status==='RESOLVED'){
    verified=await proof(d.id)
    expect(verified.resolutionId,'Resolution cycle must come from server report and immutable history').toBeTruthy()
    if(item.resolutionId)expect(verified.resolutionId,'演示恢复冲突：原解决周期已改变，不追加流转').toBe(item.resolutionId)
    item.resolutionId=verified.resolutionId!;save()
    let retests=await pages(page,`${root}/defects/${d.id}/retests`)
    let rt=selectCycleRetest(retests,verified.resolutionId!)
    if(!rt){await write(page,`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion);retests=await pages(page,`${root}/defects/${d.id}/retests`);rt=selectCycleRetest(retests,verified.resolutionId!)}
    expect(rt,'Current cycle retest must exist').toBeDefined()
    if(!rt)throw new Error('演示恢复冲突：当前周期复测缺失')
    const path=`${root}/runs/${rt.runId}/attempts/${rt.attemptId}`;let a=await get(page,path)
    if(a.status!=='FINISHED'){
     for(const s of a.steps)if(s.conclusion==='NOT_RUN')a=await write(page,`${path}/steps/${s.stepKey}`,{actualResult:'合成演示复测：修正后的观察符合预期，未操作 SAP。',conclusion:'PASS'},'PUT',s.rowVersion)
     await write(page,path+'/finish',{},'POST',a.rowVersion)
    }
    a=await get(page,path)
    expect(a.status,'演示恢复冲突：当前周期复测尚未完成，不追加流转').toBe('FINISHED')
    expect(a.conclusion,'演示恢复冲突：当前周期不是 PASS，不借用旧周期结果').toBe('PASS')
    d=await get(page,`${root}/defects/${d.id}`)
    if(identity==='OQD-13'){d=await write(page,`${root}/defects/${d.id}/transitions`,{status:'REOPENED',reason:'合成新观察：旧 PASS 不证明当前问题通过'},'POST',d.rowVersion)}
    else d=await write(page,`${root}/defects/${d.id}/transitions`,{status:'CLOSED',reason:'合成复测 PASS 后关闭'},'POST',d.rowVersion)
   }
   if(identity==='OQD-12'){
    expect(d.currentRevision.status).toBe('CLOSED');const s=await get(page,`${root}/signatures/DEVIATION_CLOSURE/${d.id}/${d.currentRevision.id}`)
    if(!s.signature){const bugs=page.getByRole('region',{name:'缺陷工作台'});await bugs.getByRole('button',{name:'刷新缺陷',exact:true}).click();await bugs.getByRole('button',{name:new RegExp(title)}).click();await confirmSimulatedSignature(page,'偏差关闭确认',env.R03_MEMBER_USER!,env.R03_MEMBER_PASSWORD!)}
   }else expect(d.currentRevision.status).toBe('REOPENED')
   item.defectCompletion=checkDefectCompletion(await proof(d.id),identity==='OQD-12'?'CLOSED':'REOPENED',item.defectCompletion,true);save()
  }
 })
 await stage('证据新版本、真实报告入口、VIEWER 只读和完整证据索引',async()=>{
  if(state.asset&&!state.asset.newVersionId){
   const revisions=await pages(page,`${root}/assets/${state.asset.assetId}/revisions`)
   let newer=revisions.find(r=>r.id!==state.asset!.revisionId)
   if(!newer){const bytes=Buffer.from('SYNTHETIC 新版证据；不覆盖原执行固定的第一版。');const pending=(await pages(page,root+'/uploads')).filter(u=>u.filename==='SYNTHETIC-OQD-01-证据-v2.txt'&&u.assetId===state.asset!.assetId);expect(pending.length).toBeLessThan(2);let u=pending[0]??await write(page,root+'/uploads',{filename:'SYNTHETIC-OQD-01-证据-v2.txt',size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),contentType:'text/plain',assetId:state.asset.assetId});if(u.state==='CREATED')await write(page,`${root}/uploads/${u.id}/content`,bytes,'PUT',undefined,'text/plain');u=await get(page,`${root}/uploads/${u.id}`);if(u.state==='UPLOADED')u=await write(page,`${root}/uploads/${u.id}/complete`,{});expect(u.state).toBe('PUBLISHED');newer={id:u.revisionId}}
   state.asset.newVersionId=newer.id;save()
  }
  const index=[]
  for(const scenario of definition.cases){
   const item=state.cases[scenario.id],review=await get(viewer,`${root}/tests/${item.testId}/revisions/${item.revisionId}/review`),run=await get(viewer,`${root}/runs/${item.runId}`)
   const original=await get(viewer,`${root}/reports/runs/${item.runId}/attempts/${item.originalAttemptId}`)
   expect(original.facts.attempt.conclusion).toBe(scenario.initialConclusion);expect(original.facts.manifest.source_revision_id).toBe(item.revisionId)
   expect(original.reviews.some((e:{action:string})=>e.action==='APPROVE')).toBe(true)
   expect(original.signatures.filter((s:{kind:string})=>['TEST_APPROVAL','RUN_COMPLETION'].includes(s.kind))).toHaveLength(2)
   if(scenario.id==='OQD-01'){expect(original.attachments).toHaveLength(1);expect(original.attachments[0].asset_revision_id).toBe(state.asset!.revisionId);expect(original.attachments[0].revision_no).toBe(1)}
   if(scenario.id==='OQD-12')expect(original.signatures.some((s:{kind:string})=>s.kind==='DEVIATION_CLOSURE')).toBe(true)
   // Original audit endpoints remain independent from signature_record evidence.
   const caseHistory=await pages(viewer,`${root}/history/test_case/${item.testId}`),runHistory=await pages(viewer,`${root}/history/run/${item.runId}`);expect(caseHistory.some(e=>e.action==='test_case.review.approved')).toBe(true);expect(runHistory.some(e=>e.action==='run.finished')).toBe(true);const revisions=await get(viewer,`${root}/tests/${item.testId}/revisions`),attempts=await pages(viewer,`${root}/runs/${item.runId}/attempts/page`)
   index.push({caseHistory,runHistory,caseId:scenario.id,title:scenario.title,...item,review,revisionCount:revisions.length,attempts,currentAttemptId:run.currentAttempt.id,report:original,signatures:original.signatures,attachments:original.attachments,pdf:scenario.id==='OQD-01'?'ordinary-run.pdf':scenario.id==='OQD-12'?'original-fail.pdf':null})
  }
  const summary=await get(viewer,`${root}/reports/test-sets/${state.setId}`)
  // A retest starts a new latest Attempt: 12 ordinary PASS + two retest PASS = 14.
  expect(summary.items).toHaveLength(14);expect(summary.facts.counts.categories).toMatchObject({PASS:14,FAIL:0,RUNNING:0,PAUSED:0,BLOCKED:0,NOT_RUN:0})
  const defectHistory=await pages(viewer,`${root}/history/defect/${state.cases['OQD-12'].defectId}`);expect(defectHistory.some(e=>e.action==='defect.transitioned')).toBe(true);const defects=await get(viewer,`${root}/reports/defects?testSetId=${state.setId}`)
  expect(defects.items).toHaveLength(2);expect(defects.items.find((d:{id:string})=>d.id===state.cases['OQD-13'].defectId).currentResolutionId).toBeNull()
  expect(defects.retests.some((r:{defect_id:string;conclusion:string})=>r.defect_id===state.cases['OQD-13'].defectId&&r.conclusion==='PASS')).toBe(true)
  const csrf=await get(viewer,'/api/v1/csrf'),denied=await viewer.request.post(root+'/test-sets',{headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID()},data:{name:'viewer must not create',description:''}});expect(denied.status()).toBe(403)
  await viewer.goto('/');await expect(viewer.getByRole('button',{name:'退出登录'})).toBeVisible();await choose(viewer,scope.tenantId,project.id)
  const panel=viewer.locator('section.execution-panel');await panel.getByRole('button',{name:'SYNTHETIC OQ-DEMO-01 v1.1',exact:true}).click();await panel.getByRole('button',{name:/^SYNTHETIC OQ-DEMO-01 v1.1/}).filter({has:viewer.locator('small')}).click()
  await panel.getByRole('button',{name:`查看绑定修订 ${definition.cases[0].title}`,exact:true}).click();await expect(panel.getByRole('region',{name:'实例修订与闭环导航'})).toContainText('修订 2');await expect(panel.getByRole('region',{name:'实例修订与闭环导航'}).getByRole('region',{name:'审计历史',exact:true})).toContainText('拒绝用例修订');await panel.getByRole('button',{name:'关闭实例导航'}).click()
  async function print(name:string){const dialog=viewer.getByRole('dialog',{name:'业务报告'});await expect(dialog).toContainText('数据完整加载');await viewer.evaluate(()=>document.fonts.ready);await dialog.screenshot({path:resolve(output,name+'.png')});await viewer.pdf({path:resolve(output,name+'.pdf'),preferCSSPageSize:true,printBackground:true});await dialog.getByRole('button',{name:'返回业务页面'}).click()}
  for(const [identity,name]of[['OQD-01','ordinary-run'],['OQD-12','original-fail']]){
   const item=state.cases[identity];await panel.getByRole('button',{name:new RegExp(`^运行 ${item.runId} `)}).click();await panel.getByRole('button',{name:/^尝试 1 ·/}).click();await expect(panel.getByRole('region',{name:'审计历史',exact:true})).toContainText('完成运行');await panel.getByRole('button',{name:'查看所选 Attempt 执行报告'}).click();await print(name)
   if(identity==='OQD-12'){await panel.getByRole('button',{name:/^尝试 2 · PASS$/}).click();await panel.getByRole('button',{name:'查看所选 Attempt 执行报告'}).click();await print('retest-pass')}
  }
  await panel.getByRole('button',{name:'测试集汇总报告',exact:true}).click();await print('test-set-summary')
  await viewer.getByRole('region',{name:'缺陷工作台'}).getByRole('button',{name:'偏差清单报告',exact:true}).click();const dialog=viewer.getByRole('dialog',{name:'业务报告'});await expect(dialog).toContainText('历史解决周期的复测记录');await print('deviation-list')
  state.stages=[...new Set([...state.stages,'fourteen-reviewed-signed-executed','closed-and-reopened-defects','reports-viewer-evidence'])];save()
  writeFileSync(resolve(dir,'evidence-index.json'),JSON.stringify({scenario:definition.scenario,version:definition.version,classification:'SYNTHETIC',notice:definition.notice,projectId:state.projectId,setId:state.setId,roles:{author:mid,executor:mid,reviewer:aid,viewer:vid},cases:index,summary,defects,defectHistory,samples:output},null,2))
 })
 if(!persistent)await oqFixRegressions(page,admin,scope,mid,rootDir,get,write,pages,choose)
 })
 }finally{await ac.close();await vc.close()}
})
