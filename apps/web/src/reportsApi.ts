import { requestJson } from './projectAccess'
export type Json = null | string | number | boolean | Json[] | { [key: string]: Json }
export type Row = { [key: string]: Json }
export type Report = { kind: 'RUN'|'SET'|'DEFECTS'; version: string; generatedAt: string; timeZone: string; projectId: string; projectName: string; filters: Record<string,string>; facts: Row; items: Row[]; steps: Row[]; attachments: Row[]; reviews: Row[]; signatures: Row[]; links: Row[]; retests: Row[] }
export type ReportTarget = { kind:'RUN'; runId:string; attemptId:string } | { kind:'SET'; setId:string } | { kind:'DEFECTS'; setId?:string }
export const object = (v: unknown): v is Row => typeof v==='object' && v!==null && !Array.isArray(v)
const invalid = (): never => { throw new Error('报告响应不完整，无法查看或打印') }
export function parseReport(v: unknown, project: string, target: ReportTarget): Report {
 if(!object(v)||v.projectId!==project||v.kind!==target.kind||v.version!=='F-REPORT-1'||v.timeZone!=='UTC'||typeof v.generatedAt!=='string'||!Number.isFinite(Date.parse(v.generatedAt))||typeof v.projectName!=='string'||!object(v.facts)||!object(v.filters)||!Object.values(v.filters).every(x=>typeof x==='string'))return invalid()
 for(const key of ['items','steps','attachments','reviews','signatures','links','retests'])if(!Array.isArray(v[key])||!(v[key] as Json[]).every(object))return invalid()
 const f=v.facts
 if(target.kind==='RUN'&&(!object(f.run)||!object(f.attempt)||!object(f.manifest)||f.run.id!==target.runId||f.attempt.id!==target.attemptId||f.attempt.status!=='FINISHED'||!object(f.businessFields)))return invalid()
 if(target.kind==='SET'&&(!object(f.testSet)||f.testSet.id!==target.setId||!object(f.counts)||!object(f.counts.categories)||typeof f.totalInstances!=='number'))return invalid()
 if(target.kind==='DEFECTS'&&typeof f.matched!=='number')return invalid()
 return v as unknown as Report
}
export async function loadReport(project:string,target:ReportTarget,filters:Record<string,string>,signal:AbortSignal):Promise<Report>{
 const root=`/api/v1/projects/${encodeURIComponent(project)}/reports`
 const path=target.kind==='RUN'?`/runs/${encodeURIComponent(target.runId)}/attempts/${encodeURIComponent(target.attemptId)}`:target.kind==='SET'?`/test-sets/${encodeURIComponent(target.setId)}`:'/defects'
 const query=new URLSearchParams({...filters,...(target.kind==='DEFECTS'&&target.setId?{testSetId:target.setId}:{})})
 return parseReport(await requestJson(`${root}${path}?${query}`,undefined,{signal}),project,target)
}
