import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { isOwnedRun, assertStepEvidenceFacilities, seedProjectAccessScope } from './ownedR03'
import { confirmSimulatedSignature } from './signatureUi'

async function login(page:Page,user:string,password:string){
 await page.goto('/');await page.getByRole('link',{name:'登录 Test365Alm'}).click();await page.locator('#username').fill(user);await page.locator('#password').fill(password);await page.locator('#kc-login').click();await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible();const response=await page.request.get('/api/v1/me');expect(response.status()).toBe(200);return (await response.json()).id as string
}
async function write(page:Page,path:string,data:unknown,method='POST',version?:number,contentType='application/json'){
 const response=await page.request.get('/api/v1/csrf');expect(response.status()).toBe(200);const csrf=await response.json()
 const result=await page.request.fetch(path,{method,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID(),'Content-Type':contentType,...(version?{'If-Match':`"${version}"`}:{})},data,timeout:15000})
 expect(result.status(),`real write ${new URL(result.url()).pathname}`).toBeLessThan(400);return result.json()
}
async function select(page:Page,tenant:string,project:string){const region=page.getByRole('region',{name:'租户、项目与成员'});await region.getByRole('button',{name:'加载访问范围'}).click();await region.getByRole('combobox').first().selectOption(tenant);const projects=region.locator('.access-selects select').nth(1);await expect(projects.locator(`option[value="${project}"]`)).toHaveCount(1);await projects.selectOption(project)}

