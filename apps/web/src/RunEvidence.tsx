import { useEffect, useRef, useState } from 'react'
import { Tag } from 'antd'
import { loadReport, reportScopeDenied, type Report } from './reportsApi'
import { row, text, label, time } from './reportFormat'

type Props={projectId:string;runId:string;attemptId:string;onDenied:()=>void}
// Read only the selected, finished Attempt. Never infer signing from a PASS.
export default function RunEvidence(props:Props) {
  const [report,setReport]=useState<Report|null>(null),[error,setError]=useState(''),[load,setLoad]=useState(false)
  const denied=useRef(props.onDenied)
  useEffect(()=>{denied.current=props.onDenied},[props.onDenied])
  useEffect(()=>{
    if(!load)return
    let current=true;const c=new AbortController()
    const timer=window.setTimeout(()=>{if(current){current=false;c.abort();setReport(null);setError('关联事实读取超时')}} ,15000)
    void loadReport(props.projectId,{kind:'RUN',runId:props.runId,attemptId:props.attemptId},{},c.signal).then(r=>{if(current)setReport(r)}).catch(e=>{
      if(!current)return
      setReport(null)
      if(reportScopeDenied(e))denied.current()
      else setError('关联事实读取失败；不推断复核、偏差或签署状态')
    }).finally(()=>window.clearTimeout(timer))
    return ()=>{current=false;c.abort();window.clearTimeout(timer)}
  },[load,props.projectId,props.runId,props.attemptId])
  return <section aria-label="所选尝试关联事实"><h3>关联偏差与签署</h3><p className="scope-note">仅所选 Attempt 及原用例修订；偏差显示当前处理状态。</p>
    {!load && <button type="button" onClick={()=>setLoad(true)}>核验关联事实</button>}
    {load && !report && !error && <p role="status">正在读取关联事实…</p>}{error && <p role="alert">{error}</p>}
    {report && <>
      <p>原修订复核：{report.reviews.length?label(report.reviews.at(-1)?.action):'未记录'}</p>
      <p>{report.signatures.some(s=>s.kind==='RUN_COMPLETION')?'本次 Attempt 已模拟签署':'本次 Attempt 未签署'}</p>
      <p>{report.signatures.some(s=>s.kind==='TEST_APPROVAL')?'原用例修订已有批准模拟签署':'原用例修订无批准模拟签署'}</p>
      {!report.items.length && <p>无关联偏差</p>}
      {report.items.map(d=><article key={text(d.id)}><strong>BUG-{text(d.display_number)} · {text(row(d.revision).title)}</strong><p><Tag color={row(d.revision).status==='CLOSED'?'green':'gold'}>{label(row(d.revision).status)}</Tag></p><p>来源关系 {report.links.filter(l=>l.defect_id===d.id && l.attempt_id===props.attemptId).length} · 本次复测关系 {report.retests.filter(t=>t.defect_id===d.id && t.attempt_id===props.attemptId).length}</p><p>{d.currentResolutionId?'存在当前有效解决周期':'无有效当前周期；旧 PASS 仅为历史'}</p></article>)}
      <h3>模拟签署原记录</h3><Tag color="purple">模拟提供方 · 非正式电子签名认证</Tag>
      {!report.signatures.length && <p>未签署</p>}
      {report.signatures.map(s=><article key={text(s.id)}><strong>{label(s.kind)}</strong><p>{text(s.displayName)} · {time(s.signedAt)}</p><p>{s.kind==='RUN_COMPLETION'?'所选 Attempt':s.kind==='TEST_APPROVAL'?'原用例修订':'偏差历史关闭修订（不代表当前状态）'}</p><details><summary>确切对象与摘要</summary><p>{text(s.objectId)} / {text(s.snapshotId)}</p><p>{text(s.contentDigest)}</p></details></article>)}
    </>}
  </section>
}
