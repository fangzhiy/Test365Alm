import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
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
