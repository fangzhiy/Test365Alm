import type { Page } from '@playwright/test'

export function signatureCredentialRejection(popup:Pick<Page,'locator'>){
 // Base puts aria-live on the error; v2 puts it on the field error's ancestor.
 return popup.locator('#input-error[aria-live="polite"], #input-error-password[aria-live="polite"], [aria-live="polite"] #input-error-username, [aria-live="polite"] #input-error-password')
  .filter({hasText:/^\s*Invalid (?:username or password|password)\.\s*$/})
}

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
