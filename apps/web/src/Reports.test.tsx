import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReportLauncher, { ReportBody } from './Reports'
import { parseReport } from './reportsApi'
import type { Report } from './reportsApi'
const target={kind:'RUN' as const,runId:'run-old',attemptId:'attempt-fail'}
const report=():Report=>({kind:'RUN',version:'F-REPORT-1',generatedAt:'2026-11-11T00:00:00Z',timeZone:'UTC',projectId:'p',projectName:'中文项目',filters:{runId:'run-old',attemptId:'attempt-fail'},facts:{testSetId:'set',testSetName:'原测试集',run:{id:'run-old',manifest_id:'manifest',test_instance_id:'instance'},attempt:{id:'attempt-fail',run_id:'run-old',project_id:'p',status:'FINISHED',conclusion:'FAIL',started_at:null,finished_at:null},manifest:{id:'manifest',project_id:'p',test_instance_id:'instance',source_test_case_id:'case',source_revision_id:'revision',title:'旧用例',source_revision_no:1},businessFields:{test_purpose:{value:'原目的'}}},items:[],steps:[{id:'step',project_id:'p',attempt_id:'attempt-fail',step_key:'step-key',ordinal:1,action:'操作',expected:'预期',actual_result:'长中文 <script>alert(1)</script>',conclusion:'FAIL',executed_at:null,updated_at:'2026-11-11T00:00:00Z'}],attachments:[],reviews:[],signatures:[],links:[],retests:[]})
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks()})
describe('report history and complete read contract',()=>{
 it('focuses the report, makes the application inert, and restores focus on Escape',()=>{
  vi.stubGlobal('fetch',vi.fn(()=>new Promise(()=>{})))
  const root=document.createElement('div');root.id='root';document.body.append(root)
  render(<ReportLauncher projectId="p" target={target} label="报告"/>,{container:root});const opener=screen.getByText('报告');opener.focus();fireEvent.click(opener)
  expect(root.inert).toBe(true);expect(document.activeElement).toBe(screen.getByText('返回业务页面'));fireEvent.keyDown(screen.getByRole('dialog'),{key:'Escape'});expect(root.inert).toBe(false);expect(document.activeElement).toBe(opener);expect(screen.queryByRole('dialog')).toBeNull()
 })
 it('shows the Attempt unsigned when only its original test revision has approval signature',()=>{
  const r=report();r.signatures=[{kind:'TEST_APPROVAL',objectId:'case',snapshotId:'revision',displayName:'原签署人',contentSnapshot:{original:true}}]
  render(<ReportBody report={r}/>);expect(screen.getByText('本次 Attempt 未签署')).toBeTruthy();expect(screen.queryByText('本次 Attempt 已模拟签署')).toBeNull()
 })
 it('rejects a different project attempt unfinished or partial envelope',()=>{
  const r=report();expect(parseReport(r,'p',target)).toBe(r)
  for(const bad of [{...r,projectId:'other'},{...r,steps:null},{...r,facts:{...r.facts,attempt:{id:'current',status:'FINISHED'}}},{...r,facts:{...r.facts,attempt:{id:'attempt-fail',status:'RUNNING'}}}])expect(()=>parseReport(bad,'p',target)).toThrow()
 })
 it('shows original FAIL actual separate from expected and unknown execution time safely',()=>{
  const {container}=render(<ReportBody report={report()}/>);expect(screen.getByText('长中文 <script>alert(1)</script>')).toBeTruthy();expect(container.querySelector('script')).toBeNull();expect(screen.getByText('实际执行时间').nextSibling?.textContent).toBe('未记录 / 未知');expect(screen.getByText('未签署')).toBeTruthy()
 })
 it('blocks printing during loading and after failure rather than preserving an old report',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'REPORT_LIMIT_EXCEEDED',message:'请缩小范围'}),{status:422})))
  render(<ReportLauncher projectId="p" target={target} label="报告"/>);fireEvent.click(screen.getByText('报告'));expect((screen.getByText('打印 / 另存为 PDF') as HTMLButtonElement).disabled).toBe(true);await screen.findByRole('alert');expect(screen.queryByText('旧用例')).toBeNull();expect((screen.getByText('打印 / 另存为 PDF') as HTMLButtonElement).disabled).toBe(true)
 })
 it('discards late previous scope responses after unmount and sends exact historic URL',async()=>{
  let resolve!:(r:Response)=>void;const fetch=vi.fn<(input:RequestInfo|URL,init?:RequestInit)=>Promise<Response>>(()=>new Promise<Response>(r=>{resolve=r}));vi.stubGlobal('fetch',fetch)
  const view=render(<ReportLauncher projectId="p" target={target} label="报告"/>);fireEvent.click(screen.getByText('报告'));await waitFor(()=>expect(fetch).toHaveBeenCalled());expect(String(fetch.mock.calls[0]?.[0])).toContain('/runs/run-old/attempts/attempt-fail');view.unmount();resolve(new Response(JSON.stringify(report())));await Promise.resolve();expect(screen.queryByRole('dialog')).toBeNull()
 })
 it('prints only the loaded DTO without generating signatures or refetching',async()=>{
  const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify(report())));vi.stubGlobal('fetch',fetch);const print=vi.spyOn(window,'print').mockImplementation(()=>{})
  render(<ReportLauncher projectId="p" target={target} label="报告"/>);fireEvent.click(screen.getByText('报告'));await screen.findByText('旧用例 · 修订 1');fireEvent.click(screen.getByText('打印 / 另存为 PDF'));expect(print).toHaveBeenCalledOnce();expect(fetch).toHaveBeenCalledOnce()
 })
 it('shows zero denominators and all rows beyond a list default page with exact links',()=>{
  const r=report();r.kind='SET';r.facts={testSet:{id:'set',name:'统计'},totalInstances:55,counts:{matched:55,categories:{NOT_RUN:55},completionRate:0,passRate:null},otherActiveAttempts:2};r.items=Array.from({length:55},(_,i)=>({id:`i${i}`,title:`用例${i}`,revision_no:1,status:'NOT_RUN'}));render(<ReportBody report={r}/>);expect(screen.getAllByRole('row')).toHaveLength(56);expect(screen.getByText('—')).toBeTruthy();expect(screen.getAllByRole('row').at(-1)?.textContent).toContain('用例54')
 })
})

