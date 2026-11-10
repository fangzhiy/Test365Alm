import { businessLabels } from './businessFields'
import { useCallback, useEffect, useRef, useState } from 'react'
import { historyApi } from './historyApi'
import ReviewerPicker from './ReviewerPicker'
import type { HistoryEntry, HistoryObjectType } from './historyApi'
import './HistoryPanel.css'

type Props = { projectId: string; objectType: HistoryObjectType; objectId: string }
const actions: Record<string, string> = {
  'test_case.created': '创建用例', 'test_case.revised': '修改用例',
  'test_case.review.submitted': '提交用例复核', 'test_case.review.approved': '批准用例修订', 'test_case.review.rejected': '拒绝用例修订',
  'defect.created': '创建缺陷', 'defect.revised': '修改缺陷', 'defect.transitioned': '缺陷状态流转', 'defect.retest-started': '开始复测',
  'run.started': '开始运行', 'step.recorded': '记录步骤结果', paused: '暂停运行', resumed: '继续运行', 'run.finished': '完成运行', 'run.retried': '重新运行',
}
const fields: Record<string, string> = {
  title: '标题', description: '描述', preconditions: '前置条件', severity: '严重程度', priority: '优先级', status: '状态', reason: '说明', conclusion: '结论', attempt: '尝试',
  action: '动作', expected: '预期', ordinal: '序号', actualResult: '实际结果',
}
function fieldLabel(field: string) {
  if(field.startsWith('businessFields.')){const key=field.split('.')[1];return (businessLabels[key]??key)+(field.endsWith('.definition')?' 字段定义':'')}
  const step = /^steps\[(\d+)\](?:\.(.+))?$/.exec(field)
  return step ? `步骤 ${step[1]}${step[2] ? ` ${fields[step[2]] ?? step[2]}` : ''}` : fields[field] ?? field
}

// A new object gets a new session so old rows cannot flash before its effect starts.
export default function HistoryPanel(props: Props) {
  return <HistorySession key={JSON.stringify([props.projectId, props.objectType, props.objectId])} {...props}/>
}

function HistorySession({ projectId, objectType, objectId }: Props) {
  const [items, setItems] = useState<HistoryEntry[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const controller = useRef<AbortController | null>(null)
  const load = useCallback((cursor: string | null) => {
    controller.current?.abort()
    const request = new AbortController()
    controller.current = request
    return historyApi.list(projectId, objectType, objectId, cursor, { signal: request.signal }).then(page => {
      if (request.signal.aborted) return
      setItems(old => cursor === null ? page.items : [...old, ...page.items.filter(item => !old.some(existing => existing.id === item.id))])
      setNextCursor(page.nextCursor)
    }, cause => {
      if (request.signal.aborted) return
      const status = (cause as { status?: number } | null)?.status
      if (status === 403 || status === 404 || status === 401) {
        setItems([]); setNextCursor(null)
        setError(status === 401 ? '登录已失效，请重新登录' : '无权查看或对象不存在')
      } else setError('历史加载失败，请重试')
    }).finally(() => {
      if (!request.signal.aborted) setBusy(false)
    })
  }, [projectId, objectType, objectId])
  useEffect(() => {
    void load(null)
    return () => controller.current?.abort()
  }, [load])
  const requestPage = (cursor: string | null) => {
    setBusy(true); setError('')
    void load(cursor)
  }

  return <section aria-label="审计历史" aria-busy={busy} className="history-panel">
    <h4>审计历史</h4><p>最新记录在前</p>
    {items.length > 0 && <div className="history-table-scroll" role="region" aria-label="审计历史表格" tabIndex={0}>
      <table>
        <thead><tr>{['时间', '人', '动作', '字段', '旧值 → 新值'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{items.flatMap(item => (item.changes.length ? item.changes : [null]).map((change, index) => <tr key={`${item.id}:${index}`}>
          <td><time dateTime={item.at}>{new Date(item.at).toLocaleString('zh-CN')}</time></td>
          <td>{item.actor.displayName || item.actor.id}</td>
          <td>{actions[item.action] ?? item.action}</td>
          <td>{change ? fieldLabel(change.field) : '—'}</td>
          <td>{change ? <><span>{change.field==='businessFields.reviewer'?<ReviewerPicker projectId={projectId} value={change.before}/>:change.before ?? '—'}</span><span aria-hidden="true"> → </span><span className="history-sr-only">变为</span><span>{change.field==='businessFields.reviewer'?<ReviewerPicker projectId={projectId} value={change.after}/>:change.after ?? '—'}</span></> : '—'}</td>
        </tr>))}</tbody>
      </table>
    </div>}
    {busy && <p role="status">加载历史中…</p>}
    {error && <p role="alert">{error}</p>}
    {!busy && !error && items.length === 0 && <p role="status">暂无历史</p>}
    {error && <button type="button" disabled={busy} onClick={() => requestPage(nextCursor)}>重试加载历史</button>}
    {!error && nextCursor && <button type="button" disabled={busy} onClick={() => requestPage(nextCursor)}>加载更多历史</button>}
  </section>
}
