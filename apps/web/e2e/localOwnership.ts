// Pure validation shared by the explicitly local browser harness and offline tests.
export type LocalManifest = {
  mode: 'local-owned'; project: string; run: string; source: string; context: string; engine: string
  root: string; envFile: string; composeFile: string
  services: Record<'postgres'|'keycloak', {id:string; created:string; image:string}>
}
export function localManifestMatches(m: LocalManifest, env: Record<string,string|undefined>): boolean {
  return m?.mode==='local-owned' && !env.GITHUB_RUN_ID && !env.GITHUB_RUN_ATTEMPT
    && /^r03-local-[a-f0-9]{32}$/.test(m.run) && m.project===`test365alm-${m.run}`
    && /^[a-f0-9]{40}$/.test(m.source) && !!m.engine && !!m.context
    && m.project===env.R03_COMPOSE_PROJECT && m.run===env.R03_RUN_ID && m.source===env.R03_LOCAL_SOURCE_SHA
    && !!m.root && !!m.envFile && !!m.composeFile
}
export function assertLocalContainer(m: LocalManifest, service: 'postgres'|'keycloak', actual: {
  Id:string; Created:string; Image:string; Config:{Labels:Record<string,string>}
}, context:string, engine:string): void {
  const expected=m.services[service], labels=actual.Config.Labels
  if(!expected || context!==m.context || engine!==m.engine || actual.Id!==expected.id
    || actual.Created!==expected.created || actual.Image!==expected.image
    || labels['com.docker.compose.project']!==m.project || labels['com.docker.compose.service']!==service
    || labels['test365alm.r03.run']!==m.run || labels['test365alm.local.source']!==m.source)
    throw new Error('Local R03 resource identity or ownership mismatch')
}
