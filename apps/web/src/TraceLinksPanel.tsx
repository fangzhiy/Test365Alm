import { useEffect, useState } from 'react'
import { coreApi } from './coreApi'
import type { Coverage, Link } from './coreApi'
import type { ProjectAccess } from './projectAccess'
import { testsApi } from './tests'
import type { TestCase } from './tests'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'

export default function TraceLinksPanel({ projectId, requirementId, access }: { projectId: string; requirementId: string; access: ProjectAccess | null }) {
  if (!access?.permissions.includes('requirement:read') || access.projectId !== projectId) return null
  return <Links key={`${projectId}:${requirementId}:${access.principalId}:${access.permissions.join(',')}`} projectId={projectId} requirementId={requirementId} write={access.permissions.includes('requirement:update') && access.permissions.includes('test:create')} />
}
function Links({ projectId, requirementId, write }: { projectId: string; requirementId: string; write: boolean }) {
  const { run, busy, message, setMessage, denied } = useCoreRequest()
  const [items, setItems] = useState<Link[]>([]), [cursor, setCursor] = useState<string | null>(null), [coverage, setCoverage] = useState<Coverage | null>(null)
  const [tests, setTests] = useState<TestCase[]>([]), [testCursor, setTestCursor] = useState<string | null>(null), [test, setTest] = useState('')
  const [intent, setIntent] = useState<{ key: string; test?: string; link?: Link } | null>(null)
  useEffect(() => { void run(signal => Promise.all([coreApi.links(projectId, requirementId, null, { signal }), coreApi.coverage(projectId, requirementId, { signal }), testsApi.list(projectId, '', null, { signal })]), ([page, stats, cases]) => { setItems(page.items); setCursor(page.nextCursor); setCoverage(stats); setTests(cases.items); setTestCursor(cases.nextCursor ?? null) }) }, [projectId, requirementId, run])
  const persist = async (old?: Link) => {
    const command = intent ?? { key: newIdempotencyKey(), ...(old ? { link: old } : { test }) }; setIntent(command); setCoverage(null)
    const result = await run(signal => command.link ? coreApi.invalidate(projectId, command.link, { signal, idempotencyKey: command.key }) : coreApi.createLink(projectId, requirementId, command.test!, { signal, idempotencyKey: command.key }), l => { setItems(rows => [l, ...rows.filter(x => x.id !== l.id)]); setMessage('关联已保存；请刷新覆盖统计') })
    if (result === 'ok' || result === 'rejected') setIntent(null)
  }
  const locked = busy || intent !== null
  if (denied) return <section aria-label="需求测试追踪"><p role="alert">该追踪范围不可访问；请重新加载项目权限。{message}</p></section>
  return <section aria-label="需求测试追踪"><h4>需求 → 测试追踪</h4><p>关联不等于执行通过。只有当前绑定修订、最新已完成 PASS 且无活动尝试才计入覆盖。</p>
    <button disabled={locked} onClick={() => { setCoverage(null); void run(signal => coreApi.coverage(projectId, requirementId, { signal }), setCoverage) }}>刷新覆盖统计</button>
    {coverage ? <p>已关联 {coverage.linkedTests} · 活动 {coverage.activeTests} · PASS 覆盖 {coverage.passedTests}/{coverage.linkedTests}（当前需求）</p> : <p>覆盖统计尚未确认</p>}
    <ul>{items.map(l => <li key={l.id}><code>{l.testCaseId}</code> · {l.state} · v{l.rowVersion}{write && l.state === 'ACTIVE' && <button disabled={locked} onClick={() => void persist(l)}>失效关联</button>}</li>)}</ul>
    {cursor && <button disabled={locked} onClick={() => void run(signal => coreApi.links(projectId, requirementId, cursor, { signal }), page => { setItems(old => [...old, ...page.items]); setCursor(page.nextCursor) })}>更多关联</button>}
    {write && <fieldset disabled={locked}><legend>关联本项目已保存用例</legend><label>关联测试用例<select value={test} onChange={e => setTest(e.target.value)}><option value="">请选择</option>{tests.map(t => <option value={t.id} key={t.id}>{t.title}</option>)}</select></label>
      {testCursor && <button onClick={() => void run(signal => testsApi.list(projectId, '', testCursor, { signal }), page => { setTests(old => [...old, ...page.items]); setTestCursor(page.nextCursor ?? null) })}>更多可关联用例</button>}
      <button disabled={!test} onClick={() => void persist()}>建立追踪关联</button></fieldset>}
    {intent && !busy && <button onClick={() => void persist()}>重试原关联意图</button>}
    <p role="status">{busy ? '读取或保存追踪…' : message}</p>
  </section>
}
