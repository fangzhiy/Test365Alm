import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SignaturePanel from './SignaturePanel'
import { signatureApi } from './signaturesApi'
vi.mock('./signaturesApi',async()=>({...await vi.importActual<typeof import('./signaturesApi')>('./signaturesApi'),signatureApi:{get:vi.fn(),prepare:vi.fn(),sign:vi.fn()}}))
const view={kind:'TEST_APPROVAL' as const,objectId:'test',snapshotId:'rev',rowVersion:3,state:'APPROVED',canSign:true,contentDigest:'a'.repeat(64),signature:null}
const challenge={id:'challenge',expiresAt:'2026-11-11T12:05:00Z',authenticationUrl:'/api/authenticate'}
const signed={...view,canSign:false,signature:{id:'signed',schema:'SIGNATURE_RECORD_V1' as const,projectId:'project',kind:'TEST_APPROVAL' as const,meaning:'用例批准确认',objectId:'test',snapshotId:'rev',sourceVersion:3,actorId:'actor',issuer:'https://idp',subject:'actor',displayName:'签署人',authenticatedAt:'2026-11-11T12:00:00Z',signedAt:'2026-11-11T12:00:01Z',contentDigest:'a'.repeat(64),contentSnapshot:{},evidence:{provider:'SIMULATED_V1' as const,simulated:true as const,reference:'signed',bindingDigest:'b'.repeat(64)}}}
const popup={closed:false,close:vi.fn()}
const props={projectId:'project',kind:'TEST_APPROVAL' as const,objectId:'test',snapshotId:'rev',rowVersion:3,contextKey:'member:session-1'}
const settle=async()=>{fireEvent.click(screen.getByRole('button',{name:'查看模拟签署'}));await screen.findByRole('button',{name:'开始模拟签署'})}
beforeEach(()=>{vi.mocked(signatureApi.get).mockReset().mockResolvedValue(view);vi.mocked(signatureApi.prepare).mockReset().mockResolvedValue(challenge);vi.mocked(signatureApi.sign).mockReset();vi.spyOn(window,'open').mockReturnValue(popup as unknown as Window)})
afterEach(()=>{cleanup();vi.restoreAllMocks()})
describe('SignaturePanel',()=>{
 it('uses a real IdP window and requires explicit confirmation after the matching callback',async()=>{
  const lock=vi.fn();render(<SignaturePanel {...props} onLock={lock}/>);await settle();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}))
  fireEvent.click(await screen.findByRole('button',{name:'重新登录以确认身份'}));expect(window.open).toHaveBeenCalledWith(challenge.authenticationUrl,'_blank',expect.any(String));expect(lock).toHaveBeenLastCalledWith(true)
  expect(signatureApi.sign).not.toHaveBeenCalled()
  act(()=>window.dispatchEvent(new MessageEvent('message',{origin:window.location.origin,source:popup as unknown as Window,data:{type:'test365alm-signature',status:'reauthenticated'}})))
  expect(await screen.findByRole('button',{name:'确认模拟签署：用例批准确认'})).toBeEnabled()
  expect(signatureApi.sign).not.toHaveBeenCalled();expect(screen.queryByLabelText(/密码/)).not.toBeInTheDocument()
 })
 it('ignores foreign origin and wrong window messages and never treats them as authentication',async()=>{
  render(<SignaturePanel {...props}/>);await settle();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));fireEvent.click(await screen.findByRole('button',{name:'重新登录以确认身份'}))
  for(const event of [{origin:'https://evil.example',source:popup},{origin:location.origin,source:window}])act(()=>window.dispatchEvent(new MessageEvent('message',{...event,source:event.source as unknown as Window,data:{type:'test365alm-signature',status:'reauthenticated'}})))
  expect(screen.queryByRole('button',{name:/确认模拟签署：/})).not.toBeInTheDocument();expect(signatureApi.sign).not.toHaveBeenCalled()
 })
 it('keeps the original challenge intent key and version through unknown outcomes',async()=>{
  vi.mocked(signatureApi.prepare).mockRejectedValueOnce(new Error('network unknown')).mockResolvedValue(challenge)
  const lock=vi.fn();render(<SignaturePanel {...props} onLock={lock}/>);await settle();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));fireEvent.click(await screen.findByRole('button',{name:'重试原签署意图'}));await screen.findByRole('button',{name:'重新登录以确认身份'})
  const calls=vi.mocked(signatureApi.prepare).mock.calls;expect(calls).toHaveLength(2);expect(calls[0].slice(0,6)).toEqual(calls[1].slice(0,6));expect(calls[0][4]).toBe('"3"');expect(lock).toHaveBeenLastCalledWith(true)
 })
 it('keeps original sign body etag and key after unknown result and unlocks only after confirmation',async()=>{
  vi.mocked(signatureApi.sign).mockRejectedValueOnce(new Error('network')).mockResolvedValue(signed)
  const lock=vi.fn();render(<SignaturePanel {...props} onLock={lock}/>);await settle();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));fireEvent.click(await screen.findByRole('button',{name:'重新登录以确认身份'}))
  act(()=>window.dispatchEvent(new MessageEvent('message',{origin:location.origin,source:popup as unknown as Window,data:{type:'test365alm-signature',status:'reauthenticated'}})))
  fireEvent.click(await screen.findByRole('button',{name:'确认模拟签署：用例批准确认'}));fireEvent.click(await screen.findByRole('button',{name:'重试原签署意图'}))
  await waitFor(()=>expect(signatureApi.sign).toHaveBeenCalledTimes(2));expect(vi.mocked(signatureApi.sign).mock.calls[0].slice(0,7)).toEqual(vi.mocked(signatureApi.sign).mock.calls[1].slice(0,7));await waitFor(()=>expect(lock).toHaveBeenLastCalledWith(false))
 })
 it('aborts and clears protected signing intent on real session teardown and ignores late completion',async()=>{
  let resolve!:(v:typeof challenge)=>void;vi.mocked(signatureApi.prepare).mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
  const lock=vi.fn();const ui=render(<SignaturePanel {...props} onLock={lock}/>);await settle();fireEvent.click(screen.getByRole('button',{name:'开始模拟签署'}));await waitFor(()=>expect(signatureApi.prepare).toHaveBeenCalledTimes(1))
  const signal=vi.mocked(signatureApi.prepare).mock.calls[0][6]?.signal
  ui.rerender(<SignaturePanel {...props} contextKey="other-member:session-2" onLock={lock}/>);expect(signal?.aborted).toBe(true)
  await act(async()=>resolve(challenge));expect(screen.queryByRole('button',{name:'重新登录以确认身份'})).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'查看模拟签署'})).toBeInTheDocument()
 })
 it('viewer can read frozen evidence but cannot start a new signing flow',async()=>{
  render(<SignaturePanel {...props} readOnly/>);fireEvent.click(screen.getByRole('button',{name:'查看模拟签署'}));await screen.findByText(/内容摘要/)
  expect(screen.queryByRole('button',{name:'开始模拟签署'})).not.toBeInTheDocument();expect(signatureApi.prepare).not.toHaveBeenCalled()
 })
})
