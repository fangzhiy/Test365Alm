import { requestJson } from './projectAccess'
export type Json = null | string | number | boolean | Json[] | { [key: string]: Json }
export type Row = { [key: string]: Json }
export type Report = { kind: 'RUN'|'SET'|'DEFECTS'; version: string; generatedAt: string; timeZone: string; projectId: string; projectName: string; filters: Record<string,string>; facts: Row; items: Row[]; steps: Row[]; attachments: Row[]; reviews: Row[]; signatures: Row[]; links: Row[]; retests: Row[] }
export type ReportTarget = { kind:'RUN'; runId:string; attemptId:string } | { kind:'SET'; setId:string } | { kind:'DEFECTS'; setId?:string }
export const object = (v: unknown): v is Row => typeof v==='object' && v!==null && !Array.isArray(v)
const invalid = (): never => { throw new Error('报告响应不完整，无法查看或打印') }
const string = (v: Json | undefined): v is string => typeof v === 'string' && v.length > 0
const count = (v: Json | undefined): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const nullable = (r: Row, key: string) => key in r && (r[key] === null || typeof r[key] === 'string')
const date = (r: Row, key: string) => nullable(r,key) && (r[key] === null || Number.isFinite(Date.parse(String(r[key]))))
const unique = (items: Row[]) => items.every(i=>string(i.id)) && new Set(items.map(i=>i.id)).size === items.length
const states = ['NOT_RUN','RUNNING','PAUSED','PASS','FAIL','BLOCKED']

// Permission errors follow the existing execution scope rule. Specific business
// errors (including CSRF) must not invalidate a scope just because of HTTP 403.
export function reportScopeDenied(error: unknown): boolean {
 if (typeof error !== 'object' || error === null) return false
 const e=error as {code?:unknown;status?:unknown}
 return typeof e.code==='string'
  ? ['UNAUTHENTICATED','FORBIDDEN','NOT_FOUND','PROJECT_ACCESS_DENIED','PROJECT_NOT_FOUND'].includes(e.code)
  : e.status===401 || e.status===403 || e.status===404
}

function validateRun(r: Report, target: Extract<ReportTarget,{kind:'RUN'}>) {
 const {run,attempt,manifest:m}=r.facts
 if(!object(run)||!object(attempt)||!object(m)||run.id!==target.runId||attempt.id!==target.attemptId||attempt.run_id!==run.id||attempt.project_id!==r.projectId||attempt.status!=='FINISHED'||run.manifest_id!==m.id||run.test_instance_id!==m.test_instance_id||m.project_id!==r.projectId||!string(m.source_test_case_id)||!string(m.source_revision_id)||!string(m.title)||!count(m.source_revision_no)||m.source_revision_no<1||!object(r.facts.businessFields)||!date(attempt,'started_at')||!date(attempt,'finished_at')||!['PASS','FAIL','BLOCKED'].includes(String(attempt.conclusion))||!r.steps.length)return invalid()
 if(r.filters.runId!==target.runId||r.filters.attemptId!==target.attemptId||!string(r.facts.testSetId)||typeof r.facts.testSetName!=='string')return invalid()
 let ordinal=0
 for(const s of r.steps) {
  if(s.project_id!==r.projectId||s.attempt_id!==attempt.id||!string(s.step_key)||!count(s.ordinal)||s.ordinal<=ordinal||typeof s.action!=='string'||typeof s.expected!=='string'||typeof s.actual_result!=='string'||!['NOT_RUN','PASS','FAIL','BLOCKED'].includes(String(s.conclusion))||!date(s,'executed_at')||!date(s,'updated_at'))return invalid()
  ordinal=s.ordinal
 }
 for(const a of r.attachments)if(!r.steps.some(s=>s.id===a.run_step_id)||!['id','asset_id','asset_revision_id','filename','sha256'].every(k=>string(a[k]))||!count(a.revision_no)||a.revision_no<1||!count(a.size))return invalid()
 for(const e of r.reviews)if(e.project_id!==r.projectId||e.test_case_id!==m.source_test_case_id||e.revision_id!==m.source_revision_id||!date(e,'occurred_at')||!string(e.action))return invalid()
}

