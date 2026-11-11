import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReportLauncher, { ReportBody } from './Reports'
import { parseReport } from './reportsApi'
import type { Report } from './reportsApi'
const target={kind:'RUN' as const,runId:'run-old',attemptId:'attempt-fail'}
const report=():Report=>({kind:'RUN',version:'F-REPORT-1',generatedAt:'2026-11-11T00:00:00Z',timeZone:'UTC',projectId:'p',projectName:'中文项目',filters:{runId:'run-old',attemptId:'attempt-fail'},facts:{run:{id:'run-old'},attempt:{id:'attempt-fail',status:'FINISHED',conclusion:'FAIL'},manifest:{title:'旧用例',source_revision_no:1},businessFields:{test_purpose:{value:'原目的'}}},items:[],steps:[{id:'step',ordinal:1,action:'操作',expected:'预期',actual_result:'长中文 <script>alert(1)</script>',conclusion:'FAIL',executed_at:null,updated_at:'2026-11-11T00:00:00Z'}],attachments:[],reviews:[],signatures:[],links:[],retests:[]})
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks()})
describe('report history and complete read contract',()=>{
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
