import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from './App'
vi.mock('./AuthPanel',()=>({default:()=> <div>测试会话</div>}))
vi.mock('./ProjectAccessPanel',()=>({default:()=> <div>授权项目选择</div>}))
vi.mock('./ProjectOverview',()=>({default:()=> <div>概览正文</div>}))
vi.mock('./TestCasesPanel',()=>({default:()=> <div>计划正文</div>}))
vi.mock('./TestExecutionPanel',()=>({default:()=> <div>实验室正文</div>}))
beforeEach(()=>{window.history.replaceState(null,'','#overview');vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new Error('offline')))})
afterEach(()=>{cleanup();vi.unstubAllGlobals()})
it('exposes every existing module and restores the module from a hash',async()=>{
 window.history.replaceState(null,'','#lab')
 render(<App/>);expect(screen.getByText('实验室正文')).toBeVisible()
 for(const name of ['项目概览','测试计划','测试实验室','偏差管理','需求管理','发布与质量快照','业务字段','项目与成员','系统状态'])expect(screen.getByRole('button',{name})).toBeVisible()
 fireEvent.click(screen.getByRole('button',{name:'测试计划'}))
 expect(screen.getByText('计划正文')).toBeVisible();expect(screen.queryByText('实验室正文')).not.toBeInTheDocument()
 expect(window.location.hash).toBe('#plan')
 fireEvent.click(screen.getByRole('button',{name:'项目概览'}))
 await waitFor(()=>expect(screen.getByText('概览正文')).toBeVisible())
 expect(screen.queryByText('计划正文')).not.toBeInTheDocument()
})
it('unknown hashes return safely to overview without lab state',()=>{
 window.history.replaceState(null,'','#unknown');render(<App/>);expect(screen.getByText('概览正文')).toBeVisible()
 expect(screen.queryByText('实验室正文')).not.toBeInTheDocument()
})