describe('report integrity and current cycle',()=>{
 const setReport=():Report=>({...report(),kind:'SET',filters:{testSetId:'set',status:''},facts:{testSet:{id:'set',project_id:'p',name:'测试集'},totalInstances:1,counts:{matched:1,categories:{NOT_RUN:0,RUNNING:0,PAUSED:0,PASS:0,FAIL:1,BLOCKED:0},completionRate:1,passRate:0},otherActiveAttempts:0,relatedDefects:0},steps:[],items:[{id:'i',title:'失败用例',test_case_id:'case',test_revision_id:'revision',revision_no:1,status:'FAIL',conclusion:'FAIL',run_id:'run',attempt_id:'a',started_at:null,finished_at:null,otherActiveAttempts:0}]})
 it('rejects plausible totals that contradict row classifications and rates',()=>{
  const target={kind:'SET' as const,setId:'set'}
  expect(parseReport(setReport(),'p',target)).toBeTruthy()
  const wrongCounts=setReport();(wrongCounts.facts.counts as Record<string,unknown>).categories={NOT_RUN:0,RUNNING:0,PAUSED:0,PASS:1,FAIL:0,BLOCKED:0}
  const wrongRate=setReport();(wrongRate.facts.counts as Record<string,unknown>).completionRate=1.5
  const duplicate=setReport();duplicate.items.push(duplicate.items[0])
  for(const r of [wrongCounts,wrongRate,duplicate])expect(()=>parseReport(r,'p',target)).toThrow('报告响应不完整')
 })
 it('rejects mismatched sources missing records and out-of-scope evidence but accepts explicit unknown time',()=>{
  const source=report();(source.facts.manifest as Record<string,unknown>).project_id='foreign'
  const missing=report();delete missing.steps[0].executed_at
  const attachment=report();attachment.attachments=[{id:'l',run_step_id:'foreign',asset_id:'asset',asset_revision_id:'rev',revision_no:1,filename:'f',sha256:'hash',size:1}]
  const noSteps=report();noSteps.steps=[]
  for(const r of [source,missing,attachment,noSteps])expect(()=>parseReport(r,'p',target)).toThrow()
  expect(parseReport(report(),'p',target).steps[0].executed_at).toBeNull()
 })
 it('shows reopened old PASS as history and differentiates the newly resolved cycle',()=>{
  const r=report();r.kind='DEFECTS';r.facts={matched:1};r.items=[{id:'d',display_number:1,resolved_revision_id:'old',currentResolutionId:null,lastResolvedRevisionNo:2,revision:{title:'重开偏差',revision_no:3,status:'REOPENED'}}]
  r.retests=[{id:'rt',defect_id:'d',resolved_revision_id:'old',conclusion:'PASS'}]
  const view=render(<ReportBody report={r}/>);expect(screen.getByText('历史解决周期的复测记录；不证明当前周期通过')).toBeTruthy();expect(screen.getByText('已重开 (REOPENED)')).toBeTruthy()
  r.items[0].currentResolutionId='new';r.retests.push({id:'new-rt',defect_id:'d',resolved_revision_id:'new',conclusion:'PASS'})
  view.rerender(<ReportBody report={r}/>);expect(screen.getByText('历史解决周期的复测记录；不证明当前周期通过')).toBeTruthy();expect(screen.getByText('当前有效解决周期的复测记录')).toBeTruthy()
 })
 it('notifies scope loss only for access errors and discards a late old project response',async()=>{
  const denied=vi.fn();let late!:(r:Response)=>void
  vi.stubGlobal('fetch',vi.fn().mockImplementationOnce(()=>new Promise<Response>(r=>{late=r})).mockResolvedValue(new Response(JSON.stringify({code:'FORBIDDEN',message:'denied'}),{status:403})))
  const view=render(<ReportLauncher projectId="p" target={target} label="报告" onDenied={denied}/>);fireEvent.click(screen.getByText('报告'))
  view.rerender(<ReportLauncher projectId="new" target={target} label="报告" onDenied={denied}/>);await screen.findByRole('alert');expect(denied).toHaveBeenCalledOnce();late(new Response(JSON.stringify(report())));await Promise.resolve();expect(screen.queryByText('旧用例 · 修订 1')).toBeNull();expect((screen.getByText('打印 / 另存为 PDF') as HTMLButtonElement).disabled).toBe(true)
 })
})
