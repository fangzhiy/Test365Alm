import {afterEach,expect,it} from 'vitest'
import type {Page} from '@playwright/test'
import {signatureCredentialRejection} from '../e2e/signatureLoginUi'

// Reconstructed from the exact tested image's base login/login-password templates.
// This tests the UI evidence locator only; it never accepts or mocks authentication.
function rejectionElements(html:string){
 document.body.innerHTML=html
 const wrap=(elements:Element[]):unknown=>({
  filter:({hasText}:{hasText:RegExp})=>wrap(elements.filter(e=>hasText.test(e.textContent??''))),
  elements,
 })
 const page={locator:(selector:string)=>wrap(Array.from(document.querySelectorAll(selector)))} as unknown as Pick<Page,'locator'>
 return (signatureCredentialRejection(page) as unknown as {elements:HTMLElement[]}).elements
}
afterEach(()=>{document.body.innerHTML=''})
it.each([
 ['input-error','Invalid username or password.'],
 ['input-error-password','Invalid password.'],
])('requires the real %s credential rejection live region', (id,message)=>{
 const elements=rejectionElements(`<label>Password<input type="password" id="password"></label><span id="${id}" aria-live="polite">${message}</span>`)
 expect(elements).toHaveLength(1)
 expect(elements[0]).toHaveTextContent(message)
 expect(elements[0]).toBeVisible()
})
it.each([
 ['username','Invalid username or password.'],
 ['password','Invalid password.'],
])('requires the v2 field error inside its ancestor live region (%s)', (field,message)=>{
 const elements=rejectionElements(`<div id="input-error-container-${field}"><div aria-live="polite"><div><div id="input-error-${field}"><span>\n  ${message}\n</span></div></div></div></div>`)
 expect(elements).toHaveLength(1)
 expect(elements[0]).toHaveTextContent(message)
 expect(elements[0]).toBeVisible()
})
it.each([
 '<label>Password<input type="password" id="password"></label><button>Sign In</button>',
 '<div>Invalid password.</div>',
 '<span id="unrelated" aria-live="polite">Invalid password.</span>',
 '<span id="input-error-password">Invalid password.</span>',
 '<span id="input-error">Invalid username or password.</span>',
 '<span id="input-error" aria-live="polite">Temporary service error</span>',
 '<span id="input-error-password" aria-live="polite">Invalid password. Extra unrelated text</span>',
 '<div aria-live="polite"><span id="unrelated">Invalid password.</span></div>',
 '<div><span id="input-error-username">Invalid username or password.</span></div>',
])('does not treat an unsubmitted form or unrelated message as credential rejection (%s)',html=>{
 expect(rejectionElements(html)).toHaveLength(0)
})
it('keeps a hidden stale error insufficient for the visible rejection assertion',()=>{
 const elements=rejectionElements('<span id="input-error-password" aria-live="polite" hidden>Invalid password.</span>')
 expect(elements).toHaveLength(1)
 expect(elements[0]).not.toBeVisible()
})
