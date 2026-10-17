import { afterEach, expect, it, vi } from 'vitest'
import { releasesApi } from './releasesApi'
import type { Release } from './releasesApi'
afterEach(()=>vi.unstubAllGlobals())
const release:Release={id:'r',projectId:'p',displayNumber:1,name:'Release',description:'',startDate:'2026-01-01',endDate:'2026-01-31',status:'PLANNED',rowVersion:1,scopeVersion:0,createdBy:'admin',createdAt:'now',updatedAt:'now'}
it('release HTTP validates dates states fields ETag and preserves business denial',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({...release,startDate:'wrong'}),{headers:{ETag:'"1"'}})).mockResolvedValueOnce(new Response(JSON.stringify(release),{headers:{ETag:'"2"'}})).mockResolvedValueOnce(new Response(JSON.stringify({code:'FORBIDDEN',message:'Denied'}),{status:403}));vi.stubGlobal('fetch',fetch)
 await expect(releasesApi.get('p','r')).rejects.toMatchObject({code:'INVALID_RESPONSE'});await expect(releasesApi.get('p','r')).rejects.toMatchObject({code:'INVALID_RESPONSE'});await expect(releasesApi.get('p','r')).rejects.toMatchObject({status:403,code:'FORBIDDEN'})
})
it('release pages use explicit cursors and snapshot metrics reject contradictory counts',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({items:[release],nextCursor:'next'}))).mockResolvedValueOnce(new Response(JSON.stringify({id:'bad',coverage:{numerator:2,denominator:1}})));vi.stubGlobal('fetch',fetch)
 expect((await releasesApi.list('p','opaque')).nextCursor).toBe('next');expect(fetch.mock.calls[0][0]).toContain('limit=25&cursor=opaque');await expect(releasesApi.snapshot('p','r','bad')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('release HTTP serializes only editable fields and preserves original key and version',async()=>{
 const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/csrf')?new Response(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'synthetic'})):new Response(JSON.stringify(release),{headers:{ETag:'"1"'}}));vi.stubGlobal('fetch',fetch)
 await releasesApi.update('p',release,{name:'Release',description:'',startDate:'2026-01-01',endDate:'2026-01-31',status:'PLANNED'},{idempotencyKey:'original-key'})
 const [,init]=fetch.mock.calls[1] as unknown as [string,RequestInit];expect(JSON.parse(String(init.body))).toEqual({name:'Release',description:'',startDate:'2026-01-01',endDate:'2026-01-31',status:'PLANNED'});expect(new Headers(init.headers).get('If-Match')).toBe('"1"');expect(new Headers(init.headers).get('Idempotency-Key')).toBe('original-key')
})
