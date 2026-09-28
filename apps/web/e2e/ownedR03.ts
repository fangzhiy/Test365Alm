import { execFileSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const project = process.env.R03_COMPOSE_PROJECT || ''
const runId = process.env.R03_RUN_ID || ''

export function isOwnedCiRun(): boolean {
  return Boolean(process.env.GITHUB_RUN_ID && process.env.GITHUB_RUN_ATTEMPT
    && project === `test365alm-r03-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`
    && /^[0-9a-f]{32}$/.test(runId))
}

function docker(args: string[]): string {
  return execFileSync('docker', args, { cwd: root, encoding: 'utf8', timeout: 20_000, maxBuffer: 1024 * 1024 }).trim()
}

function compose(args: string[]): string {
  return docker(['compose', '--env-file', '.env.r03', '-f', 'compose.r03.yaml', '-p', project, ...args])
}

let initialEngine: string | undefined

function ownedContainer(service: 'postgres' | 'keycloak'): string {
  if (!isOwnedCiRun()) throw new Error('Only this CI run may fault-inject its R03 stack')
  const engine = docker(['info', '--format', '{{.ID}}'])
  if (initialEngine && initialEngine !== engine) throw new Error('Docker engine changed during R03 test')
  initialEngine = engine
  const id = compose(['ps', '-aq', service])
  if (!/^[0-9a-f]{12,64}$/.test(id)) throw new Error('Owned R03 container was not found')
  const labels = JSON.parse(docker(['inspect', '--format', '{{json .Config.Labels}}', id])) as Record<string, string>
  if (labels['com.docker.compose.project'] !== project || labels['com.docker.compose.service'] !== service
    || labels['test365alm.r03.run'] !== runId) throw new Error('R03 container ownership mismatch')
  return id
}

export function stopKeycloak(): void {
  ownedContainer('keycloak')
  compose(['stop', '--timeout', '10', 'keycloak'])
}

export function startKeycloak(): void {
  ownedContainer('keycloak')
  compose(['start', 'keycloak'])
}

export function setPrincipalDisabled(id: string, disabled: boolean): void {
  if (!/^[0-9a-f-]{36}$/.test(id)) throw new Error('Invalid principal ID')
  ownedContainer('postgres')
  const db = process.env.R03_DB_NAME || ''
  const user = process.env.R03_MIGRATION_USER || ''
  if (!/^[a-z][a-z0-9_]*$/.test(db) || !/^[a-z][a-z0-9_]*$/.test(user)) {
    throw new Error('Invalid disposable database identity')
  }
  const value = disabled ? 'CURRENT_TIMESTAMP' : 'NULL'
  const result = compose(['exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
    '-U', user, '-d', db, '-At', '-c', `WITH changed AS (UPDATE principal SET disabled_at = ${value} WHERE id = '${id}' RETURNING id) SELECT id FROM changed;`])
  if (result !== id) throw new Error('Expected exactly one disposable principal to change')
}
