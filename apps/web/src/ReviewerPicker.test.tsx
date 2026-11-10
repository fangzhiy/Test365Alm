import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import ReviewerPicker from './ReviewerPicker'
import { reviewerApi } from './reviewerApi'
vi.mock('./reviewerApi',()=>({reviewerApi:{list:vi.fn()}}))
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('searches and pages active colleagues by name while assigning only their stable identity',async()=>{
 vi.mocked(reviewerApi.list).mockResolvedValueOnce({items:[{principalId:'one',displayName:'张三'}],nextCursor:'one'}).mockResolvedValueOnce({items:[{principalId:'two',displayName:'李四'}],nextCursor:null})
 const change=vi.fn(),u=userEvent.setup();render(<ReviewerPicker projectId="p" onChange={change}/>);
 await u.click(screen.getByRole('button',{name:'查找复核人'}));await u.selectOptions(await screen.findByLabelText('指定复核人'),'one');expect(change).toHaveBeenCalledWith('one');
 await u.click(screen.getByRole('button',{name:'更多成员'}));expect(await screen.findByRole('option',{name:'李四'})).toBeInTheDocument();expect(reviewerApi.list).toHaveBeenLastCalledWith('p','', 'one',undefined,expect.anything())
})
it('resolves readonly identity from the authorized directory and explicitly marks unavailable identities',async()=>{
 vi.mocked(reviewerApi.list).mockResolvedValueOnce({items:[{principalId:'one',displayName:'张三'}],nextCursor:null}).mockResolvedValueOnce({items:[],nextCursor:null})
 const v=render(<ReviewerPicker projectId="p" value="one"/>);expect(await screen.findByText('张三（当前成员姓名）')).toBeInTheDocument();v.rerender(<ReviewerPicker projectId="p" value="revoked"/>);expect(await screen.findByText('已指定的复核人不可用')).toBeInTheDocument();expect(screen.queryByText('revoked')).not.toBeInTheDocument()
})
