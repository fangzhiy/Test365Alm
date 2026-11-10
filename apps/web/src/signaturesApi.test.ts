import { beforeEach, describe, expect, it, vi } from 'vitest'
import { signatureApi, type SignatureView } from './signaturesApi'
import { requestJson } from './projectAccess'
vi.mock('./projectAccess',()=>({requestJson:vi.fn()}))
const view:SignatureView={kind:'TEST_APPROVAL',objectId:'test',snapshotId:'revision',rowVersion:3,state:'APPROVED',canSign:true,contentDigest:'a'.repeat(64),signature:null}
beforeEach(()=>vi.mocked(requestJson).mockReset())
describe('signature API',()=>{
 it('scopes exact snapshots and keeps the original strong version and key for both commands',async()=>{
  vi.mocked(requestJson).mockResolvedValue(view)
  expect(await signatureApi.get('project','TEST_APPROVAL','test','revision')).toEqual(view)
  expect(requestJson).toHaveBeenLastCalledWith('/api/v1/projects/project/signatures/TEST_APPROVAL/test/revision',undefined,undefined)
  await signatureApi.sign('project','TEST_APPROVAL','test','revision',{challengeId:'challenge'},'"3"','original-key')
  expect(requestJson).toHaveBeenLastCalledWith(expect.any(String),expect.objectContaining({method:'POST',body:'{"challengeId":"challenge"}',headers:expect.objectContaining({'If-Match':'"3"'})}),expect.objectContaining({idempotencyKey:'original-key'}))
 })
 it('rejects wrong target scope malformed digest impossible capability and forged signed evidence',async()=>{
  for(const value of [{...view,objectId:'foreign'},{...view,snapshotId:'foreign'},{...view,contentDigest:'bad'},{...view,state:'DRAFT'},
   {...view,signature:{id:'forged',evidence:{provider:'REAL_VENDOR'}}}]){
   vi.mocked(requestJson).mockResolvedValue(value)
   await expect(signatureApi.get('project','TEST_APPROVAL','test','revision')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  }
 })
 it('accepts only the exact same origin server authentication path for its returned challenge',async()=>{
  const base='/api/v1/projects/project/signatures/TEST_APPROVAL/test/revision'
  const challenge={id:'challenge',expiresAt:'2026-11-11T12:05:00Z',authenticationUrl:`${base}/challenges/challenge/authenticate`}
  vi.mocked(requestJson).mockResolvedValue(challenge)
  expect(await signatureApi.prepare('project','TEST_APPROVAL','test','revision','"3"','key')).toEqual(challenge)
  for(const url of ['https://evil.example/login','//evil.example/login',`${base}/challenges/other/authenticate`,`${challenge.authenticationUrl}?token=bad`]){
   vi.mocked(requestJson).mockResolvedValue({...challenge,authenticationUrl:url})
   await expect(signatureApi.prepare('project','TEST_APPROVAL','test','revision','"3"','key')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  }
 })
})
