import { defineConfig } from '@playwright/test'
import config from './playwright.config'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'

// The persistent demo is a distinct capability, never a forged CI/local-owned run.
const guard = JSON.parse(execFileSync('python', ['tools/oq_demo.py','--profile','oq-demo-01','--mode','guard'], {
 cwd:resolve(process.cwd(),'../..'),encoding:'utf8',timeout:30000,
}))
if (!guard.owned || guard.origin !== process.env.OQ_DEMO_ORIGIN || guard.project !== 'test365alm-demo-oq-demo-01') throw new Error('Owned demo guard failed')
export default defineConfig(config, {
 workers:1,retries:0,
 use:{...config.use,baseURL:guard.origin,trace:'off',screenshot:'off',video:'off'},
 reporter:[['list'],['./e2e/localReporter.ts']],
 outputDir:'../../local-evidence/demo-oq-demo-01/browser-artifacts',
})
