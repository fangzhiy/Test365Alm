import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { useState } from 'react'
import BusinessFieldsForm from './BusinessFieldsForm'
import type { BusinessFieldValues } from './businessFields'
import { coreApi } from './coreApi'
afterEach(cleanup)
vi.mock('./coreApi',()=>({coreApi:{fields:vi.fn()}}))
it('edits purpose and reviewer as a revision payload and displays the frozen values read only',async()=>{
 const onChange=vi.fn();const u=userEvent.setup();const v=render(<BusinessFieldsForm kind="test_case" projectId="p" value={{test_purpose:{value:'Frozen purpose'}}} onChange={onChange}/>);
 expect(screen.getByLabelText('测试目的')).toHaveValue('Frozen purpose');await u.type(screen.getByLabelText('指定复核人（主体 UUID）'),'abc');expect(onChange).toHaveBeenCalledWith({test_purpose:{value:'Frozen purpose'},reviewer:{value:'c'}});
 v.rerender(<BusinessFieldsForm kind="test_case" value={{test_purpose:{value:'Historical purpose'}}}/>);expect(screen.getByText('Historical purpose')).toBeInTheDocument();expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})
it('adopts an exact project definition revision and uses its enum editor',async()=>{
 vi.mocked(coreApi.fields).mockResolvedValue({items:[{id:'field-1',projectId:'p',kind:'CUSTOM',rowVersion:2,definition:{key:'owning_department',type:'ENUM',required:true,options:['QA','Engineering']}}],nextCursor:null});
 function Form(){const [v,setV]=useState<BusinessFieldValues>();return <><BusinessFieldsForm kind="requirement" projectId="p" value={v} onChange={setV}/><output>{JSON.stringify(v)}</output></>}
 const u=userEvent.setup();render(<Form/>);await u.click(screen.getByRole('button',{name:'读取项目字段配置'}));await u.click(await screen.findByRole('button',{name:'采用 owning_department v2'}));await u.selectOptions(screen.getByLabelText('所属部门'),'QA');expect(screen.getByText(/"fieldId":"field-1"/)).toHaveTextContent('"revision":2');expect(screen.getByText(/"fieldId":"field-1"/)).toHaveTextContent('"value":"QA"')
})
