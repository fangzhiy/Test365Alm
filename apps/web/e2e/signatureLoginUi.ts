import type { Page } from '@playwright/test'

/** Only operates the real IdP form; no identity or credential is sent to the application API. */
export async function fillSignatureCredentials(popup:Pick<Page,'locator'|'getByRole'>,username:string,password:string,switchSubject=false){
 await popup.locator('#password').waitFor({state:'visible',timeout:15000})
 const account=popup.locator('#username')
 if(switchSubject&&!await account.isVisible()){
  await popup.getByRole('button',{name:'Restart login',exact:true}).click()
  await account.waitFor({state:'visible',timeout:15000})
 }
 if(await account.isVisible())await account.fill(username)
 await popup.locator('#password').fill(password)
}
