import '@testing-library/jest-dom/vitest'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import FieldMetadataPanel from './FieldMetadataPanel'
import { coreApi } from './coreApi'
vi.mock('./coreApi', () => ({ coreApi: { fields: vi.fn(), system: vi.fn(), createField: vi.fn(), reviseField: vi.fn(), validate: vi.fn() } }))
const admin = { tenantId: 't', projectId: 'p', principalId: 'a', roles: ['PROJECT_ADMIN'] as const, permissions: ['project:read','project:manage-members'] }
const field = { id: 'f', projectId: 'p', kind: 'CUSTOM' as const, rowVersion: 1, definition: { key: 'note', type: 'TEXT' as const, required: false, maxLength: 10 } }
afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks() })
it('admin persists a definition then validates through the server and displays rejection', async () => {
  vi.mocked(coreApi.fields).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(coreApi.system).mockResolvedValue(['title'])
  vi.mocked(coreApi.createField).mockResolvedValue(field); vi.mocked(coreApi.validate).mockRejectedValue({ code: 'VALIDATION_FAILED', status: 422, message: 'Text exceeds length' })
  const user = userEvent.setup(); render(<FieldMetadataPanel projectId="p" access={admin} />)
  await screen.findByText('title')
  await user.type(screen.getByLabelText('字段键'), 'note'); await user.clear(screen.getByLabelText('文本最大长度')); await user.type(screen.getByLabelText('文本最大长度'), '10')
  await user.click(screen.getByRole('button', { name: '创建字段' })); await screen.findByRole('button', { name: /note.*TEXT/ })
  await user.type(screen.getByLabelText('待验证值'), 'eleven chars'); await user.click(screen.getByRole('button', { name: '验证值' }))
  expect(await screen.findByText(/Text exceeds length/)).toBeVisible()
  expect(coreApi.createField).toHaveBeenCalledWith('p', field.definition, expect.objectContaining({ idempotencyKey: expect.any(String), signal: expect.any(AbortSignal) }))
})
it('viewer can read and validate but has no definition write controls', async () => {
  vi.mocked(coreApi.fields).mockResolvedValue({ items: [field], nextCursor: null }); vi.mocked(coreApi.system).mockResolvedValue(['title'])
  render(<FieldMetadataPanel projectId="p" access={{ ...admin, roles: ['PROJECT_VIEWER'], permissions: ['project:read'] }} />)
  await screen.findByRole('button', { name: /note.*TEXT/ }); expect(screen.queryByRole('button', { name: '创建字段' })).not.toBeInTheDocument()
})
it('direct scope loss cancels pending requests and late results cannot restore data', async () => {
  let resolve!: (value: { items: typeof field[]; nextCursor: null }) => void
  vi.mocked(coreApi.fields).mockImplementation(() => new Promise(r => { resolve = r })); vi.mocked(coreApi.system).mockResolvedValue([])
  const { rerender } = render(<FieldMetadataPanel projectId="p" access={admin} />)
  await waitFor(() => expect(coreApi.fields).toHaveBeenCalled())
  const signal = vi.mocked(coreApi.fields).mock.calls[0][2]!.signal!
  rerender(<FieldMetadataPanel projectId="p" access={null} />); expect(signal.aborted).toBe(true)
  resolve({ items: [field], nextCursor: null }); expect(screen.queryByRole('button', { name: /note.*TEXT/ })).not.toBeInTheDocument()
})
it('unknown write locks submitted fields and retries the original key and snapshot', async () => {
  vi.mocked(coreApi.fields).mockResolvedValue({items:[],nextCursor:null});vi.mocked(coreApi.system).mockResolvedValue([])
  vi.mocked(coreApi.createField).mockRejectedValueOnce(new TypeError('Network unavailable')).mockResolvedValueOnce(field)
  const user=userEvent.setup();render(<FieldMetadataPanel projectId="p" access={admin} />)
  await waitFor(()=>expect(screen.getByRole('button',{name:'创建字段'})).toBeEnabled())
  await user.type(screen.getByLabelText('字段键'),'note');await user.click(screen.getByRole('button',{name:'创建字段'}))
  await screen.findByRole('button',{name:'重试原字段意图'});expect(screen.getByLabelText('字段键')).toBeDisabled()
  const first=vi.mocked(coreApi.createField).mock.calls[0]
  await user.click(screen.getByRole('button',{name:'重试原字段意图'}));await screen.findByText('字段修订已保存')
  expect(vi.mocked(coreApi.createField).mock.calls[1][1]).toEqual(first[1]);expect(vi.mocked(coreApi.createField).mock.calls[1][2]?.idempotencyKey).toBe(first[2]?.idempotencyKey)
})
it('StrictMode rounds abort predecessors and timeout then unmount clears timers', async()=>{
  vi.useFakeTimers();vi.mocked(coreApi.fields).mockImplementation(()=>new Promise(()=>{}));vi.mocked(coreApi.system).mockResolvedValue([])
  const view=render(<StrictMode><FieldMetadataPanel projectId="p" access={admin}/></StrictMode>)
  expect(coreApi.fields).toHaveBeenCalledTimes(2);expect(vi.mocked(coreApi.fields).mock.calls[0][2]?.signal?.aborted).toBe(true)
  await act(async()=>{await vi.advanceTimersByTimeAsync(8001)})
  expect(screen.getByText(/请求超时/)).toBeVisible();expect(screen.getByRole('button',{name:'创建字段'})).toBeEnabled()
  view.unmount();expect(vi.getTimerCount()).toBe(0)
})
