import { afterEach, expect, it, vi } from 'vitest'
import { requestJson } from './projectAccess'
import { parseReview, reviewApi } from './reviewApi'
vi.mock('./projectAccess',()=>({requestJson:vi.fn()}))
afterEach(()=>vi.resetAllMocks())
const event={id:'event',revisionId:'revision',revisionNo:1,rowVersion:2,action:'SUBMIT',state:'SUBMITTED',reviewerId:'reviewer',actorId:'actor',contentDigest:'a'.repeat(64),reason:'',occurredAt:'2026-11-11T00:00:00Z'}
const view={testCaseId:'test',revisionId:'revision',revisionNo:1,currentRevisionId:'revision',rowVersion:2,contentDigest:'a'.repeat(64),reviewerId:'reviewer',state:'SUBMITTED',canSubmit:false,canDecide:true,latest:event,items:[event],nextCursor:null}
it('parses bounded revision review responses and rejects malformed or mismatched event bindings',()=>{
 expect(parseReview(view)).toEqual(view)
 for(const body of [null,{}, {...view,state:'signed'}, {...view,rowVersion:0},{...view,canDecide:'true'},
  {...view,contentDigest:'bad'}, {...view,items:[{...event,revisionId:'foreign'}]},
  {...view,latest:{...event,contentDigest:'b'.repeat(64)}}, {...view,items:[event,event]}])expect(()=>parseReview(body)).toThrow()
})
it('uses the exact revision route opaque cursor and durable strong-version command without client identity',async()=>{
 vi.mocked(requestJson).mockResolvedValue(view)
 await reviewApi.get('project /','test','revision','opaque+/=')
 expect(requestJson).toHaveBeenLastCalledWith(expect.stringContaining('/projects/project%20%2F/tests/test/revisions/revision/review?'),undefined,undefined)
 expect(new URL('https://example.test'+vi.mocked(requestJson).mock.calls[0][0]).searchParams.get('cursor')).toBe('opaque+/=')
 await reviewApi.act('p','test','revision',{action:'APPROVE',reason:'checked'},'"2"','original-intent')
 expect(requestJson).toHaveBeenLastCalledWith('/api/v1/projects/p/tests/test/revisions/revision/review',
  {method:'POST',headers:{'Content-Type':'application/json','If-Match':'"2"'},body:'{"action":"APPROVE","reason":"checked"}'},{idempotencyKey:'original-intent'})
})
