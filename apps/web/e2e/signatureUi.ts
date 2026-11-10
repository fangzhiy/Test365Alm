import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'

/** Credentials stay on the real IdP page, outside diagnostics and application requests. */
export async function confirmSimulatedSignature(page:Page,meaning:string,username:string,password:string,wrongPasswordFirst=false){
 const panel=page.getByRole('region',{name:`模拟电子签署：${meaning}`,exact:true})
 await panel.getByRole('button',{name:'查看模拟签署',exact:true}).click()
 await panel.getByRole('button',{name:'开始模拟签署',exact:true}).click()
 const waiting=page.waitForEvent('popup')
 await panel.getByRole('button',{name:'重新登录以确认身份',exact:true}).click()
 const popup=await waiting
 await expect(popup.locator('#username')).toBeVisible({timeout:15000})
 await popup.locator('#username').fill(username)
 if(wrongPasswordFirst){
  await popup.locator('#password').fill('deliberately-wrong-isolated-password')
  await popup.locator('#kc-login').click()
  await expect(popup.locator('#input-error')).toBeVisible()
  await expect(panel.getByRole('button',{name:`确认模拟签署：${meaning}`,exact:true})).toHaveCount(0)
 }
 await popup.locator('#password').fill(password)
 await Promise.all([popup.waitForEvent('close',{timeout:15000}),popup.locator('#kc-login').click()])
 await panel.getByRole('button',{name:`确认模拟签署：${meaning}`,exact:true}).click()
 await expect(panel).toContainText(`已模拟签署：${meaning}`)
 await expect(panel).toContainText('SIMULATED_V1')
 await expect(panel.getByRole('button',{name:'开始模拟签署',exact:true})).toHaveCount(0)
}
