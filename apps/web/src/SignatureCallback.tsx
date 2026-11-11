import { useEffect } from 'react'
export default function SignatureCallback({status}:{status:'reauthenticated'|'failed'}){
 useEffect(()=>{if(window.opener){window.opener.postMessage({type:'test365alm-signature',status},window.location.origin);window.close()}},[status])
 return <main><h1>{status==='reauthenticated'?'身份再认证已返回':'身份再认证失败'}</h1><p>请返回原签署窗口确认。签署是否成功以服务端记录为准。</p></main>
}
