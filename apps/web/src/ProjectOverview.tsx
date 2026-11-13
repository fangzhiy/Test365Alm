import { useEffect, useRef, useState } from 'react'
import { Card, Progress, Tag, Button, Empty, Spin } from 'antd'
import { ReadOutlined, CheckCircleOutlined, ExperimentOutlined, BugOutlined } from '@ant-design/icons'
import type { ProjectAccess } from './projectAccess'
import { readOverview, type OverviewData } from './overviewData'
import { reportScopeDenied } from './reportsApi'
import { labels } from './reportFormat'

type Props={projectId:string;access:ProjectAccess|null;onDenied:()=>void;onNavigate:(module:string)=>void}
export default function ProjectOverview(props:Props) {
  return <OverviewSession key={JSON.stringify([props.projectId,props.access])} {...props}/>
}
function OverviewSession({projectId,access,onDenied,onNavigate}:Props) {
  const [data,setData]=useState<OverviewData|null>(null),[error,setError]=useState(''),[loading,setLoading]=useState(true),[revision,setRevision]=useState(0)
  const denied=useRef(onDenied); useEffect(()=>{denied.current=onDenied},[onDenied])
  useEffect(()=>{
    if(!projectId || !access || access.projectId!==projectId)return
    let current=true
    const c=new AbortController()
    const timeout=window.setTimeout(()=>{if(current){current=false;c.abort();setData(null);setLoading(false);setError('概览读取超时，请重试')}},15000)
    void readOverview(projectId,c.signal).then(d=>{if(current)setData(d)}).catch(e=>{
      if(!current)return
      setData(null)
      if(reportScopeDenied(e))denied.current()
      else setError(e instanceof Error?e.message:'概览读取失败，请稍后重试')
    }).finally(()=>{if(current){setLoading(false);window.clearTimeout(timeout)}})
    return ()=>{current=false;c.abort();window.clearTimeout(timeout)}
  },[projectId,access,revision])
  if(!projectId || !access || access.projectId!==projectId)return <Card><Empty description="请先加载访问范围并选择项目" /></Card>
  const done=data?data.counts.PASS+data.counts.FAIL+data.counts.BLOCKED:0
  const passed=done&&data?Math.round(data.counts.PASS/done*1000)/10:null
  const attempts=data?.history.flatMap(h=>h.attempts)
  return <section className="project-overview" aria-label="项目概览数据">
    <div className="overview-toolbar"><span>授权只读查询 · 实例最新执行结果与历史尝试分开统计</span><Button loading={loading} onClick={()=>{setData(null);setError('');setLoading(true);setRevision(v=>v+1)}}>刷新概览</Button></div>
    {loading && <div role="status"><Spin /> 正在核验项目数据…</div>}
    {error && <p role="alert" className="access-error">{error}</p>}
    {data && <>
      <div className="overview-stats">
        <Card><ReadOutlined /><p>测试用例</p><strong>{data.cases.length}</strong><small>{data.reviews?`当前修订已复核批准 ${data.reviews.filter(r=>r.state==='APPROVED').length} · 待复核 ${data.reviews.filter(r=>r.state==='SUBMITTED').length}`:'超过 100 条，复核状态请按用例查看'}</small></Card>
        <Card><CheckCircleOutlined /><p>执行通过率</p><strong>{passed===null?'—':`${passed}%`}</strong><small>通过 {data.counts.PASS} / 已完成 {done} · 阻塞 {data.counts.BLOCKED}</small></Card>
        <Card><ExperimentOutlined /><p>运行次数（含复测）</p><strong>{attempts?.length}</strong><small>{data.history.length} 个 Run · 每个 Attempt 独立计数</small></Card>
        <Card><BugOutlined /><p>偏差</p><strong>{data.defects.length}</strong><small>未关闭 {data.defects.filter(d=>d.currentRevision.status!=='CLOSED').length} · 已关闭 {data.defects.filter(d=>d.currentRevision.status==='CLOSED').length}</small></Card>
      </div>
      <div className="overview-columns"><Card title="按用例的执行进度" extra={<Button type="link" onClick={()=>onNavigate('lab')}>进入测试实验室</Button>}>
        <p className="scope-note">同一用例的多个实例分别计数；完成率 = 完成实例 / 实例；通过率 = PASS / 已完成。零分母显示 —。</p>
        <div className="overview-table"><table aria-label="用例执行进度"><thead><tr><th>用例 / 当前修订复核</th><th>实例</th><th>完成进度</th><th>最新结果</th></tr></thead><tbody>{data.cases.map(c=>{
          const rows=data.instances.filter(i=>i.testCaseId===c.id),finished=rows.filter(i=>i.status==='FINISHED').length
          const review=data.reviews?.find(r=>r.testCaseId===c.id)
          return <tr key={c.id}><th scope="row"><span>{c.displayNumber} · {c.title}</span><small>当前 R{c.revisionNumber} · {review?labels[review.state]:'未汇总'}（实例仍绑定各自修订）</small></th><td>{rows.length}</td><td>{rows.length?<Progress percent={Math.round(finished/rows.length*100)} size="small"/>:'—'}</td><td>{rows.length?rows.map(i=><Tag key={i.id} color={i.conclusion==='PASS'?'green':i.conclusion==='FAIL'?'red':i.status==='FINISHED'?'gold':'blue'}>{labels[i.status==='FINISHED'?(i.conclusion??''):i.status??'NOT_RUN']}</Tag>):'未加入测试集'}</td></tr>
        })}</tbody></table></div>
      </Card><div className="overview-side"><Card title="最近动态"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无项目级动态接口"/><p>对象历史请从用例、运行及偏差的“历史”入口查看。</p></Card><Card title="工作入口"><Button block onClick={()=>onNavigate('plan')}>测试计划 · 用例与复核</Button><Button block onClick={()=>onNavigate('defects')}>偏差管理 · 处理与复测</Button><p>运行报告与测试集汇总位于测试实验室；偏差清单位于偏差管理。</p></Card></div></div>
      <p className="scope-note">同步汇总上限：用例/偏差/实例各 1000，测试集/Run 各 100，每 Run 最多 1000 Attempt；超限不展示部分统计。数据为读取时状态，跨接口读取不构成事务快照。</p>
    </>}
  </section>
}
