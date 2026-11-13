import { useEffect, useRef, useState } from 'react'
import { readRunHistory, type RunHistory } from './overviewData'
import { reportScopeDenied } from './reportsApi'

export function useRunHistory(project:string,setId:string|undefined,generation:string,onDenied:()=>void) {
  const [result,setResult]=useState<{generation:string;data:RunHistory[]}|null>(null)
  const [failure,setFailure]=useState<{generation:string;message:string}|null>(null)
  const callback=useRef(onDenied)
  useEffect(()=>{callback.current=onDenied},[onDenied])
  useEffect(()=>{
    if(!setId)return
    const c=new AbortController();let current=true
    const timer=window.setTimeout(()=>{if(current){current=false;c.abort();setFailure({generation,message:'历史次数读取超时'})}} ,15000)
    void readRunHistory(project,setId,c.signal).then(data=>{if(current)setResult({generation,data})}).catch(e=>{
      if(!current)return
      if(reportScopeDenied(e))callback.current()
      else setFailure({generation,message:e instanceof Error?e.message:'历史次数暂不可用'})
    }).finally(()=>{window.clearTimeout(timer)})
    return ()=>{current=false;c.abort();window.clearTimeout(timer)}
  },[project,setId,generation])
  return {history:result?.generation===generation?result.data:null,error:failure?.generation===generation?failure.message:''}
}
