import { test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// No response bodies, query strings, form values, cookies or raw traces.
export class ExecutionDiagnostics {
  private readonly events: object[] = []

  async step<T>(page: Page, name: string, action: () => Promise<T>): Promise<T> {
    const started = Date.now()
    const http: object[] = []
    const listener = (response: import('@playwright/test').Response) => {
      const pathname = new URL(response.url()).pathname
      if (pathname.startsWith('/api/v1/')) http.push({ pathname, status: response.status() })
    }
    page.on('response', listener)
    let failed = false
    try { return await test.step(name, action, { timeout: 30_000 }) }
    catch (error) { failed = true; throw error }
    finally {
      page.off('response', listener)
      const panel = page.locator('section.execution-panel')
      const snapshot = await panel.evaluate((element) => ({
        detail: element.querySelector('.execution-attempt-heading h4')?.textContent,
        stepCount: element.querySelectorAll('textarea').length,
        buttons: Array.from(element.querySelectorAll('button')).map((button) => ({ label: button.textContent, disabled: button.disabled })),
      }), undefined, { timeout: 1_000 }).catch(() => ({ unavailable: true }))
      this.events.push({ name, startedAt: new Date(started).toISOString(), durationMs: Date.now() - started, failed, pathname: new URL(page.url()).pathname, http, snapshot })
    }
  }

  cleanup(name: string, failed: boolean): void { this.events.push({ cleanup: name, failed }) }

  async save(): Promise<void> {
    const directory = resolve(process.cwd(), '../../local-evidence/r03')
    await mkdir(directory, { recursive: true })
    await writeFile(resolve(directory, 'manual-execution-context-full.json'), JSON.stringify({ schemaVersion: 1, events: this.events }, null, 2))
  }
}