test('real Keycloak UI reports preserve historical FAIL after related PASS and print three complete Chinese reports',async({page,browser})=>{
 test.skip(!isOwnedRun(),'Requires explicitly owned authentication database and evidence facilities');test.setTimeout(240000);assertStepEvidenceFacilities()
 const env=process.env,mu=env.R03_MEMBER_USER!,mp=env.R03_MEMBER_PASSWORD!,viewerContext=await browser.newContext(),viewer=await viewerContext.newPage(),adminContext=await browser.newContext(),admin=await adminContext.newPage()
 const output=resolve(process.cwd(),'../../output/pdf'),evidence=resolve(process.cwd(),'../../local-evidence/r03');await mkdir(output,{recursive:true});await mkdir(evidence,{recursive:true})
 const steps:object[]=[];async function stage(name:string,fn:()=>Promise<void>){await test.step(name,async()=>{let passed=false;try{await fn();passed=true}finally{steps.push({name,passed})}})}
 const checkpoint=(name:string)=>{steps.push({name,entered:true})}
 try{
  const aid=await login(admin,env.R03_TEST_USER!,env.R03_TEST_USER_PASSWORD!),mid=await login(page,mu,mp),vid=await login(viewer,env.R03_VIEWER_USER!,env.R03_VIEWER_PASSWORD!),{tenantId,domainId}=seedProjectAccessScope(aid,vid,mid)
  const project=await write(admin,'/api/v1/projects',{tenantId,domainId,code:`F-${randomUUID()}`,name:'合成演示 · 中文报告项目'}),root=`/api/v1/projects/${project.id}`
  for(const [id,role]of[[mid,'PROJECT_MEMBER'],[vid,'PROJECT_VIEWER']])await write(admin,`${root}/members/${id}`,{roles:[role],authorizationVersion:0},'PUT')
  const original=await write(page,`${root}/tests`,{testType:'MANUAL',title:'合成演示 · 设备清洁确认',description:'检查设备清洁、参数及异常处理。该数据仅用于技术验证，不代表真实 OQ 验收。',preconditions:'设备停机并完成安全检查。',businessFields:{test_purpose:{value:'验证长中文、确切历史修订和执行证据。'},reviewer:{value:mid}},steps:[1,2,3].map(n=>({ordinal:n,action:`步骤${n}：检查设备表面、参数和记录。`,expected:'清洁无残留，参数在允许范围内。'}))})
  const set=await write(page,`${root}/test-sets`,{name:'合成演示 · 实例最新执行结果',description:'含不同执行状态及超过默认分页数量的实例。'}),instance=await write(page,`${root}/test-sets/${set.id}/instances`,{testCaseId:original.id,testRevisionId:original.currentRevision.id})
  const run=await write(page,`${root}/runs`,{testInstanceId:instance.id}),old=run.currentAttempt
  let relatedRetestId='',closedDefectId='',closedRevisionId=''
  const content=Buffer.from('合成演示证据第一版\n'),upload=await write(page,`${root}/uploads`,{filename:'清洁检查原始证据.txt',size:content.length,sha256:createHash('sha256').update(content).digest('hex'),contentType:'text/plain'})
  await write(page,`${root}/uploads/${upload.id}/content`,content,'PUT',undefined,'text/plain');const published=await write(page,`${root}/uploads/${upload.id}/complete`,{});await write(page,`${root}/attachments`,{kind:'RUN_STEP',runStepId:old.steps[0].runStepId,assetId:published.assetId,assetRevisionId:published.revisionId})
  await select(page,tenantId,project.id);const execution=page.locator('section.execution-panel'),bugs=page.getByRole('region',{name:'缺陷工作台'})
  await execution.getByRole('button',{name:set.name,exact:false}).click();await execution.getByRole('button',{name:new RegExp(`运行 ${run.run.id}`)}).click()
  const long='实测发现局部清洁残留，需要记录原始观察并复核。特殊字符 <script>不可执行</script> & 符号安全显示。'.repeat(35)
  await stage('完成原始 FAIL 并从真实步骤创建偏差',async()=>{
   for(const n of [1,2,3]){await execution.getByLabel(`步骤 ${n} 实际结果`).fill(`步骤${n}原始观察：${long}`);await execution.getByLabel(`步骤 ${n} 结论`).selectOption(n===1?'FAIL':'PASS');await execution.getByRole('button',{name:'保存步骤结果',exact:true}).nth(n-1).click();await expect(execution).toContainText('步骤结果已保存')}
   await execution.getByRole('button',{name:'完成运行',exact:true}).click();await expect(execution).toContainText('已完成 · FAIL')
   await execution.getByRole('button',{name:'从失败步骤创建缺陷'}).click();const form=execution.getByRole('region',{name:'从失败步骤创建缺陷'});await form.getByLabel('缺陷标题').fill('合成演示 · 清洁残留偏差');await form.getByLabel('缺陷描述').fill('偏差调查：保留原始观察，与后续复测分开。'.repeat(60));await form.getByRole('button',{name:'创建缺陷',exact:true}).click();await expect(form).toContainText('缺陷已创建')
  })
  await stage('模拟签署指定 FAIL Attempt，真实关联复测完成 PASS',async()=>{
   await confirmSimulatedSignature(page,'运行完成确认',mu,mp)
   await bugs.getByRole('button',{name:'刷新缺陷'}).click();await bugs.getByRole('button',{name:/BUG-.*清洁残留偏差/}).click();const detail=bugs.getByRole('region',{name:'缺陷详情'})
   await detail.getByLabel('修复/流转说明').fill('重新清洁后复测');await detail.getByRole('button',{name:'转为 RESOLVED'}).click();await expect(detail).toContainText('RESOLVED');await detail.getByRole('button',{name:'开始复测'}).click();const retest=detail.getByRole('region',{name:'缺陷关联尝试'})
   for(const n of [1,2,3]){await retest.getByLabel(`复测步骤 ${n} 实际结果`).fill('重新清洁后符合预期，复测 PASS。');await retest.getByLabel(`复测步骤 ${n} 结论`).selectOption('PASS');await retest.getByRole('button',{name:`保存复测步骤 ${n}`}).click();await expect(retest).toContainText('复测步骤已保存')}
   await retest.getByRole('button',{name:'完成复测'}).click();await expect(retest).toContainText('FINISHED · PASS')
   await detail.getByRole('button',{name:'刷新缺陷详情'}).click();await expect(detail).toContainText('可用相关 PASS')
   await detail.getByLabel('修复/流转说明').fill('相关复测通过，关闭并模拟签署')
   await detail.getByRole('button',{name:'转为 CLOSED'}).click();await expect(detail).toContainText('CLOSED')
   await confirmSimulatedSignature(page,'偏差关闭确认',mu,mp)
   const list=await (await page.request.get(`${root}/defects`)).json(),d=list.items.find((x:{currentRevision:{title:string}})=>x.currentRevision.title==='合成演示 · 清洁残留偏差')
   closedDefectId=d.id;closedRevisionId=d.currentRevision.id
   const rt=await (await page.request.get(`${root}/defects/${d.id}/retests`)).json();relatedRetestId=rt.items[0].attemptId
   const signed=await (await page.request.get(`${root}/signatures/DEVIATION_CLOSURE/${d.id}/${d.currentRevision.id}`)).json()
   for(const id of [old.id,relatedRetestId]){
    const dto=await (await page.request.get(`${root}/reports/runs/${run.run.id}/attempts/${id}`)).json()
    expect(dto.signatures.filter((s:{kind:string})=>s.kind==='DEVIATION_CLOSURE')).toEqual([signed.signature])
   }
  })
  await stage('重开偏差保留旧 PASS 但不视作有效周期',async()=>{
   let d=await write(page,`${root}/defects/from-step`,{title:'合成演示 · 重开后历史复测',description:'旧周期曾通过，但新问题已重开，不能据此认定当前通过。',severity:'HIGH',priority:'HIGH',runId:run.run.id,attemptId:old.id,stepKey:old.steps[0].stepKey})
   d=await write(page,`${root}/defects/${d.id}/transitions`,{status:'RESOLVED',reason:'首次修复'},'POST',d.rowVersion)
   const rt=await write(page,`${root}/defects/${d.id}/retests`,{},'POST',d.rowVersion)
   let a=(await (await page.request.get(`${root}/runs/${rt.runId}/attempts/${rt.attemptId}`)).json())
   for(const s of a.steps)a=await write(page,`${root}/runs/${rt.runId}/attempts/${rt.attemptId}/steps/${s.stepKey}`,{actualResult:'旧周期复测 PASS，后来发现新问题。',conclusion:'PASS'},'PUT',s.rowVersion)
   await write(page,`${root}/runs/${rt.runId}/attempts/${rt.attemptId}/finish`,{},'POST',a.rowVersion)
   await write(page,`${root}/defects/${d.id}/transitions`,{status:'REOPENED',reason:'新观察，需要重新调查'},'POST',d.rowVersion)
   const dto=await (await page.request.get(`${root}/reports/defects?status=REOPENED`)).json();expect(dto.items[0].currentResolutionId).toBeNull();expect(dto.retests[0].conclusion).toBe('PASS')
  })
  // Real authenticated fixture writes create enough instances to prove complete printing.
  let current=original
  for(let n=1;n<32;n++){
   current=await write(page,`${root}/tests/${original.id}/revisions`,{title:`合成演示实例 ${n}`,description:'后续修订不覆盖原执行',preconditions:'',steps:original.currentRevision.steps.map((s:{stepKey:string;ordinal:number;action:string;expected:string})=>({stepKey:s.stepKey,ordinal:s.ordinal,action:s.action,expected:s.expected}))},'POST',current.rowVersion)
   const i=await write(page,`${root}/test-sets/${set.id}/instances`,{testCaseId:original.id,testRevisionId:current.currentRevision.id})
   if(n<6){const r=await write(page,`${root}/runs`,{testInstanceId:i.id});let a=r.currentAttempt
    if(n===2)await write(page,`${root}/runs/${r.run.id}/attempts/${a.id}/pause`,{},'POST',a.rowVersion)
    if(n>=3){for(const s of a.steps)a=await write(page,`${root}/runs/${r.run.id}/attempts/${a.id}/steps/${s.stepKey}`,{actualResult:'合成状态验证',conclusion:['PASS','FAIL','BLOCKED'][n-3]},'PUT',s.rowVersion);await write(page,`${root}/runs/${r.run.id}/attempts/${a.id}/finish`,{},'POST',a.rowVersion)}
   }
  }
  const v2=Buffer.from('证据新版不应覆盖旧报告'),u2=await write(page,`${root}/uploads`,{filename:'清洁检查新版证据.txt',size:v2.length,sha256:createHash('sha256').update(v2).digest('hex'),contentType:'text/plain',assetId:published.assetId});await write(page,`${root}/uploads/${u2.id}/content`,v2,'PUT',undefined,'text/plain');await write(page,`${root}/uploads/${u2.id}/complete`,{})
  await stage('旧 Attempt 报告、固定附件版本及中文纵向 PDF',async()=>{
   checkpoint('即将刷新运行详情')
   steps.push({name:'运行详情入口状态',refreshEnabled:await execution.getByRole('button',{name:'刷新运行详情'}).isEnabled()})
   await execution.getByRole('button',{name:'刷新运行详情'}).click();checkpoint('已点击刷新，即将选择历史失败');await execution.getByRole('button',{name:/^尝试 1 · FAIL$/}).click();checkpoint('已选择历史失败，即将打开报告');await execution.getByRole('button',{name:'查看所选 Attempt 执行报告'}).click();checkpoint('报告入口已点击');const dialog=page.getByRole('dialog',{name:'业务报告'});await expect(dialog.getByRole('heading',{name:'单次运行执行记录报告',exact:true})).toBeVisible();await expect(dialog).toContainText('设备清洁确认');await expect(dialog).toContainText('清洁检查原始证据.txt');await expect(dialog).not.toContainText('清洁检查新版证据.txt');await expect(dialog).toContainText('源自本次执行');await expect(dialog).toContainText('RUN_COMPLETION');expect(await dialog.locator('script').count()).toBe(0)
   const loaded=await page.request.get(`${root}/reports/runs/${run.run.id}/attempts/${old.id}`);expect(loaded.status()).toBe(200);const dto=await loaded.json();expect(dto.facts.attempt.conclusion).toBe('FAIL');expect(dto.attachments[0].revision_no).toBe(1);expect(dto.facts.businessFields.test_purpose.value).toContain('历史修订')
   await expect(dialog).toContainText('偏差关闭确认');await expect(dialog).toContainText('历史解决周期的复测记录；不证明当前周期通过')
   checkpoint('旧报告内容已核对，即将等待字体与打印');await page.evaluate(()=>document.fonts.ready);await dialog.screenshot({path:resolve(output,'run-report.png')});await page.pdf({path:resolve(output,'run-report.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:true,headerTemplate:'<span></span>',footerTemplate:'<div style="font-size:8px;text-align:center;width:100%">Test365Alm · <span class="pageNumber"></span> / <span class="totalPages"></span></div>'});await dialog.getByRole('button',{name:'返回业务页面'}).click()
   checkpoint('失败报告 PDF 已完成，即将查看未签署复测');await execution.getByRole('button',{name:/^尝试 2 · PASS$/}).click();await execution.getByRole('button',{name:'查看所选 Attempt 执行报告'}).click()
   await expect(dialog).toContainText('本次 Attempt 未签署');await expect(dialog).toContainText('偏差关闭确认')
   const b=await (await page.request.get(`${root}/reports/runs/${run.run.id}/attempts/${relatedRetestId}`)).json()
   expect(b.signatures.some((s:{kind:string;objectId:string;snapshotId:string})=>s.kind==='DEVIATION_CLOSURE'&&s.objectId===closedDefectId&&s.snapshotId===closedRevisionId)).toBe(true)
   await dialog.getByRole('button',{name:'返回业务页面'}).click()
  })
  await stage('实例互斥分类、后端筛选及完整横向汇总 PDF',async()=>{
   await execution.getByRole('button',{name:'测试集汇总报告',exact:true}).click();const dialog=page.getByRole('dialog',{name:'业务报告'});await expect(dialog.getByRole('heading',{name:'测试集汇总报告',exact:true})).toBeVisible();await expect(dialog.getByRole('row')).toHaveCount(33)
   const response=await page.request.get(`${root}/reports/test-sets/${set.id}`),dto=await response.json();expect(Object.values(dto.facts.counts.categories).reduce((a:number,b)=>a+Number(b),0)).toBe(32);expect(dto.facts.counts.categories).toMatchObject({PASS:2,FAIL:1,BLOCKED:1,RUNNING:1,PAUSED:1,NOT_RUN:26})
   await dialog.getByLabel('报告状态').selectOption('PASS');await dialog.getByRole('button',{name:'应用报告筛选'}).click();await expect(dialog.getByRole('row')).toHaveCount(3);await dialog.getByLabel('报告状态').selectOption('');await dialog.getByRole('button',{name:'应用报告筛选'}).click();await expect(dialog.getByRole('row')).toHaveCount(33)
   await dialog.screenshot({path:resolve(output,'test-set-report.png')});await page.pdf({path:resolve(output,'test-set-report.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:true,headerTemplate:'<span></span>',footerTemplate:'<div style="font-size:8px;text-align:center;width:100%">Test365Alm · <span class="pageNumber"></span> / <span class="totalPages"></span></div>'});await dialog.getByRole('button',{name:'返回业务页面'}).click()
   // Ordinary business length remains usable, independently of long-text stress evidence.
   await execution.getByRole('button',{name:'测试集汇总报告',exact:true}).click();await expect(dialog.getByRole('row')).toHaveCount(33)
   await dialog.getByRole('row').filter({hasText:'合成演示实例 3'}).filter({has:dialog.getByRole('button',{name:'查看运行报告'})}).getByRole('button',{name:'查看运行报告'}).click()
   await expect(dialog.getByRole('heading',{name:'单次运行执行记录报告',exact:true})).toBeVisible()
   await page.pdf({path:resolve(output,'run-report-ordinary.pdf'),preferCSSPageSize:true,printBackground:true});await dialog.screenshot({path:resolve(output,'run-report-ordinary.png')});await dialog.getByRole('button',{name:'返回业务页面'}).click()
  })
  await stage('VIEWER 偏差清单入口、真实来源及复测周期和中文 PDF',async()=>{
   await select(viewer,tenantId,project.id);await viewer.getByRole('button',{name:'偏差清单报告',exact:true}).click();const dialog=viewer.getByRole('dialog',{name:'业务报告'});await expect(dialog.getByRole('heading',{name:'偏差清单报告',exact:true})).toBeVisible();await expect(dialog).toContainText('原始来源及关联');await expect(dialog).toContainText('FAIL');await expect(dialog).toContainText('PASS');await dialog.getByLabel('报告标题关键词').fill('不存在的标题');await dialog.getByRole('button',{name:'应用报告筛选'}).click();await expect(dialog).toContainText('当前范围无偏差');await dialog.getByLabel('报告标题关键词').fill('');await dialog.getByRole('button',{name:'应用报告筛选'}).click();await expect(dialog.getByRole('heading',{name:/BUG-.*清洁残留偏差/})).toBeVisible();await dialog.screenshot({path:resolve(output,'deviation-report.png')});await viewer.pdf({path:resolve(output,'deviation-report.pdf'),preferCSSPageSize:true,printBackground:true,displayHeaderFooter:true,headerTemplate:'<span></span>',footerTemplate:'<div style="font-size:8px;text-align:center;width:100%">Test365Alm · <span class="pageNumber"></span> / <span class="totalPages"></span></div>'});await dialog.getByRole('button',{name:'返回业务页面'}).click()
   await viewer.getByRole('button',{name:'偏差清单报告',exact:true}).click();await expect(dialog).toContainText('数据完整加载')
   await dialog.getByLabel('测试集范围').selectOption(set.id);await dialog.getByRole('button',{name:'应用报告筛选'}).click();await expect(dialog).toContainText(set.name)
   await expect(dialog).toContainText('历史解决周期的复测记录；不证明当前周期通过')
   await dialog.getByRole('button',{name:'返回业务页面'}).click()
   const viewerBugs=viewer.getByRole('region',{name:'缺陷工作台'});await viewerBugs.getByRole('button',{name:/BUG-.*清洁残留偏差/}).click();await expect(viewerBugs.getByRole('region',{name:'缺陷详情'})).toBeVisible()
   await viewer.getByRole('button',{name:'偏差清单报告',exact:true}).click();await expect(dialog).toContainText('数据完整加载')
   await write(admin,`${root}/members/${vid}`,{authorizationVersion:1},'DELETE');await dialog.getByRole('button',{name:'应用报告筛选'}).click()
   await expect(dialog).toHaveCount(0);await expect(viewerBugs).toContainText('旧内容已清理');await expect(viewerBugs.getByRole('region',{name:'缺陷详情'})).toHaveCount(0);await expect(viewerBugs.getByRole('button',{name:/BUG-/})).toHaveCount(0)
   const denied=await viewer.request.get(`${root}/reports/defects`);expect([403,404]).toContain(denied.status())
   await execution.getByRole('button',{name:'测试集汇总报告',exact:true}).click();const memberDialog=page.getByRole('dialog',{name:'业务报告'});await expect(memberDialog).toContainText('数据完整加载')
   await write(admin,`${root}/members/${mid}`,{authorizationVersion:1},'DELETE');await memberDialog.getByRole('button',{name:'应用报告筛选'}).click()
   await expect(memberDialog).toHaveCount(0);await expect(execution).toContainText('当前会话没有读取测试集和运行记录的权限。');await expect(execution.locator('.execution-set')).toHaveCount(0);await expect(execution.locator('.execution-detail')).toHaveCount(0)
  })
 }finally{await writeFile(resolve(evidence,'reports-context.json'),JSON.stringify({schemaVersion:1,steps},null,2));await viewerContext.close();await adminContext.close()}
})
