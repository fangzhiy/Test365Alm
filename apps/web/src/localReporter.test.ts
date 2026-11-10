/// <reference types="node" />
import { afterEach,expect,it,vi } from 'vitest'
import type { FullConfig,FullResult } from '@playwright/test/reporter'
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
