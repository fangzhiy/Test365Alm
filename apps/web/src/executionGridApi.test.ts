import { afterEach, expect, it, vi } from 'vitest'
import { executionApi } from './testExecution'

const instance = { id:'i',projectId:'p',testSetId:'s',testCaseId:'c',testRevisionId:'r',title:'Saved case',revisionNumber:2 }
const row = { instance,assigneeId:'member',assigneeName:'Operator',assignmentVersion:3,runId:'run',attemptId:'attempt',status:'PAUSED',conclusion:null,executionDate:'2026-01-01T10:00:00Z' }
const response = (body:unknown) => new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}})
afterEach(()=>vi.unstubAllGlobals())

it('execution grid preserves server assignment date filters and opaque cursor',async()=>{
 const fetch=vi.fn().mockResolvedValue(response({items:[row],nextCursor:'opaque/+'}));vi.stubGlobal('fetch',fetch)
 const page=await executionApi.executionGrid('p','s','opaque?',25,{status:'PAUSED',assigneeId:'member'})
 expect(page.items[0]).toMatchObject({id:'i',assignmentVersion:3,status:'PAUSED',executionDate:row.executionDate,runId:'run'})
 expect(page.nextCursor).toBe('opaque/+');const url=new URL(fetch.mock.calls[0][0],'http://local')
 expect(url.searchParams.get('cursor')).toBe('opaque?');expect(url.searchParams.get('assigneeId')).toBe('member');expect(url.searchParams.get('status')).toBe('PAUSED')
})
it('execution grid rejects cross project or malformed execution dates and versions',async()=>{
 const fetch=vi.fn();vi.stubGlobal('fetch',fetch)
 for(const bad of [{...row,instance:{...instance,projectId:'other'}},{...row,executionDate:'invented'},{...row,assignmentVersion:0},{...row,runId:null}]){
  fetch.mockResolvedValue(response({items:[bad],nextCursor:null}))
  await expect(executionApi.executionGrid('p','s')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 }
})
it('assignment sends explicit nullable target strong version and durable original key',async()=>{
 const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/csrf')?response({headerName:'X-CSRF-TOKEN',token:'synthetic'}):response({instanceId:'i',assigneeId:null,assigneeName:null,assignmentVersion:4}))
 vi.stubGlobal('fetch',fetch);await executionApi.assignInstance('p','i',null,3,{idempotencyKey:'original-assignment'})
 const call=fetch.mock.calls.find(([input])=>String(input).endsWith('/assignee')) as unknown as [string,RequestInit]
 expect(call[1].method).toBe('PUT');expect(JSON.parse(String(call[1].body))).toEqual({assigneeId:null,expectedVersion:3})
 expect(new Headers(call[1].headers).get('If-Match')).toBe('"3"');expect(new Headers(call[1].headers).get('Idempotency-Key')).toBe('original-assignment')
})
it('manual runner retains independent actual conclusion and both server clocks',async()=>{
 const step={stepKey:'step',runStepId:'persisted-step',ordinal:1,action:'open',expected:'visible',actualResult:'text before conclusion',conclusion:'NOT_RUN',rowVersion:2,executedAt:null,updatedAt:'2026-01-01T10:00:00Z'}
 const body={id:'a',attemptNo:1,status:'RUNNING',rowVersion:2,steps:[step]}
 const fetch=vi.fn().mockResolvedValue(response(body));vi.stubGlobal('fetch',fetch)
 expect((await executionApi.getAttempt('p','run','a')).steps[0]).toMatchObject({actual:'text before conclusion',outcome:'NOT_RUN',executedAt:null,updatedAt:step.updatedAt,runStepId:'persisted-step'})
 fetch.mockResolvedValue(response({...body,steps:[{...step,executedAt:'fake'}]}))
 await expect(executionApi.getAttempt('p','run','a')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
