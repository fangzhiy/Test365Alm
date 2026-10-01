import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // The R03 browser suite deliberately shares one Keycloak service.  Several
  // tests stop/restart or disable identities, so parallel workers would make
  // otherwise independent browser contexts share mutable server state.
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: 'http://127.0.0.1:5173',
    browserName: 'chromium',
    channel: process.env.R03_E2E_BROWSER_CHANNEL || undefined,
    trace: 'off',
  },
  reporter: [['list'], ['junit', { outputFile: '../../local-evidence/r03/playwright-results.xml' }]],
})
