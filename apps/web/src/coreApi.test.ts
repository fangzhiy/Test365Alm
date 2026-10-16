import { afterEach, expect, it, vi } from 'vitest'
import { coreApi } from './coreApi'
afterEach(()=>vi.unstubAllGlobals())
it('public transport preserves actual HTTP business errors and rejects invalid success bodies',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({code:'NOT_FOUND',message:'Not accessible'}),{status:404})).mockResolvedValueOnce(new Response(JSON.stringify({items:[{}],nextCursor:null}),{status:200}))
  vi.stubGlobal('fetch',fetch)
  await expect(coreApi.fields('p',null)).rejects.toMatchObject({code:'NOT_FOUND',status:404})
  await expect(coreApi.fields('p',null)).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('coverage response cannot claim PASS exceeding association count or use a different rule',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({requirementId:'r',linkedTests:0,activeTests:0,passedTests:1,rule:'CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE'}),{status:200})))
  await expect(coreApi.coverage('p','r')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('optional null default definitions remain readable rather than corrupting the whole page',async()=>{
  const f={id:'f',projectId:'p',kind:'CUSTOM',rowVersion:1,definition:{key:'note',type:'TEXT',required:false,maxLength:10,defaultValue:null}}
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({items:[f],nextCursor:null}),{status:200})))
  await expect(coreApi.fields('p',null)).resolves.toEqual({items:[f],nextCursor:null})
})
