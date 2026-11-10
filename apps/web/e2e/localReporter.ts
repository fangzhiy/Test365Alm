import { writeFileSync } from 'node:fs'
import type { Reporter,TestCase,TestResult,FullResult } from '@playwright/test/reporter'
export default class LocalReporter implements Reporter {
 private cases:{name:string;status:string;expected:string;retry:number;durationMs:number}[]=[]
 onTestEnd(test:TestCase,result:TestResult){this.cases.push({name:test.title,status:result.status,expected:test.expectedStatus,retry:result.retry,durationMs:result.duration})}
 onEnd(result:FullResult){
  if(!process.env.R03_LOCAL_RESULTS_PATH)throw new Error('Local sanitized report path required')
  writeFileSync(process.env.R03_LOCAL_RESULTS_PATH,JSON.stringify({status:result.status,workers:1,retries:0,cases:this.cases},null,2))
 }
}
