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
