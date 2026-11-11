import type { Locator } from '@playwright/test'

/** Use the product review controls before asking to sign its approved immutable revision. */
export async function approveSignatureRevision(review:Pick<Locator,'getByRole'>){
 await review.getByRole('button',{name:'提交复核',exact:true}).click()
 await review.getByRole('button',{name:'批准本修订',exact:true}).click()
}
