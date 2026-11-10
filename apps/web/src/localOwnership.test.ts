import { expect,it } from 'vitest'
import { assertLocalAssets,assertLocalContainer,localManifestMatches } from '../e2e/localOwnership'
import type { LocalManifest } from '../e2e/localOwnership'
const run=`r03-local-${'a'.repeat(32)}`,source='b'.repeat(40)
const m:LocalManifest={mode:'local-owned',run,project:`test365alm-${run}`,source,context:'local',engine:'engine',root:'/snapshot',envFile:'/snapshot/.env.r03',composeFile:'/snapshot/compose.json',services:{postgres:{id:'pg',created:'now',image:'image'},keycloak:{id:'kc',created:'now',image:'image'}}}
const env={R03_RUN_ID:run,R03_COMPOSE_PROJECT:m.project,R03_LOCAL_SOURCE_SHA:source}
const actual={Id:'pg',Created:'now',Image:'image',Config:{Labels:{'com.docker.compose.project':m.project,'com.docker.compose.service':'postgres','test365alm.r03.run':run,'test365alm.local.source':source}}}
it('explicit local provenance accepts only its own project run and source',()=>{
 expect(localManifestMatches(m,env)).toBe(true)
 for(const change of [{R03_RUN_ID:'other'},{R03_LOCAL_SOURCE_SHA:'c'.repeat(40)},{R03_COMPOSE_PROJECT:'demo'},{GITHUB_RUN_ID:'fake'},{GITHUB_RUN_ATTEMPT:'1'}])expect(localManifestMatches(m,{...env,...change})).toBe(false)
})
it('exact container identity plus local daemon and all ownership labels are required',()=>{
 expect(()=>assertLocalContainer(m,'postgres',actual,'local','engine')).not.toThrow()
 for(const change of [{Id:'replaced'},{Created:'recreated'},{Image:'other'}])expect(()=>assertLocalContainer(m,'postgres',{...actual,...change},'local','engine')).toThrow()
 for(const [context,engine]of[['other','engine'],['local','other']])expect(()=>assertLocalContainer(m,'postgres',actual,context,engine)).toThrow()
 for(const key of Object.keys(actual.Config.Labels))expect(()=>assertLocalContainer(m,'postgres',{...actual,Config:{Labels:{...actual.Config.Labels,[key]:'foreign'}}},'local','engine')).toThrow()
})
it('local step evidence requires both owned asset containers and their exact loopback endpoints',()=>{
 const services={...m.services,'asset-store':{id:'s3',created:'now',image:'s3-image'},'asset-scanner':{id:'clamd',created:'now',image:'clamd-image'}}
 const manifest:LocalManifest={...m,assets:'real-owned',services}
 const values={...env,TEST365ALM_ASSETS_ENABLED:'true',R11_S3_PORT:'32001',R11_CLAMD_PORT:'32002',TEST365ALM_ASSETS_S3_ENDPOINT:'http://127.0.0.1:32001',TEST365ALM_ASSETS_CLAMD_HOST:'127.0.0.1',TEST365ALM_ASSETS_CLAMD_PORT:'32002'}
 const containers=Object.fromEntries((['asset-store','asset-scanner'] as const).map((service,index)=>[service,{
  Id:services[service].id,Created:'now',Image:services[service].image,State:{Running:true},
  Config:{Labels:{...actual.Config.Labels,'com.docker.compose.service':service}},
  NetworkSettings:{Ports:{[index?'3310/tcp':'8333/tcp']:[{HostIp:'127.0.0.1',HostPort:index?'32002':'32001'}]}}
 }]))
 expect(()=>assertLocalAssets(manifest,values,containers,'local','engine')).not.toThrow()
 for(const change of [{TEST365ALM_ASSETS_ENABLED:'false'},{TEST365ALM_ASSETS_S3_ENDPOINT:'http://127.0.0.1:8080'},{TEST365ALM_ASSETS_CLAMD_HOST:'192.0.2.1'},{TEST365ALM_ASSETS_CLAMD_PORT:'32003'}])expect(()=>assertLocalAssets(manifest,{...values,...change},containers,'local','engine')).toThrow()
 expect(()=>assertLocalAssets({...manifest,assets:'disabled'},values,containers,'local','engine')).toThrow()
 for(const service of ['asset-store','asset-scanner']){
  const changed=structuredClone(containers)
  changed[service].NetworkSettings.Ports={}
  expect(()=>assertLocalAssets(manifest,values,changed,'local','engine')).toThrow()
  expect(()=>assertLocalAssets(manifest,values,{...containers,[service]:{...containers[service],Id:'foreign'}},'local','engine')).toThrow()
 }
})
