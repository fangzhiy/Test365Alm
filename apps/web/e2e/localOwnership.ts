// Pure validation shared by the explicitly local browser harness and offline tests.
export type LocalService = 'postgres'|'keycloak'|'asset-store'|'asset-scanner'
export type LocalManifest = {
  mode: 'local-owned'; project: string; run: string; source: string; context: string; engine: string
  root: string; envFile: string; composeFile: string
  assets?: 'real-owned'|'disabled'
  services: Partial<Record<LocalService, {id:string; created:string; image:string}>> & Record<'postgres'|'keycloak', {id:string; created:string; image:string}>
}
export function localManifestMatches(m: LocalManifest, env: Record<string,string|undefined>): boolean {
  return m?.mode==='local-owned' && !env.GITHUB_RUN_ID && !env.GITHUB_RUN_ATTEMPT
    && /^r03-local-[a-f0-9]{32}$/.test(m.run) && m.project===`test365alm-${m.run}`
    && /^[a-f0-9]{40}$/.test(m.source) && !!m.engine && !!m.context
    && m.project===env.R03_COMPOSE_PROJECT && m.run===env.R03_RUN_ID && m.source===env.R03_LOCAL_SOURCE_SHA
    && !!m.root && !!m.envFile && !!m.composeFile
}
export function assertLocalContainer(m: LocalManifest, service: LocalService, actual: {
  Id:string; Created:string; Image:string; Config:{Labels:Record<string,string>}
}, context:string, engine:string): void {
  const expected=m.services[service], labels=actual.Config.Labels
  if(!expected || context!==m.context || engine!==m.engine || actual.Id!==expected.id
    || actual.Created!==expected.created || actual.Image!==expected.image
    || labels['com.docker.compose.project']!==m.project || labels['com.docker.compose.service']!==service
    || labels['test365alm.r03.run']!==m.run || labels['test365alm.local.source']!==m.source)
    throw new Error('Local R03 resource identity or ownership mismatch')
}
type AssetContainer = {
 Id:string; Created:string; Image:string; Config:{Labels:Record<string,string>}
 State:{Running:boolean}; NetworkSettings:{Ports:Record<string,{HostIp:string;HostPort:string}[]|null>}
}
export function assertLocalAssets(m:LocalManifest,env:Record<string,string|undefined>,containers:Record<string,AssetContainer>,context:string,engine:string):void{
 if(!localManifestMatches(m,env)||m.assets!=='real-owned'||env.TEST365ALM_ASSETS_ENABLED!=='true'
   ||env.TEST365ALM_ASSETS_S3_ENDPOINT!==`http://127.0.0.1:${env.R11_S3_PORT}`
   ||env.TEST365ALM_ASSETS_CLAMD_HOST!=='127.0.0.1'||env.TEST365ALM_ASSETS_CLAMD_PORT!==env.R11_CLAMD_PORT)
   throw new Error('Local step evidence requires explicitly owned real asset services')
 for(const [service,privatePort,port]of[['asset-store','8333/tcp',env.R11_S3_PORT],['asset-scanner','3310/tcp',env.R11_CLAMD_PORT]] as const){
  const actual=containers[service]
  if(!actual||!port||!/^\d+$/.test(port)||+port<1||+port>65535||[5173,8080,15173,18083].includes(+port))throw new Error('Invalid owned asset endpoint')
  assertLocalContainer(m,service,actual,context,engine)
  const bindings=actual.NetworkSettings.Ports[privatePort]
  if(!actual.State.Running||bindings?.length!==1||bindings[0].HostIp!=='127.0.0.1'||bindings[0].HostPort!==port)
   throw new Error('Owned asset service binding changed')
 }
}
