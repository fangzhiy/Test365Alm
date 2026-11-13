import { Progress, Tag } from 'antd'
import { categories, instanceHistory } from './overviewData'
import { useRunHistory } from './useRunHistory'
import ReportLauncher from './Reports'
import { useEffect, useState } from 'react'
import { executionApi, type Assignment, type Assignee, type GridFilters, type Page, type TestInstance } from './testExecution'
import { useCoreRequest } from './useCoreRequest'
import { newIdempotencyKey } from './requirements'
import './ExecutionGrid.css'
import InstanceNavigator from './InstanceNavigator'

type Props = {
  projectId: string; instances: TestInstance[]; writable: boolean; disabled: boolean; nextCursor: string | null; filters: GridFilters
  refreshing?: boolean
  onFilter: (filters: GridFilters) => void; onMore: () => void; onRefresh: () => void
  onOpen: (instance: TestInstance) => void; onStart: (instance: TestInstance) => void
  onAssigned: (assignment: Assignment) => void; onLock: (locked: boolean) => void; onDenied: () => void
}
const statuses = { NOT_RUN:'未运行', RUNNING:'运行中', PAUSED:'已暂停', FINISHED:'已完成' }
type Intent = { instanceId: string; assigneeId: string | null; version: number; key: string }

export default function ExecutionGrid(props: Props) {
  const {projectId,instances,writable,disabled,nextCursor,onLock,onDenied} = props
  const read = useCoreRequest(), write = useCoreRequest()
  const historyGeneration=JSON.stringify([projectId,instances.map(i=>[i.id,i.runId,i.attemptId,i.status]),props.refreshing])
  const {history,error:historyError}=useRunHistory(projectId,instances[0]?.setId,historyGeneration,onDenied)
  const counts=categories(instances), completed=counts.PASS+counts.FAIL+counts.BLOCKED
  const [people,setPeople] = useState<Page<Assignee>>({items:[],nextCursor:null})
  const [selection,setSelection] = useState<{id:string;projectId:string;setId:string;filters:string} | null>(null)
  const filterScope = JSON.stringify([props.filters.status ?? '',props.filters.assigneeId ?? ''])
  const navigation = selection?.projectId === projectId && selection.filters === filterScope
    ? instances.find(item=>item.id === selection.id && item.setId === selection.setId) : undefined
  // A removed/foreign selection is invalid immediately, including during a
  // refresh render. Forget its identity so it cannot reopen if rows reappear.
  if(selection && !navigation)setSelection(null)
  const navigationDenied = () => { setSelection(null); onDenied() }
  const [editing,setEditing] = useState<TestInstance | null>(null)
  const [assignee,setAssignee] = useState('')
  const [status,setStatus] = useState(props.filters.status ?? '')
  const [owner,setOwner] = useState(props.filters.assigneeId ?? '')
  const [intent,setIntent] = useState<Intent | null>(null)
  const locked = disabled || read.busy || write.busy || intent !== null
  useEffect(()=>{ onLock(intent !== null); return ()=>onLock(false) },[intent,onLock])
  useEffect(()=>{ if(read.denied || write.denied) onDenied() },[read.denied,write.denied,onDenied])
  const loadPeople = (cursor: string | null = null) => read.run(signal=>executionApi.executionAssignees(projectId,cursor,{signal}),page=>setPeople(old=>({...page,items:cursor?[...old.items,...page.items]:page.items})))
  const save = async () => {
    const op = intent ?? (editing?.assignmentVersion ? {instanceId:editing.id,assigneeId:assignee || null,version:editing.assignmentVersion,key:newIdempotencyKey()} : null)
    if (!op) return
    setIntent(op)
    const result = await write.run(signal=>executionApi.assignInstance(projectId,op.instanceId,op.assigneeId,op.version,{signal,idempotencyKey:op.key}),value=>{
      props.onAssigned(value); setEditing(null)
    })
    if(result === 'ok' || result === 'rejected') setIntent(null)
  }
  return <section className="execution-grid" aria-label="实例执行网格区域">
    <div className="lab-progress" aria-label="当前已加载实例统计">
      <strong>实例最新结果</strong><Progress percent={instances.length?Math.round(completed/instances.length*100):0} size="small" showInfo={instances.length>0}/>
      <Tag color="green">通过 {counts.PASS}</Tag><Tag color="red">失败 {counts.FAIL}</Tag><Tag color="blue">进行中 {counts.RUNNING}</Tag><Tag color="gold">暂停 {counts.PAUSED} · 阻塞 {counts.BLOCKED}</Tag><Tag>未运行 {counts.NOT_RUN}</Tag>
      <p className="scope-note">仅当前已加载 {instances.length} 个实例{nextCursor?'（还有下一页）':''}；完整范围见测试集汇总报告。{props.refreshing?'正在核验网格，报告暂不可用。':''}</p>
    </div>
    <div className="execution-grid-tools">
      <label>执行状态<select aria-label="执行状态筛选" value={status} disabled={locked} onChange={e=>setStatus(e.target.value as typeof status)}>
        <option value="">全部状态</option>{Object.entries(statuses).map(([key,label])=><option key={key} value={key}>{label}</option>)}
      </select></label>
      <label>负责人<select aria-label="负责人筛选" value={owner} disabled={locked} onChange={e=>setOwner(e.target.value)}>
        <option value="">全部负责人</option>{people.items.map(person=><option key={person.id} value={person.id}>{person.displayName}</option>)}
      </select></label>
      <button type="button" className="secondary-button" disabled={locked} onClick={()=>void loadPeople()}>加载项目成员</button>
      {people.nextCursor && <button type="button" className="secondary-button" disabled={locked} onClick={()=>void loadPeople(people.nextCursor)}>更多负责人</button>}
      <button type="button" className="secondary-button" disabled={locked} onClick={()=>props.onFilter({...(status?{status:status as GridFilters['status']} : {}),...(owner?{assigneeId:owner}: {})})}>筛选实例</button>
      <button type="button" className="secondary-button" disabled={locked} onClick={props.onRefresh}>刷新实例网格</button>
    </div>
    <p>执行日期为最新尝试的服务端开始时间。当前负责人调整只用于后续新运行。</p>
    <div className="execution-grid-scroll"><table aria-label="实例执行网格"><thead><tr>
      <th scope="col">用例</th><th scope="col">修订</th><th scope="col">状态 / 结论</th><th scope="col">负责人</th><th scope="col">执行日期</th><th scope="col">历史次数</th><th scope="col">操作</th>
    </tr></thead><tbody>{instances.map(item=><tr key={item.id}>
      <th scope="row">{item.displayNumber ? `${item.displayNumber} · ` : ''}{item.title}</th><td>修订 {item.revisionNumber}</td>
      <td><Tag color={item.conclusion==='PASS'?'green':item.conclusion==='FAIL'?'red':item.status==='RUNNING'?'blue':'gold'}>{item.status ? statuses[item.status] : '状态未知'}{item.conclusion ? ` · ${item.conclusion}` : ''}</Tag></td>
      <td>{item.assigneeName ?? '未分配'}</td><td>{item.executionDate ? <time dateTime={item.executionDate}>{item.executionDate.replace('T',' ').replace(/:\d\d(?:\.\d+)?Z$/, ' UTC')}</time> : '尚无执行日期'}</td>
      <td>{history?<>{instanceHistory(history,item.id).count} 次 {instanceHistory(history,item.id).failed && <Tag color="red">曾失败</Tag>}</>:'—'}</td>
      <td><div className="execution-grid-actions"><button type="button" disabled={locked} aria-label={`查看绑定修订 ${item.title}`} onClick={()=>setSelection({id:item.id,projectId,setId:item.setId,filters:filterScope})}>修订与闭环进度</button>{item.runId && <button type="button" className="secondary-button" aria-label={`进入运行 ${item.title}`} disabled={locked} onClick={()=>props.onOpen(item)}>进入运行</button>}
        {item.status==='FINISHED' && item.runId && item.attemptId && !props.refreshing && !locked && <ReportLauncher projectId={projectId} target={{kind:'RUN',runId:item.runId,attemptId:item.attemptId}} label="报告" ariaLabel={`最新报告 ${item.title}`} onDenied={onDenied}/>}
        {writable && <><button type="button" className="secondary-button" disabled={locked} onClick={()=>props.onStart(item)}>启动手工运行</button>
          <button type="button" className="secondary-button" aria-label={`分配负责人 ${item.title}`} disabled={locked || !item.assignmentVersion} onClick={()=>{setEditing(item);setAssignee(item.assigneeId ?? '');write.setMessage('');void loadPeople()}}>分配负责人</button></>}
      </div></td>
    </tr>)}</tbody></table></div>
    {historyError && <p role="status">{historyError}；次数与曾失败标记未汇总。</p>}
    {!instances.length && <p>当前筛选没有实例。</p>}
    {nextCursor && <button type="button" className="secondary-button" disabled={locked} onClick={props.onMore}>加载更多实例</button>}
    {navigation && <InstanceNavigator key={`${projectId}:${navigation.id}:${navigation.testRevisionId}`} projectId={projectId} instance={navigation} refreshing={props.refreshing} onDenied={navigationDenied} onClose={()=>setSelection(null)}/>}
    {editing && <form className="execution-assignment" onSubmit={e=>{e.preventDefault();void save()}} aria-label="分配实例负责人">
      <strong>{editing.title}</strong><label>实例负责人<select aria-label="实例负责人" value={assignee} disabled={locked} onChange={e=>setAssignee(e.target.value)}>
        <option value="">未分配</option>{editing.assigneeId && !people.items.some(p=>p.id===editing.assigneeId) && <option value={editing.assigneeId}>{editing.assigneeName}（待核验）</option>}
        {people.items.map(person=><option key={person.id} value={person.id}>{person.displayName}</option>)}
      </select></label>
      {!intent && <><button type="submit" className="primary-button" disabled={locked}>保存负责人</button><button type="button" className="secondary-button" disabled={locked} onClick={()=>setEditing(null)}>取消分配</button></>}
      {intent && !write.busy && <><p>结果未知，保留原负责人、版本与操作；可重试原操作。</p><button type="button" className="secondary-button" disabled={disabled || read.busy} onClick={()=>void save()}>重试原负责人操作</button></>}
    </form>}
    {read.message && <p role="status">{read.message}</p>}{write.message && <p role="status">{write.message}</p>}
  </section>
}
