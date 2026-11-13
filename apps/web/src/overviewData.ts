import { executionApi, type Run, type RunAttempt, type TestInstance } from './testExecution'
import { testsApi } from './tests'
import { defectsApi } from './defectsApi'
import { reviewApi } from './reviewApi'

// Existing paged APIs only. Never present a truncated sample as a project total.
export async function collectPages<T>(load: (cursor: string | null) => Promise<{items:T[];nextCursor?:string|null}>, max: number): Promise<T[]> {
  const items:T[] = [], cursors = new Set<string>()
  let cursor:string|null = null
  do {
    const page = await load(cursor)
    items.push(...page.items)
    if (items.length > max) throw new Error(`概览读取范围超过 ${max} 条，请进入业务页面分页查看；不展示不完整统计`)
    cursor = page.nextCursor ?? null
    if (cursor && cursors.has(cursor)) throw new Error('分页游标重复，无法核验完整统计')
    if (cursor) cursors.add(cursor)
  } while (cursor)
  return items
}
export async function mapLimited<T,R>(items:T[], work:(item:T)=>Promise<R>):Promise<R[]> {
  const values:R[] = new Array(items.length)
  let index = 0
  await Promise.all(Array.from({length:Math.min(items.length,4)}, async()=>{
    while(index<items.length) {const i=index++;values[i]=await work(items[i])}
  }))
  return values
}
export type RunHistory = {run:Run;attempts:RunAttempt[]}
export async function readRunHistory(project:string,setId:string|undefined,signal:AbortSignal):Promise<RunHistory[]> {
  const runs = await collectPages(c=>executionApi.pageRuns(project,{setId},c,100,{signal}),100)
  return mapLimited(runs,async run=>({run,attempts:await collectPages(c=>executionApi.pageAttempts(project,run.id,c,100,{signal}),1000)}))
}
export function instanceHistory(history:RunHistory[],instanceId:string) {
  const attempts=history.filter(h=>h.run.instanceId===instanceId).flatMap(h=>h.attempts)
  return {count:attempts.length,failed:attempts.some(a=>a.outcome==='FAIL')}
}
export function categories(instances:TestInstance[]) {
  const result={NOT_RUN:0,RUNNING:0,PAUSED:0,PASS:0,FAIL:0,BLOCKED:0}
  for(const i of instances) {
    const status=i.status==='FINISHED'?i.conclusion:i.status ?? 'NOT_RUN'
    if(!status || !(status in result)) throw new Error('实例结果不完整，无法统计')
    result[status]++
  }
  return result
}
export async function readOverview(project:string,signal:AbortSignal) {
  const [cases,sets,defects,history] = await Promise.all([
    collectPages(c=>testsApi.list(project,'',c,{signal}),1000),
    collectPages(c=>executionApi.pageSets(project,c,100,{signal}),100),
    collectPages(c=>defectsApi.list(project,'',c,{signal}),1000),
    readRunHistory(project,undefined,signal),
  ])
  const grids=await mapLimited(sets,set=>collectPages(c=>executionApi.executionGrid(project,set.id,c,100,{}, {signal}),1000))
  const instances=grids.flat()
  if(instances.length>1000)throw new Error('项目实例超过 1000 条，请按测试集查看')
  // Exact current revisions, no approval inferred from a different revision.
  const reviews=cases.length<=100 ? await mapLimited(cases,c=>reviewApi.get(project,c.id,c.currentRevisionId,null,{signal})) : null
  return {cases,instances,counts:categories(instances),history,defects,reviews}
}
export type OverviewData = Awaited<ReturnType<typeof readOverview>>