function validateSet(r: Report, target: Extract<ReportTarget,{kind:'SET'}>) {
 const f=r.facts,c=f.counts
 if(!object(f.testSet)||f.testSet.id!==target.setId||f.testSet.project_id!==r.projectId||typeof f.testSet.name!=='string'||r.filters.testSetId!==target.setId||!count(f.totalInstances)||!count(f.otherActiveAttempts)||!count(f.relatedDefects)||!object(c)||!object(c.categories)||!count(c.matched)||c.matched!==r.items.length||c.matched>f.totalInstances)return invalid()
 const actual:Record<string,number>=Object.fromEntries(states.map(s=>[s,0]))
 if(!r.filters.status&&c.matched!==f.totalInstances)return invalid()
 for(const i of r.items) {
  if(!string(i.title)||!string(i.test_case_id)||!string(i.test_revision_id)||!count(i.revision_no)||i.revision_no<1||!states.includes(String(i.status))||!count(i.otherActiveAttempts)||!date(i,'started_at')||!date(i,'finished_at')||!nullable(i,'run_id')||!nullable(i,'attempt_id'))return invalid()
  if(i.status==='NOT_RUN'?(i.run_id!==null||i.attempt_id!==null||i.started_at!==null||i.finished_at!==null||i.conclusion!==null):(!string(i.run_id)||!string(i.attempt_id)))return invalid()
  if(['PASS','FAIL','BLOCKED'].includes(String(i.status))&&i.conclusion!==i.status)return invalid()
  if(r.filters.status&&i.status!==r.filters.status)return invalid()
  actual[String(i.status)]++
 }
 for(const s of states)if(!count(c.categories[s])||c.categories[s]!==actual[s])return invalid()
 if(f.otherActiveAttempts!==r.items.reduce((sum,i)=>sum+Number(i.otherActiveAttempts),0))return invalid()
 const finished=actual.PASS+actual.FAIL+actual.BLOCKED
 const matches=(v:Json|undefined,n:number,d:number)=>d===0?v===null:typeof v==='number'&&v>=0&&v<=1&&Math.abs(v-n/d)<1e-10
 if(!matches(c.completionRate,finished,r.items.length)||!matches(c.passRate,actual.PASS,finished))return invalid()
}

