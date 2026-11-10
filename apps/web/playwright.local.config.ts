import { defineConfig } from '@playwright/test'
import config from './playwright.config'
const origin=process.env.R03_LOCAL_WEB_ORIGIN||''
if(!process.env.R03_LOCAL_MANIFEST||!/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(origin))throw new Error('Explicit local-owned harness required')
export default defineConfig(config,{
 workers:1,retries:0,
 use:{...config.use,baseURL:origin,trace:'off',screenshot:'off',video:'off'},
 reporter:[['./e2e/localReporter.ts']],
 outputDir:'../../local-evidence/local-browser-artifacts',
})
