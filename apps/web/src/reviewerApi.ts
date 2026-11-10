import { requestJson } from './projectAccess'
import type { AccessRequestOptions } from './projectAccess'
export type ReviewerCandidate={principalId:string;displayName:string}
export const reviewerApi={
 async list(projectId:string,search='',cursor:string|null=null,principalId?:string,options?:AccessRequestOptions):Promise<{items:ReviewerCandidate[];nextCursor:string|null}>{
  const params=new URLSearchParams({search,limit:'25'});if(cursor)params.set('cursor',cursor);if(principalId)params.set('principalId',principalId)
  const v=await requestJson(`/api/v1/projects/${encodeURIComponent(projectId)}/reviewer-candidates?${params}`,undefined,options) as {items?:ReviewerCandidate[];nextCursor?:string|null}
  if(!v||!Array.isArray(v.items)||!v.items.every(x=>x&&typeof x.principalId==='string'&&typeof x.displayName==='string')||!(v.nextCursor===null||typeof v.nextCursor==='string'))throw {code:'INVALID_RESPONSE',message:'成员响应无效'}
  return {items:v.items,nextCursor:v.nextCursor}
 }
}
