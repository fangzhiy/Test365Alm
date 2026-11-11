import { useEffect, useRef, useState } from 'react'
import { testsApi, type TestRevision } from './tests'
import type { TestInstance } from './testExecution'
import ReviewPanel from './ReviewPanel'
import SignaturePanel from './SignaturePanel'
import HistoryPanel from './HistoryPanel'
import ReportLauncher from './Reports'
import { reportScopeDenied } from './reportsApi'

type Props = { projectId: string; instance: TestInstance; refreshing?:boolean; onDenied: () => void; onClose: () => void }

const nextStep = {
 NOT_RUN:'未运行：返回网格启动手工运行，再在运行工作台记录步骤。',
 RUNNING:'运行中：通过网格“进入运行”记录步骤并完成；当前 Attempt 尚不能生成正式报告。',
 PAUSED:'已暂停：通过网格“进入运行”继续执行；当前 Attempt 尚不能生成正式报告。',
 FINISHED:'已完成：可查看当前 Attempt 报告；通过“进入运行”选择历史 Attempt 或按原流程重跑。',
}

// Read-only navigation for the exact saved revision. Commands remain in the
// original case/execution/defect workbenches, with their existing authorization.
export default function InstanceNavigator(props: Props) {
 return <InstanceSession key={`${props.projectId}:${props.instance.id}:${props.instance.testRevisionId}`} {...props}/>
}
function InstanceSession({ projectId, instance, refreshing=false, onDenied, onClose }: Props) {
 const [revision, setRevision] = useState<TestRevision | null>(null)
 const [error, setError] = useState('')
 const denied = useRef(onDenied)
 useEffect(() => { denied.current = onDenied }, [onDenied])
 useEffect(() => {
  const c = new AbortController()
  void testsApi.revision(projectId, instance.testCaseId, instance.testRevisionId, { signal: c.signal }).then(r => {
   if (c.signal.aborted) return
   if (r.id !== instance.testRevisionId || r.revisionNumber !== instance.revisionNumber) throw new Error('绑定修订不一致')
   setRevision(r)
  }).catch(e => {
   if (c.signal.aborted) return
   setRevision(null); setError('无法读取实例绑定修订，请重新选择或刷新权限。')
   if (reportScopeDenied(e)) denied.current()
  })
  return () => c.abort()
 }, [projectId, instance.id, instance.testCaseId, instance.testRevisionId, instance.revisionNumber])
 return <section aria-label="实例修订与闭环导航" className="execution-step">
  <h4>{instance.displayNumber} · {instance.title} · 修订 {instance.revisionNumber}</h4>
  <button type="button" onClick={onClose}>关闭实例导航</button>
  {error && <p role="alert">{error}</p>}
  {!revision && !error && <p role="status">正在读取绑定修订…</p>}
  {revision && <>
   <p>此处只读核对实例绑定修订；复核、批准及签署操作请到“测试用例”选择该用例。新修订的批准不替代这里的状态。</p>
   <p>{revision.description}</p><p>前置条件：{revision.preconditions || '未记录'}</p>
   <ReviewPanel projectId={projectId} testCaseId={instance.testCaseId} revisionId={revision.id} rowVersion={1} contextKey={instance.id} readOnly onDenied={onDenied}/>
   <SignaturePanel projectId={projectId} kind="TEST_APPROVAL" objectId={instance.testCaseId} snapshotId={revision.id} rowVersion={1} contextKey={instance.id} readOnly onDenied={onDenied}/>
   {refreshing ? <p role="status">正在重新核对实例进度，当前 Attempt 报告暂不可用。</p> : <p>执行进度：{instance.status ? nextStep[instance.status] : '状态未知，请刷新实例网格。'} 结论：{instance.conclusion ?? '尚无完成结论'}。</p>}
   <p>用例批准及其签署绑定上方原修订；运行完成签署绑定确切 Attempt；偏差关闭及其签署绑定偏差关闭修订。PASS 不代表偏差已关闭或本次运行已签署。历史报告请通过网格“进入运行”选择确切历史 Attempt。</p>
   {!refreshing && instance.runId && instance.attemptId && instance.status === 'FINISHED' && <ReportLauncher projectId={projectId} target={{kind:'RUN',runId:instance.runId,attemptId:instance.attemptId}} label="实例最新 Attempt 与关联偏差报告" onDenied={onDenied}/>}
   <ReportLauncher projectId={projectId} target={{kind:'DEFECTS',setId:instance.setId}} label="本测试集真实关联偏差及处理结果" onDenied={onDenied}/>
   <HistoryPanel projectId={projectId} objectType="test_case" objectId={instance.testCaseId}/>
   <details><summary>确切来源标识</summary><p style={{overflowWrap:'anywhere'}}>实例 {instance.id} / 用例 {instance.testCaseId} / 修订 {revision.id}</p></details>
  </>}
 </section>
}
