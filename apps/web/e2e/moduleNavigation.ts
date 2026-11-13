import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
export async function openModule(page:Page,name:string) {
  const item=page.getByRole('navigation',{name:'功能导航'}).getByRole('button',{name,exact:true})
  if(await item.getAttribute('aria-current')!=='page')await item.click()
  await expect(item).toHaveAttribute('aria-current','page')
}
