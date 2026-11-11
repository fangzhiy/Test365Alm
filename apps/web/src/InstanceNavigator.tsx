import { useEffect, useRef, useState } from 'react'
import { testsApi, type TestRevision } from './tests'
import type { TestInstance } from './testExecution'
import ReviewPanel from './ReviewPanel'
import SignaturePanel from './SignaturePanel'
import HistoryPanel from './HistoryPanel'
import ReportLauncher from './Reports'
import { reportScopeDenied } from './reportsApi'

type Props = { projectId: string; instance: TestInstance; onDenied: () => void; onClose: () => void }

// Read-only navigation for the exact saved revision. Commands remain in the
// original case/execution/defect workbenches, with their existing authorization.
export default function InstanceNavigator(props: Props) {
 return <InstanceSession key={`${props.projectId}:${props.instance.id}:${props.instance.testRevisionId}`} {...props}/>
}
function InstanceSession({ projectId, instance, onDenied, onClose }: Props) {
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
   <p>执行进度：{instance.status ?? '未知'} / {instance.conclusion ?? '尚无完成结论'}。通过网格“进入运行”查看历史 Attempt，完成后可打开所选 Attempt 报告及其真实关联偏差。运行签署与用例批准、偏差关闭签署分别核对。</p>
   {instance.runId && instance.attemptId && instance.status === 'FINISHED' && <ReportLauncher projectId={projectId} target={{kind:'RUN',runId:instance.runId,attemptId:instance.attemptId}} label="实例最新 Attempt 与关联偏差报告" onDenied={onDenied}/>}
   <ReportLauncher projectId={projectId} target={{kind:'DEFECTS',setId:instance.setId}} label="本测试集真实关联偏差及处理结果" onDenied={onDenied}/>
   <HistoryPanel projectId={projectId} objectType="test_case" objectId={instance.testCaseId}/>
   <details><summary>确切来源标识</summary><p style={{overflowWrap:'anywhere'}}>实例 {instance.id} / 用例 {instance.testCaseId} / 修订 {revision.id}</p></details>
  </>}
 </section>
}
