import { afterEach, expect, it, vi } from 'vitest'
import { defectsApi } from './defectsApi'
afterEach(()=>vi.unstubAllGlobals())
it('rejects invalid defect bodies and preserves real HTTP business errors',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response('{}',{status:200})).mockResolvedValueOnce(new Response(JSON.stringify({code:'FORBIDDEN',message:'Denied'}),{status:403})))
 await expect(defectsApi.get('p','d')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 await expect(defectsApi.get('p','d')).rejects.toMatchObject({code:'FORBIDDEN',status:403})
})
it('uses a bounded page cursor and rejects false verification',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({items:[],nextCursor:'next'}))).mockResolvedValueOnce(new Response(JSON.stringify({eligible:'true',runId:null,attemptId:null,reason:'bad'})))
 vi.stubGlobal('fetch',fetch);expect((await defectsApi.list('p','query','cursor')).nextCursor).toBe('next')
 expect(String(fetch.mock.calls[0][0])).toContain('cursor=cursor')
 await expect(defectsApi.verification('p','d')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('shared HTTP serializer excludes readonly revision metadata from create and edit',async()=>{
 const c={title:'Bug',description:'fixed',severity:'HIGH',priority:'MEDIUM'},revision={...c,id:'rev',defectId:'bug',revisionNo:1,status:'OPEN' as const,reason:'',createdBy:'member',createdAt:'date'},d={id:'bug',projectId:'p',displayNumber:1,rowVersion:1,createdBy:'member',createdAt:'date',currentRevision:revision,links:[]}
 const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/csrf')?new Response(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'synthetic-test-token'})):new Response(JSON.stringify(d),{headers:{ETag:'"1"'}}));vi.stubGlobal('fetch',fetch)
 await defectsApi.create('p',revision,null,{idempotencyKey:'original-key'});await defectsApi.edit('p',d,revision,{idempotencyKey:'edit-key'})
 const calls=fetch.mock.calls as unknown as [RequestInfo,RequestInit][];const writes=calls.filter(([path])=>String(path).includes('/defects'));expect(writes).toHaveLength(2);for(const [,init]of writes)expect(JSON.parse(String(init.body))).toEqual(c);expect(new Headers(writes[1][1].headers).get('If-Match')).toBe('"1"')
})
