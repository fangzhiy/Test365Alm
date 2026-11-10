/// <reference types="node" />
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Browser, Page } from '@playwright/test'
const required = 'real Keycloak UI designated reviewer rejects approves and new revision requires fresh review'
type Callback = (fixtures: { page: Page; browser: Browser }) => Promise<void>
const harness = vi.hoisted(() => ({
  registered: [] as { name: string; callback: Callback }[], read: vi.fn(), write: vi.fn(), mkdir: vi.fn(), docker: vi.fn(),
  refused: new Error('ownership rejected before UI'), reached: new Error('owned UI execution boundary'),
  skip: vi.fn(),
}))
vi.mock('@playwright/test', () => ({
  expect: vi.fn(), test: Object.assign((name: string, callback: Callback) => { harness.registered.push({ name, callback }) }, {
    skip: harness.skip, setTimeout: vi.fn(), info: () => ({ title: 'owned review probe' }),
    step: async (_name: string, action: () => Promise<unknown>) => action(),
  }),
}))
vi.mock('node:fs', () => { const fs = { readFileSync: harness.read }; return { ...fs, default: fs } })
vi.mock('node:fs/promises', () => { const fs = { mkdir: harness.mkdir, writeFile: harness.write }; return { ...fs, default: fs } })
vi.mock('node:child_process', () => { const process = { execFileSync: harness.docker }; return { ...process, default: process } })
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const localRun = `r03-local-${'a'.repeat(32)}`, source = 'b'.repeat(40)
const manifest = () => ({ mode: 'local-owned', run: localRun, project: `test365alm-${localRun}`, source,
  context: 'desktop-linux', engine: 'fixture-engine', root, envFile: resolve(root, '.env.r03'),
  composeFile: resolve(root, 'local-evidence/local-browser-compose.json'), services: {} })
beforeEach(() => {
  vi.resetModules(); vi.resetAllMocks(); harness.registered.length = 0
  for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'R03_LOCAL_MANIFEST', 'R03_RUN_ID', 'R03_COMPOSE_PROJECT', 'R03_LOCAL_SOURCE_SHA']) vi.stubEnv(key, undefined)
  for (const key of ['R03_TEST_USER', 'R03_TEST_USER_PASSWORD', 'R03_MEMBER_USER', 'R03_MEMBER_PASSWORD', 'R03_VIEWER_USER', 'R03_VIEWER_PASSWORD']) vi.stubEnv(key, 'offline-fixture')
  harness.skip.mockImplementation((condition: boolean) => { if (condition) throw harness.refused })
  harness.write.mockResolvedValue(undefined); harness.mkdir.mockResolvedValue(undefined)
})
afterEach(() => vi.unstubAllEnvs())
const scenarios = ['local-owned', 'CI-owned', 'unowned', 'missing manifest', 'wrong project', 'wrong run', 'wrong source', 'invalid random run', 'invalid source SHA', 'wrong root', 'wrong env path', 'wrong compose path', 'partial CI', 'foreign CI project'] as const
it.each(scenarios)('registers the real review flow and admits only valid ownership: %s', async scenario => {
  const local = manifest()
  if (scenario !== 'unowned' && scenario !== 'CI-owned' && scenario !== 'partial CI' && scenario !== 'foreign CI project') {
    vi.stubEnv('R03_LOCAL_MANIFEST', resolve(root, 'local-evidence/fixture-manifest.json'))
    vi.stubEnv('R03_RUN_ID', localRun); vi.stubEnv('R03_COMPOSE_PROJECT', local.project); vi.stubEnv('R03_LOCAL_SOURCE_SHA', source)
    if (scenario === 'missing manifest') harness.read.mockImplementation(() => { throw new Error('missing fixture') })
    else {
      if (scenario === 'wrong project') local.project = 'foreign-project'
      if (scenario === 'wrong run') local.run = `r03-local-${'c'.repeat(32)}`
      if (scenario === 'wrong source') local.source = 'c'.repeat(40)
      if (scenario === 'invalid random run') { local.run = 'r03-local-fixed'; local.project = `test365alm-${local.run}`; vi.stubEnv('R03_RUN_ID', local.run); vi.stubEnv('R03_COMPOSE_PROJECT', local.project) }
      if (scenario === 'invalid source SHA') { local.source = 'not-a-commit'; vi.stubEnv('R03_LOCAL_SOURCE_SHA', local.source) }
      if (scenario === 'wrong root') local.root = resolve(root, 'foreign')
      if (scenario === 'wrong env path') local.envFile = resolve(root, 'foreign.env')
      if (scenario === 'wrong compose path') local.composeFile = resolve(root, 'foreign.json')
      harness.read.mockReturnValue(JSON.stringify(local))
    }
  }
  if (scenario === 'CI-owned' || scenario === 'partial CI' || scenario === 'foreign CI project') {
    vi.stubEnv('GITHUB_RUN_ID', '123'); vi.stubEnv('R03_COMPOSE_PROJECT', scenario === 'foreign CI project' ? 'foreign' : 'test365alm-r03-123-2')
    vi.stubEnv('R03_RUN_ID', 'r03-123-2-999')
    if (scenario !== 'partial CI') vi.stubEnv('GITHUB_RUN_ATTEMPT', '2')
  }
  await import('../e2e/project-access.spec')
  const registered = harness.registered.filter(entry => entry.name === required)
  expect(registered).toHaveLength(1)
  const locator = { first: () => locator, inputValue: async () => undefined, evaluate: async () => undefined, textContent: async () => null }
  const goto = vi.fn().mockRejectedValue(harness.reached)
  const page = { goto, on: vi.fn(), off: vi.fn(), url: () => 'http://127.0.0.1:32000/', getByRole: () => locator, getByLabel: () => locator } as unknown as Page
  const accepted = scenario === 'local-owned' || scenario === 'CI-owned'
  await expect(registered[0].callback({ page, browser: {} as Browser })).rejects.toBe(accepted ? harness.reached : harness.refused)
  expect(harness.skip).toHaveBeenCalledWith(!accepted, expect.any(String))
  expect(goto).toHaveBeenCalledTimes(accepted ? 1 : 0)
  expect(harness.docker).not.toHaveBeenCalled()
  expect(harness.write).toHaveBeenCalledTimes(accepted ? 1 : 0)
})
