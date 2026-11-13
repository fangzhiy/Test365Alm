import { expect,it,vi } from 'vitest'
import { categories,collectPages,instanceHistory } from './overviewData'
import type { TestInstance,Run,RunAttempt } from './testExecution'
it('counts instances separately and never adds old completed PASS to a newer RUNNING',()=>{
 const rows=[{status:'RUNNING'},{status:'FINISHED',conclusion:'FAIL'},{status:'NOT_RUN'},{status:'FINISHED',conclusion:'PASS'},{status:'PAUSED'},{status:'FINISHED',conclusion:'BLOCKED'}] as TestInstance[]
 expect(categories(rows)).toEqual({NOT_RUN:1,RUNNING:1,PAUSED:1,PASS:1,FAIL:1,BLOCKED:1})
 expect(categories([])).toEqual({NOT_RUN:0,RUNNING:0,PAUSED:0,PASS:0,FAIL:0,BLOCKED:0})
})
it('history includes every run and preserves former failure independently of latest result',()=>{
 const history=[{run:{instanceId:'i'} as Run,attempts:[{outcome:'FAIL'},{outcome:'PASS'}] as RunAttempt[]},{run:{instanceId:'i'} as Run,attempts:[{state:'RUNNING'}] as RunAttempt[]},{run:{instanceId:'other'} as Run,attempts:[{outcome:'FAIL'}] as RunAttempt[]}]
 expect(instanceHistory(history,'i')).toEqual({count:3,failed:true})
 expect(instanceHistory(history,'new')).toEqual({count:0,failed:false})
})
it('follows all pages, rejects over-limit and repeated cursors instead of returning partial totals',async()=>{
 const load=vi.fn().mockResolvedValueOnce({items:[1],nextCursor:'next'}).mockResolvedValueOnce({items:[2],nextCursor:null})
 expect(await collectPages(load,2)).toEqual([1,2]);expect(load).toHaveBeenNthCalledWith(2,'next')
 await expect(collectPages(async()=>({items:[1,2],nextCursor:null}),1)).rejects.toThrow('不完整统计')
 await expect(collectPages(async()=>({items:[1],nextCursor:'repeat'}),100)).rejects.toThrow('游标重复')
})
