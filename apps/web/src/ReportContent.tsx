import { useState } from 'react'
import { businessLabels } from './businessFields'
import { text, row, time, rate, label } from './reportFormat'
import { object } from './reportsApi'
import type { Json, Report, ReportTarget, Row } from './reportsApi'

const titles = { RUN: '单次运行执行记录报告', SET: '测试集汇总报告', DEFECTS: '偏差清单报告' }

function Facts({ values }: { values: [string, Json | undefined][] }) {
  return <dl className="report-facts">{values.map(([k, v]) =>
    <div key={k} className={text(v).length > 160 ? 'report-wide' : 'report-short'}><dt>{k}</dt><dd>{text(v)}</dd></div>)}</dl>
}
function Trace({ values }: { values: [string, Json | undefined][] }) {
  return <details className="report-trace" open><summary>技术溯源标识</summary><Facts values={values} /></details>
}
function BusinessFields({ value }: { value: Json | undefined }) {
  const entries = Object.entries(row(value))
  return entries.length ? <Facts values={entries.map(([key, v]) => [businessLabels[key] ?? key, object(v) ? v.value : v])} /> : <p>未记录业务字段</p>
}
function SignatureObject({ signature: s, report: r }: { signature: Row; report: Report }) {
  if (s.kind === 'DEVIATION_CLOSURE') {
    const d = r.items.find(i => i.id === s.objectId), revision = row(row(s.contentSnapshot).content).revision
    return <>BUG-{text(d?.display_number)} · 关闭修订 {text(row(revision).revision_no)}<br />
      该签署仅证明原关闭修订；不代表当前偏差状态或其他修订已签署。</>
  }
  if (s.kind === 'TEST_APPROVAL') return <>{text(row(r.facts.manifest).title)} · 修订 {text(row(r.facts.manifest).source_revision_no)}</>
  return <>本次运行 · Attempt {text(row(r.facts.attempt).attempt_no)}</>
}
function Signatures({ report: r }: { report: Report }) {
  const [appendix, setAppendix] = useState(false)
  return <section className={appendix ? 'report-signatures with-appendix' : 'report-signatures'}>
    <h2>模拟签署原记录</h2>
    <label className="no-print"><input type="checkbox" checked={appendix} onChange={e => setAppendix(e.target.checked)} />打印原始签署快照技术附录</label>
    {!r.signatures.length ? <p>未签署</p> : r.signatures.map(s => <article key={text(s.id)}>
      <h3>模拟签署 · {label(s.kind)}</h3><p><SignatureObject signature={s} report={r} /></p>
      <Facts values={[["签署人（原记录）", s.displayName], ["签署时间", time(s.signedAt)], ["含义（原记录）", s.meaning], ["内容摘要", s.contentDigest]]} />
      <Trace values={[["签署记录", s.id], ["签署身份", s.actorId], ["对象", s.objectId], ["确切修订 / Attempt", s.snapshotId]]} />
      <details className="signature-snapshot" open={appendix || undefined}><summary>签署时内容快照（原记录，技术溯源）</summary>
        <p>原始技术材料保留原始时间格式，不改变签署内容。</p><pre>{JSON.stringify(s.contentSnapshot, null, 2)}</pre></details>
    </article>)}
    <p>模拟签署仅覆盖原内容快照，不覆盖本报告新增的偏差状态或其他动态补充信息。本报告不是整份已电子签署的报告。</p>
  </section>
}
function Deviations({ report: r }: { report: Report }) {
  return <>{!r.items.length && <p>当前范围无偏差。</p>}{r.items.map(d => {
    const v = row(d.revision), links = r.links.filter(x => x.defect_id === d.id), retests = r.retests.filter(x => x.defect_id === d.id)
    return <article key={text(d.id)}><h2>BUG-{text(d.display_number)} · {text(v.title)}</h2>
      <Facts values={[["当前修订", v.revision_no], ["状态", label(v.status)], ["严重程度", label(v.severity)], ["优先级", label(v.priority)],
        ["创建时间", time(d.created_at)], ["创建人（当前姓名）", d.creatorCurrentName], ["最近一次解决修订", d.lastResolvedRevisionNo == null ? '未记录' : `修订 ${d.lastResolvedRevisionNo}`],
        ["当前有效解决周期", d.currentResolutionId ? `最近解决修订 ${text(d.lastResolvedRevisionNo)}；可核对该周期复测` : '无；旧复测仅为历史，不表示当前问题已通过复测']]} />
      <Trace values={[["偏差标识", d.id], ["当前修订标识", v.id], ["最近解决修订标识", d.resolved_revision_id], ["有效周期标识", d.currentResolutionId]]} />
      <p>{text(v.description)}</p><h3>已有业务字段（生成时当前修订）</h3><BusinessFields value={v.business_fields} />
      <h3>原始来源及关联</h3>{!links.length && <p>手工创建 / 无执行来源</p>}
      {links.map(l => <section key={text(l.id)}><Facts values={[["关系类型", label(l.kind)],
        ["与本报告关系", r.kind === 'RUN' ? (l.run_id === r.filters.runId && l.attempt_id === r.filters.attemptId ? '源自本次执行' : '其他关联') : '原始来源'],
        ["原预期", l.expected_result], ["原实际结果", l.actual_result], ["原结论", label(l.source_conclusion)]]} />
        <Trace values={[["用例及修订", `${text(l.test_case_id)} / ${text(l.test_revision_id)}`], ["运行 / Attempt / 步骤", `${text(l.run_id)} / ${text(l.attempt_id)} / ${text(l.step_key)}`]]} />
      </section>)}
      <h3>复测记录（按处理周期独立展示）</h3>{!retests.length && <p>无复测记录</p>}
      {retests.map((rt, index) => <section key={`${text(rt.id)}:${index}`}><Facts values={[
        ["周期归属", d.currentResolutionId && rt.resolved_revision_id === d.currentResolutionId ? '当前有效解决周期的复测记录' : '历史解决周期的复测记录；不证明当前周期通过'],
        ["与本报告关系", r.kind === 'RUN' && rt.run_id === r.filters.runId && rt.attempt_id === r.filters.attemptId ? '本次执行用于复测' : '其他复测'],
        ["Attempt 状态 / 结论", `${label(rt.status)} / ${label(rt.conclusion)}`], ["关联记录时间", time(rt.created_at)],
        ["对应步骤实际结果", rt.stepActual], ["对应步骤结论", label(rt.stepConclusion)], ["执行时间", time(rt.stepExecutedAt)]]} />
        <Trace values={[["处理周期 resolvedRevisionId", rt.resolved_revision_id], ["运行 / Attempt", `${text(rt.run_id)} / ${text(rt.attempt_id)}`], ["复测记录人员标识", rt.created_by]]} />
      </section>)}
    </article>
  })}</>
}
function RunReport({ report: r }: { report: Report }) {
  const f = r.facts, m = row(f.manifest), a = row(f.attempt)
  return <><h2>{text(m.title)} · 修订 {text(m.source_revision_no)}</h2>
    <Facts values={[["测试集", f.testSetName], ["Attempt 序号", a.attempt_no], ["冻结负责人", m.assignee_name], ["开始记录人（当前姓名）", f.starterCurrentName],
      ["开始时间", time(a.started_at)], ["结束时间", time(a.finished_at)], ["本次结论", label(a.conclusion)]]} />
    <Trace values={[["测试实例", m.test_instance_id], ["项目 / Run / Attempt", `${r.projectId} / ${text(row(f.run).id)} / ${text(a.id)}`],
      ["用例 / 确切修订", `${text(m.source_test_case_id)} / ${text(m.source_revision_id)}`], ["开始记录人标识", a.started_by]]} />
    <p>开始至结束跨度包含暂停，不代表净工时。</p><h3>描述</h3><p>{text(m.description)}</p><h3>前置条件</h3><p>{text(m.preconditions)}</p>
    <h3>测试目的及业务字段（固定原修订）</h3><BusinessFields value={f.businessFields} />
    <h2>步骤执行事实</h2>{r.steps.map(s => <article key={text(s.id)}><h3>步骤 {text(s.ordinal)}</h3>
      <Facts values={[["操作", s.action], ["预期结果", s.expected], ["实际结果", s.actual_result], ["步骤结论", label(s.conclusion)], ["实际执行时间", time(s.executed_at)],
        ["最后修改时间（不是执行时间）", time(s.updated_at)], ["记录人（当前姓名）", s.recorderCurrentName]]} />
      <Trace values={[["步骤 / 记录人标识", `${text(s.id)} / ${text(s.updated_by)}`]]} />
      <h4>固定版本附件</h4>{r.attachments.filter(x => x.run_step_id === s.id).map(x => <div key={text(x.id)}>
        <a href={`/api/v1/projects/${r.projectId}/assets/${text(x.asset_id)}/revisions/${text(x.asset_revision_id)}/download`}>{text(x.filename)}</a>
        <Facts values={[["版本", x.revision_no], ["字节数", x.size], ["SHA-256", x.sha256]]} /><Trace values={[["固定版本标识", x.asset_revision_id]]} />
      </div>)}{!r.attachments.some(x => x.run_step_id === s.id) && <p>无附件</p>}
    </article>)}
    <h2>原用例修订复核事件</h2>{!r.reviews.length && <p>无复核记录</p>}{r.reviews.map(e => <Facts key={text(e.id)} values={[
      ["动作 / 状态", `${label(e.action)} / ${label(e.state)}`], ["实际事件时间", time(e.occurred_at)],
      ["时间关系", typeof e.occurred_at === 'string' && typeof a.finished_at === 'string' && Date.parse(e.occurred_at) > Date.parse(a.finished_at) ? '晚于执行结束，不代表执行前批准' : '按事件时间理解'],
      ["指定复核人（当前姓名）", e.reviewerCurrentName], ["操作人（当前姓名）", e.actorCurrentName]]} />)}
    <h2>相关偏差（生成时状态）</h2><Deviations report={r} />
    <p>{r.signatures.some(s => s.kind === 'RUN_COMPLETION') ? '本次 Attempt 已模拟签署' : '本次 Attempt 未签署'}</p>
    <p>{r.signatures.some(s => s.kind === 'TEST_APPROVAL') ? '原用例修订已有批准模拟签署' : '原用例修订无批准模拟签署'}</p>
    <Signatures report={r} />
  </>
}
function SetReport({ report: r, onTarget }: { report: Report; onTarget?: (t: ReportTarget) => void }) {
  const f = r.facts, counts = row(f.counts)
  return <><h2>{text(row(f.testSet).name)}</h2><p>{text(row(f.testSet).description)}</p>
    <section className="report-statistics"><Facts values={[["测试集实例总数", f.totalInstances], ["当前匹配实例数", counts.matched],
      ...['NOT_RUN','RUNNING','PAUSED','PASS','FAIL','BLOCKED'].map((k): [string, Json] => [label(k), row(counts.categories)[k]]), ["完成率", rate(counts.completionRate)], ["通过率", rate(counts.passRate)]]} /></section>
    <p>实例最新执行结果：按 started_at、runId、attemptId 降序选择一次，再筛选。每实例仅归入一类；同用例不同实例分别计数。
      完成率 = (PASS + FAIL + BLOCKED) / 匹配实例数；通过率 = PASS / 已完成数；零分母显示 —。</p>
    <Facts values={[["其他历史活动 Attempt 数（匹配实例，不混入分类）", f.otherActiveAttempts], ["本测试集相关偏差数（全测试集，不受实例状态筛选影响）", f.relatedDefects]]} />
    <button className="no-print" onClick={() => onTarget?.({ kind: 'DEFECTS', setId: text(row(f.testSet).id) })}>查看测试集偏差报告</button>
    <table><thead><tr>{['用例 / 修订', '负责人（当前指派）', '最新执行', '状态 / 结论', '执行时间'].map(x => <th key={x}>{x}</th>)}</tr></thead>
      <tbody>{r.items.map(i => <tr key={text(i.id)}><td>{text(i.title)}<br />修订 {text(i.revision_no)}<Trace values={[["实例", i.id], ["用例修订", i.test_revision_id]]} /></td>
        <td>{text(i.assignee_name)}</td><td>{i.attempt_id ? `Attempt ${text(i.attempt_no)}` : '尚无执行'}
          <Trace values={[["Run / Attempt", `${text(i.run_id)} / ${text(i.attempt_id)}`]]} />
          {['PASS', 'FAIL', 'BLOCKED'].includes(String(i.status)) && <button className="no-print" onClick={() => onTarget?.({ kind: 'RUN', runId: text(i.run_id), attemptId: text(i.attempt_id) })}>查看运行报告</button>}</td>
        <td>{label(i.status)} / {label(i.conclusion)}</td><td>{time(i.started_at)}<br />{time(i.finished_at)}</td></tr>)}</tbody></table>
    {!r.items.length && <p>当前筛选无实例。</p>}</>
}
export function ReportBody({ report: r, onTarget }: { report: Report; onTarget?: (t: ReportTarget) => void }) {
  const setName = r.kind === 'SET' ? row(r.facts.testSet).name : r.facts.testSetName
  const filters = [r.filters.status && `状态：${label(r.filters.status)}`, r.filters.severity && `严重程度：${label(r.filters.severity)}`,
    r.filters.q && `标题包含：${r.filters.q}`].filter(Boolean).join('；') || '全部状态，无关键词限制'
  return <><header><p>Test365Alm · 只读业务报告</p><h1>{titles[r.kind]}</h1>
    <Facts values={[["项目", r.projectName], ["范围", setName ? `测试集：${text(setName)}` : '项目范围'], ["筛选条件", filters], ["生成时间", time(r.generatedAt)]]} />
    <p>固定执行事实与生成时读取的项目名称、当前偏差状态分开展示。重新获取可能反映新的补充状态；未持久化报告档案。</p></header>
    {r.kind === 'RUN' && <RunReport report={r} />}
    {r.kind === 'SET' && <SetReport report={r} onTarget={onTarget} />}
    {r.kind === 'DEFECTS' && <><p>匹配偏差数量：{text(r.facts.matched)}；手工偏差保留在项目范围，测试集范围只含真实来源或复测关系。一个偏差只计一次。</p><Deviations report={r} /><Signatures report={r} /></>}
    <footer>数据完整加载 · {r.version} · 所有业务时间 UTC · 历史空值不补造。技术标识用于溯源。</footer></>
}
