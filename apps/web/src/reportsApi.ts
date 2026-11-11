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
 const collections=v as unknown as Report
 for(const s of collections.steps)if(typeof s.id!=='string'||typeof s.ordinal!=='number'||typeof s.action!=='string'||typeof s.expected!=='string'||typeof s.actual_result!=='string'||typeof s.conclusion!=='string'||!('executed_at' in s))return invalid()
 for(const a of collections.attachments)if(!['id','run_step_id','asset_id','asset_revision_id','filename','sha256'].every(k=>typeof a[k]==='string')||typeof a.revision_no!=='number')return invalid()
 for(const s of collections.signatures)if(!['id','kind','objectId','snapshotId','contentDigest','signedAt','displayName'].every(k=>typeof s[k]==='string')||!object(s.contentSnapshot))return invalid()
 if(target.kind==='SET'){
  const counts=f.counts as Row,categories=counts.categories as Row
  const values=['NOT_RUN','RUNNING','PAUSED','PASS','FAIL','BLOCKED'].map(k=>categories[k])
  if(!values.every(x=>typeof x==='number'&&Number.isSafeInteger(x)&&x>=0)||values.reduce<number>((sum,x)=>sum+Number(x),0)!==collections.items.length||counts.matched!==collections.items.length)return invalid()
  for(const i of collections.items)if(typeof i.id!=='string'||typeof i.title!=='string'||typeof i.revision_no!=='number'||typeof i.status!=='string'||!['NOT_RUN','RUNNING','PAUSED','PASS','FAIL','BLOCKED'].includes(i.status))return invalid()
 }
 if(target.kind==='RUN'||target.kind==='DEFECTS')for(const d of collections.items)if(typeof d.id!=='string'||!object(d.revision)||typeof d.revision.title!=='string'||typeof d.revision.revision_no!=='number')return invalid()
 return v as unknown as Report
}
export async function loadReport(project:string,target:ReportTarget,filters:Record<string,string>,signal:AbortSignal):Promise<Report>{
 const root=`/api/v1/projects/${encodeURIComponent(project)}/reports`
 const path=target.kind==='RUN'?`/runs/${encodeURIComponent(target.runId)}/attempts/${encodeURIComponent(target.attemptId)}`:target.kind==='SET'?`/test-sets/${encodeURIComponent(target.setId)}`:'/defects'
 const query=new URLSearchParams({...(target.kind==='DEFECTS'&&target.setId?{testSetId:target.setId}:{}),...filters})
 return parseReport(await requestJson(`${root}${path}?${query}`,undefined,{signal}),project,target)
}
