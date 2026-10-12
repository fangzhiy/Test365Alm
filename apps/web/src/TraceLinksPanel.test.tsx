import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import TraceLinksPanel from './TraceLinksPanel'
import { coreApi } from './coreApi'
import { testsApi } from './tests'
vi.mock('./coreApi', () => ({ coreApi: { links: vi.fn(), coverage: vi.fn(), createLink: vi.fn(), invalidate: vi.fn() } }))
vi.mock('./tests', () => ({ testsApi: { list: vi.fn() } }))
const access = { tenantId:'t', projectId:'p', principalId:'m', roles:['PROJECT_MEMBER'] as const, permissions:['requirement:read','requirement:update','test:read','test:create'] }
const link = { id:'l', projectId:'p', requirementId:'r', testCaseId:'x', requirementRevisionId:'rr', testRevisionId:'tr', rowVersion:1, state:'ACTIVE' as const }
afterEach(() => { cleanup(); vi.resetAllMocks() })
it('creates typed links and shows linked separately from passed coverage', async () => {
  vi.mocked(coreApi.links).mockResolvedValue({items:[], nextCursor:null}); vi.mocked(coreApi.coverage).mockResolvedValue({requirementId:'r', linkedTests:0, activeTests:0, passedTests:0, rule:'CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE'})
  vi.mocked(testsApi.list).mockResolvedValue({items:[{id:'x',title:'Manual check'}] as never[],nextCursor:null}); vi.mocked(coreApi.createLink).mockResolvedValue(link)
  const user=userEvent.setup(); render(<TraceLinksPanel projectId="p" requirementId="r" access={access} />)
  await screen.findByRole('option',{name:'Manual check'}); await user.selectOptions(screen.getByLabelText('关联测试用例'),'x'); await user.click(screen.getByRole('button',{name:'建立追踪关联'}))
  expect(await screen.findByText(/关联已保存/)).toBeVisible(); expect(coreApi.createLink).toHaveBeenCalledWith('p','r','x',expect.objectContaining({idempotencyKey:expect.any(String)}))
  vi.mocked(coreApi.coverage).mockResolvedValue({requirementId:'r',linkedTests:1,activeTests:0,passedTests:0,rule:'CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE'})
  await user.click(screen.getByRole('button',{name:'刷新覆盖统计'})); expect(await screen.findByText(/PASS 覆盖 0\/1/)).toBeVisible()
})
it('viewer sees links and coverage without write controls',async()=>{
  vi.mocked(coreApi.links).mockResolvedValue({items:[link],nextCursor:null}); vi.mocked(coreApi.coverage).mockResolvedValue({requirementId:'r',linkedTests:1,activeTests:0,passedTests:0,rule:'CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE'})
  vi.mocked(testsApi.list).mockResolvedValue({items:[],nextCursor:null})
  render(<TraceLinksPanel projectId="p" requirementId="r" access={{...access,roles:['PROJECT_VIEWER'],permissions:['requirement:read','test:read']}} />)
  await screen.findByText(/已关联 1/); expect(screen.queryByRole('button',{name:'建立追踪关联'})).not.toBeInTheDocument(); expect(screen.queryByRole('button',{name:'失效关联'})).not.toBeInTheDocument()
})
