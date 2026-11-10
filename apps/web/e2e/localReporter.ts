import { writeFileSync } from 'node:fs'
import type { Reporter,TestCase,TestResult,FullResult,FullConfig } from '@playwright/test/reporter'
export default class LocalReporter implements Reporter {
 private cases:{name:string;status:string;expected:string;retry:number;durationMs:number}[]=[]
 private execution:{workers:number;retries:number}|undefined
 onBegin(config:FullConfig){
  const retries=new Set(config.projects.map(project=>project.retries))
  if(retries.size!==1)throw new Error('Local report requires consistent project retries')
  this.execution={workers:config.workers,retries:[...retries][0]}
 }
 onTestEnd(test:TestCase,result:TestResult){this.cases.push({name:test.title,status:result.status,expected:test.expectedStatus,retry:result.retry,durationMs:result.duration})}
 onEnd(result:FullResult){
  if(!this.execution)throw new Error('Local execution configuration missing')
  if(!process.env.R03_LOCAL_RESULTS_PATH)throw new Error('Local sanitized report path required')
  writeFileSync(process.env.R03_LOCAL_RESULTS_PATH,JSON.stringify({status:result.status,...this.execution,cases:this.cases},null,2))
 }
}
