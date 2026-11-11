import { afterEach,expect,it } from 'vitest'
import type { Page } from '@playwright/test'
import { fillSignatureCredentials } from '../e2e/signatureLoginUi'

// Reconstructed IdP DOM fixtures from retained accessibility snapshots; no authentication is mocked or accepted here.
const fullForm='<label>Username or email<input id="username"></label><label>Password<input id="password" type="password"></label><button>Sign In</button>'
function passwordForm(hiddenUsername=false){
 document.body.innerHTML=`<button type="button">Restart login</button><form>${hiddenUsername?'<input id="username" type="hidden">':''}<label>Password<input id="password" type="password"></label><button>Sign In</button></form>`
 document.querySelector('button')!.addEventListener('click',()=>{document.querySelector('form')!.innerHTML=fullForm})
}
const visible=(element:Element|null)=>element instanceof HTMLElement&&!element.hidden&&!(element instanceof HTMLInputElement&&element.type==='hidden')
function idpPage():Pick<Page,'locator'|'getByRole'>{
 return {
  locator:(selector:string)=>({
   waitFor:async()=>{if(!visible(document.querySelector(selector)))throw new Error('IdP field unavailable')},
   isVisible:async()=>visible(document.querySelector(selector)),
   fill:async(value:string)=>{const element=document.querySelector(selector);if(!visible(element)||!(element instanceof HTMLInputElement))throw new Error('IdP field unavailable');element.value=value},
  }),
  getByRole:(role:string,options:{name:string})=>({click:async()=>{
   const button=Array.from(document.querySelectorAll('button')).find(element=>role==='button'&&element.textContent===options.name)
   if(!button)throw new Error('IdP control unavailable');button.click()
  }}),
 } as unknown as Pick<Page,'locator'|'getByRole'>
}
afterEach(()=>{document.body.innerHTML=''})
it('fills the explicit account entry form',async()=>{
 document.body.innerHTML=fullForm
 await fillSignatureCredentials(idpPage(),'fixture-user','fixture-password')
 expect((document.querySelector('#username') as HTMLInputElement).value).toBe('fixture-user')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('fixture-password')
})
it.each([false,true])('fills only password on the remembered-subject form (hidden username %s)',async hidden=>{
 passwordForm(hidden)
 await fillSignatureCredentials(idpPage(),'fixture-user','fixture-password')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('fixture-password')
 expect(document.querySelector('#username')?.getAttribute('type')??null).toBe(hidden?'hidden':null)
})
it.each([false,true])('uses Restart login for an explicit different subject (hidden username %s)',async hidden=>{
 passwordForm(hidden)
 await fillSignatureCredentials(idpPage(),'fixture-other','fixture-other-password',true)
 expect((document.querySelector('#username') as HTMLInputElement).value).toBe('fixture-other')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('fixture-other-password')
})
it('can replace a rejected password without inventing a username field',async()=>{
 passwordForm()
 await fillSignatureCredentials(idpPage(),'fixture-user','fixture-wrong')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('fixture-wrong')
 await fillSignatureCredentials(idpPage(),'fixture-user','fixture-correct')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('fixture-correct')
})
it('refuses an unknown form without a visible password input',async()=>{
 document.body.innerHTML='<input id="username">'
 await expect(fillSignatureCredentials(idpPage(),'fixture-user','fixture-password')).rejects.toThrow('IdP field unavailable')
 expect((document.querySelector('#username') as HTMLInputElement).value).toBe('')
})
it('refuses a different subject when the IdP offers no restart control',async()=>{
 document.body.innerHTML='<input id="password" type="password">'
 await expect(fillSignatureCredentials(idpPage(),'fixture-other','fixture-password',true)).rejects.toThrow('IdP control unavailable')
 expect((document.querySelector('#password') as HTMLInputElement).value).toBe('')
})
