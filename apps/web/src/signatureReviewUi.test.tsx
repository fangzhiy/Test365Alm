import '@testing-library/jest-dom/vitest'
import { useState } from 'react'
import { act,cleanup,fireEvent,render,screen,within } from '@testing-library/react'
import { afterEach,expect,it,vi } from 'vitest'
import type { Locator } from '@playwright/test'
import { approveSignatureRevision } from '../e2e/signatureReviewUi'
import ReviewPanel from './ReviewPanel'
import { reviewApi } from './reviewApi'
import type { ReviewView } from './reviewApi'
vi.mock('./reviewApi',()=>({reviewApi:{get:vi.fn(),act:vi.fn()}}))
vi.mock('./ReviewerPicker',()=>({default:()=> <span>fixture reviewer</span>}))
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('uses the actual review controls and exact successive versions when preparing the E signed revision',async()=>{
 let current:ReviewView={testCaseId:'test',revisionId:'revision',revisionNo:1,currentRevisionId:'revision',rowVersion:1,contentDigest:'a'.repeat(64),reviewerId:'actor',state:'DRAFT',canSubmit:true,canDecide:false,latest:null,items:[],nextCursor:null}
 vi.mocked(reviewApi.get).mockImplementation(async()=>current)
 vi.mocked(reviewApi.act).mockImplementation(async(_project,_test,_revision,command)=>{
  const state=command.action==='SUBMIT'?'SUBMITTED':'APPROVED'
  const event={id:`event-${state}`,revisionId:'revision',revisionNo:1,rowVersion:current.rowVersion+1,action:command.action,state,reviewerId:'actor',actorId:'actor',contentDigest:current.contentDigest,reason:'',occurredAt:'2026-11-11T00:00:00Z'} as const
  current={...current,rowVersion:event.rowVersion,state,canSubmit:false,canDecide:state==='SUBMITTED',latest:event,items:[event,...current.items]}
  return current
 })
 function Parent(){
  const [version,setVersion]=useState(1)
  return <ReviewPanel projectId="project" testCaseId="test" revisionId="revision" rowVersion={version} contextKey="same-identity" onChanged={view=>setVersion(view.rowVersion)}/>
 }
 render(<Parent/>);await screen.findByRole('button',{name:'提交复核'})
 const region=screen.getByRole('region',{name:'用例复核'})
 const domReview={getByRole:(role:'button',options:{name:string;exact:true})=>({click:async()=>{
  const button=within(region).getByRole(role,{name:options.name})
  expect(button).toBeEnabled()
  await act(async()=>{fireEvent.click(button)})
 }})} as unknown as Pick<Locator,'getByRole'>
 await approveSignatureRevision(domReview)
 expect(region).toHaveTextContent('已批准')
 expect(vi.mocked(reviewApi.act).mock.calls.map(call=>call.slice(0,5))).toEqual([
  ['project','test','revision',{action:'SUBMIT',reason:''},'"1"'],
  ['project','test','revision',{action:'APPROVE',reason:''},'"2"'],
 ])
 expect(current.currentRevisionId).toBe('revision')
 expect(within(region).queryByRole('button',{name:'批准本修订'})).not.toBeInTheDocument()
})
