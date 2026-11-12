import type { APIRequestContext } from '@playwright/test'

// Observe the real APIRequestContext send boundary, not page network events.
// All original methods still execute and return their genuine server response.
// Only method and pathname are retained; no headers, bodies or query secrets.
export function captureApiWrites(request:APIRequestContext, root:string) {
 const writes:string[]=[]
 const restores:Array<()=>void>=[]
 for(const method of ['fetch','post','put','patch','delete'] as const){
  const original=request[method]
  const descriptor=Object.getOwnPropertyDescriptor(request,method)
  Object.defineProperty(request,method,{configurable:true,writable:true,value:(...args:Parameters<APIRequestContext['fetch']>)=>{
   const [url,options]=args
   const verb=(options?.method ?? (method==='fetch'?'GET':method)).toUpperCase()
   const pathname=new URL(typeof url==='string'?url:url.url(),'http://local.invalid').pathname
   if(['POST','PUT','PATCH','DELETE'].includes(verb) && (pathname===root || pathname.startsWith(root+'/')))writes.push(`${verb} ${pathname}`)
   return Reflect.apply(original,request,args)
  }})
  restores.push(()=>{if(descriptor)Object.defineProperty(request,method,descriptor);else Reflect.deleteProperty(request,method)})
 }
 return {writes,clear:()=>{writes.length=0},stop:()=>{for(const restore of restores)restore()}}
}