function validateDefectsAndSignatures(r: Report) {
 const defects=new Map(r.items.map(d=>[d.id,d]))
 if(r.kind!=='SET')for(const d of r.items) {
  const v=d.revision
  if(!object(v)||v.project_id!==r.projectId||v.defect_id!==d.id||!string(v.id)||!string(v.title)||!count(v.revision_no)||v.revision_no<1||!['OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED'].includes(String(v.status))||!['LOW','MEDIUM','HIGH','CRITICAL'].includes(String(v.severity))||!nullable(d,'resolved_revision_id')||!nullable(d,'currentResolutionId'))return invalid()
  const active=['RESOLVED','CLOSED'].includes(String(v.status))?d.resolved_revision_id:null
  if(d.currentResolutionId!==active)return invalid()
 }
 for(const l of r.links) {
  if(l.project_id!==r.projectId||!defects.has(l.defect_id)||!['RUN_STEP','REQUIREMENT'].includes(String(l.kind)))return invalid()
  if(l.kind==='RUN_STEP'&&(!['run_id','attempt_id','test_case_id','test_revision_id','manifest_id','step_key'].every(k=>string(l[k]))||!['FAIL','BLOCKED'].includes(String(l.source_conclusion))||typeof l.actual_result!=='string'||typeof l.expected_result!=='string'))return invalid()
  if(l.kind==='REQUIREMENT'&&(!string(l.requirement_id)||!string(l.requirement_revision_id)))return invalid()
 }
 for(const rt of r.retests)if(rt.project_id!==r.projectId||!defects.has(rt.defect_id)||!['run_id','attempt_id','resolved_revision_id'].every(k=>string(rt[k]))||!['RUNNING','PAUSED','FINISHED'].includes(String(rt.status)))return invalid()
 for(const s of r.signatures) {
  if(s.projectId!==r.projectId||!['id','kind','objectId','snapshotId','contentDigest','signedAt','displayName','actorId'].every(k=>string(s[k]))||!date(s,'signedAt')||!object(s.contentSnapshot))return invalid()
  if(s.contentSnapshot.projectId!==r.projectId||s.contentSnapshot.kind!==s.kind||s.contentSnapshot.objectId!==s.objectId||s.contentSnapshot.snapshotId!==s.snapshotId||!object(s.contentSnapshot.content))return invalid()
  if(s.kind==='DEVIATION_CLOSURE') {
   if(!defects.has(s.objectId))return invalid()
   // The original signed closure revision can be historical. Never substitute
   // the defect's current revision for the snapshot's exact source identity.
   const snapshot=s.contentSnapshot,content=snapshot.content
   if(snapshot.objectId!==s.objectId||snapshot.snapshotId!==s.snapshotId||snapshot.projectId!==r.projectId||!object(content)||!object(content.revision)||content.revision.id!==s.snapshotId||content.revision.defect_id!==s.objectId||content.revision.status!=='CLOSED')return invalid()
  } else if(r.kind==='RUN') {
   const m=r.facts.manifest as Row,a=r.facts.attempt as Row,run=r.facts.run as Row
   if(s.kind==='TEST_APPROVAL'?(s.objectId!==m.source_test_case_id||s.snapshotId!==m.source_revision_id):s.kind==='RUN_COMPLETION'?(s.objectId!==run.id||s.snapshotId!==a.id):true)return invalid()
  } else return invalid()
 }
 if(r.kind==='RUN')for(const d of r.items)if(!r.links.some(l=>l.defect_id===d.id&&l.run_id===r.filters.runId&&l.attempt_id===r.filters.attemptId)&&!r.retests.some(rt=>rt.defect_id===d.id&&rt.run_id===r.filters.runId&&rt.attempt_id===r.filters.attemptId))return invalid()
}
export function parseReport(v: unknown, project: string, target: ReportTarget): Report {
 if(!object(v)||v.projectId!==project||v.kind!==target.kind||v.version!=='F-REPORT-1'||v.timeZone!=='UTC'||typeof v.generatedAt!=='string'||!Number.isFinite(Date.parse(v.generatedAt))||typeof v.projectName!=='string'||!object(v.facts)||!object(v.filters)||!Object.values(v.filters).every(x=>typeof x==='string'))return invalid()
 for(const key of ['items','steps','attachments','reviews','signatures','links','retests'])if(!Array.isArray(v[key])||!(v[key] as Json[]).every(object))return invalid()
 const r=v as unknown as Report
 for(const key of ['items','steps','attachments','reviews','signatures','links'] as const)if(!unique(r[key]))return invalid()
 if(target.kind==='RUN')validateRun(r,target)
 if(target.kind==='SET')validateSet(r,target)
 if(target.kind==='DEFECTS'&&(!count(r.facts.matched)||r.facts.matched!==r.items.length))return invalid()
 if(target.kind==='DEFECTS'&&(r.filters.testSetId!==(target.setId??'')||typeof r.facts.testSetName!=='string'))return invalid()
 validateDefectsAndSignatures(r)
 return r
}
export async function loadReport(project:string,target:ReportTarget,filters:Record<string,string>,signal:AbortSignal):Promise<Report>{
 const root=`/api/v1/projects/${encodeURIComponent(project)}/reports`
 const path=target.kind==='RUN'?`/runs/${encodeURIComponent(target.runId)}/attempts/${encodeURIComponent(target.attemptId)}`:target.kind==='SET'?`/test-sets/${encodeURIComponent(target.setId)}`:'/defects'
 const query=new URLSearchParams({...(target.kind==='DEFECTS'&&target.setId?{testSetId:target.setId}:{}),...filters})
 const effectiveTarget=target.kind==='DEFECTS'?{...target,setId:filters.testSetId??target.setId}:target
 return parseReport(await requestJson(`${root}${path}?${query}`,undefined,{signal}),project,effectiveTarget)
}
