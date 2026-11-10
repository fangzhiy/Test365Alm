/// <reference types="node" />
import { afterEach,expect,it,vi } from 'vitest'
import type { FullConfig,FullResult,TestCase,TestResult } from '@playwright/test/reporter'
import { writeFileSync } from 'node:fs'
import LocalReporter from '../e2e/localReporter'
vi.mock('node:fs',()=>{const writeFileSync=vi.fn();return{writeFileSync,default:{writeFileSync}}})
afterEach(()=>{vi.unstubAllEnvs();vi.clearAllMocks()})
it('local report records actual workers and retries rather than fixed acceptance values',()=>{
 vi.stubEnv('R03_LOCAL_RESULTS_PATH','report.json')
 const reporter=new LocalReporter()
 reporter.onBegin({workers:3,projects:[{retries:2}]} as FullConfig)
 reporter.onEnd({status:'passed'} as FullResult)
 const report=JSON.parse(vi.mocked(writeFileSync).mock.calls[0][1] as string)
 expect(report).toMatchObject({workers:3,retries:2,status:'passed'})
})
it('local report refuses missing or inconsistent execution configuration',()=>{
 const reporter=new LocalReporter()
 expect(()=>reporter.onEnd({status:'passed'} as FullResult)).toThrow('configuration missing')
 expect(()=>reporter.onBegin({workers:1,projects:[{retries:0},{retries:1}]} as FullConfig)).toThrow('consistent')
 expect(writeFileSync).not.toHaveBeenCalled()
})
it('local report preserves all four flow identities and actual failed skipped or repeated results',()=>{
 vi.stubEnv('R03_LOCAL_RESULTS_PATH','report.json')
 const names=[
  'real Keycloak UI manual test case flow creates steps, edits order, and keeps viewer read-only',
  'real Keycloak UI manual execution creates, resumes, finishes and reruns a two-step run',
  'real Keycloak UI member creates defect from FAIL retests PASS closes and preserves history',
  'real Keycloak UI designated reviewer rejects approves and new revision requires fresh review',
 ]
 const reporter=new LocalReporter()
 reporter.onBegin({workers:1,projects:[{retries:0}]} as FullConfig)
 const statuses=['passed','failed','skipped','timedOut'] as const
 names.forEach((name,index)=>reporter.onTestEnd({title:name,expectedStatus:'passed'} as TestCase,
  {status:statuses[index],retry:index===3?1:0,duration:10} as TestResult))
 reporter.onEnd({status:'failed'} as FullResult)
 const report=JSON.parse(vi.mocked(writeFileSync).mock.calls[0][1] as string)
 expect(report).toMatchObject({workers:1,retries:0,status:'failed'})
 expect(report.cases).toEqual(names.map((name,index)=>({name,status:statuses[index],expected:'passed',retry:index===3?1:0,durationMs:10})))
})
