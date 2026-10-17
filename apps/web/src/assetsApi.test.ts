import {afterEach,expect,it,vi} from 'vitest'
import {assetsApi,prepareAssetFile} from './assetsApi'
const sha='a'.repeat(64),now='2026-10-17T00:00:00Z'
const upload={id:'u',projectId:'p',assetId:null,revisionId:null,filename:'safe.txt',declaredSize:4,declaredSha256:sha,declaredContentType:'text/plain',actualSize:null,computedSha256:null,detectedMime:null,state:'CREATED',cleanupPending:false,lastErrorCode:null,createdBy:'member',createdAt:now,rowVersion:1}
afterEach(()=>vi.unstubAllGlobals())
it('asset HTTP validates upload state fields ownership and rejects invalid JSON',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({...upload,state:'CLEAN'}))).mockResolvedValueOnce(new Response(JSON.stringify({...upload,projectId:'other'}))).mockResolvedValueOnce(new Response('not json')).mockResolvedValueOnce(new Response(JSON.stringify({...upload,state:'PUBLISHED'}))).mockResolvedValueOnce(new Response(JSON.stringify({...upload,actualSize:4})))
 vi.stubGlobal('fetch',fetch)
 for(let i=0;i<5;i++)await expect(assetsApi.upload('p','u')).rejects.toHaveProperty('code')
})
it('asset POST and raw PUT keep original key content upload identity and CSRF',async()=>{
 const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/csrf')?new Response(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'synthetic'})):String(input).endsWith('/content')?new Response(JSON.stringify({uploadId:'u',state:'UPLOADED',size:4,sha256:sha,mime:'text/plain'})):new Response(JSON.stringify(upload)))
 vi.stubGlobal('fetch',fetch);const file=new File(['safe'],'safe.txt',{type:'text/plain'})
 await assetsApi.createUpload('p',{filename:'safe.txt',size:4,sha256:sha,contentType:'text/plain'},{idempotencyKey:'original'})
 await assetsApi.put('p','u',file,{signal:new AbortController().signal})
 const [,post]=fetch.mock.calls[1] as unknown as [string,RequestInit], [path,put]=fetch.mock.calls[3] as unknown as [string,RequestInit]
 expect(new Headers(post.headers).get('Idempotency-Key')).toBe('original');expect(new Headers(put.headers).get('X-CSRF-TOKEN')).toBe('synthetic');expect(path).toBe('/api/v1/projects/p/uploads/u/content');expect(put.body).toBe(file);expect(new Headers(put.headers).get('Content-Type')).toBe('text/plain')
})
it('asset pages use explicit cursors and reject cross target frozen attachment',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({items:[upload],nextCursor:'opaque'}))).mockResolvedValueOnce(new Response(JSON.stringify({items:[{id:'bad'}],nextCursor:null})))
 vi.stubGlobal('fetch',fetch);expect((await assetsApi.uploads('p','cursor')).nextCursor).toBe('opaque');expect(fetch.mock.calls[0][0]).toContain('limit=25&cursor=cursor');await expect(assetsApi.attachments('p',{kind:'REQUIREMENT',id:'r'})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('asset download refuses unsafe headers and preserves real permission denial',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response('safe',{headers:{'Content-Type':'text/html'}})).mockResolvedValueOnce(new Response(JSON.stringify({code:'FORBIDDEN',message:'no access'}),{status:403}))
 vi.stubGlobal('fetch',fetch);await expect(assetsApi.download('p','a','r')).rejects.toMatchObject({code:'INVALID_RESPONSE'});await expect(assetsApi.download('p','a','r')).rejects.toMatchObject({code:'FORBIDDEN',status:403})
})
it('asset file preflight rejects unsupported empty and oversized files before HTTP',async()=>{
 await expect(prepareAssetFile(new File(['x'],'unsafe.html',{type:'text/html'}))).rejects.toThrow()
 await expect(prepareAssetFile(new File([],'empty.txt',{type:'text/plain'}))).rejects.toMatchObject({status:400,code:'INVALID_REQUEST'})
 const huge=new File(['x'],'huge.txt',{type:'text/plain'});Object.defineProperty(huge,'size',{value:26214401});await expect(prepareAssetFile(huge)).rejects.toThrow()
})
