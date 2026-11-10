import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'
import HistoryPanel from './HistoryPanel'
import { historyApi } from './historyApi'
import type { HistoryEntry, HistoryPage } from './historyApi'

vi.mock('./historyApi', () => ({ historyApi: { list: vi.fn() } }))
const entry: HistoryEntry = { id: 'event', at: '2026-11-10T08:00:00Z', actor: { id: 'member', displayName: '张三' }, action: 'defect.revised', revision: 2, changes: [{ field: 'title', before: '旧标题', after: '新标题' }] }
beforeEach(() => { vi.mocked(historyApi.list).mockResolvedValue({ items: [], nextCursor: null }) })
afterEach(() => { cleanup(); vi.resetAllMocks() })
const panel = (id = 'd') => <HistoryPanel projectId="p" objectType="defect" objectId={id}/>

it('renders actor action time and before/after values, including entries without changes', async () => {
  vi.mocked(historyApi.list).mockResolvedValue({ items: [entry, { ...entry, id: 'second', actor: { id: 'fallback', displayName: null }, action: 'defect.retest-started', changes: [] }], nextCursor: null })
  render(panel())
  expect(await screen.findByText('旧标题')).toBeInTheDocument()
  for (const text of ['新标题', '张三', '修改缺陷', '标题', 'fallback', '开始复测']) expect(screen.getByText(text)).toBeInTheDocument()
  expect(screen.getAllByText(new Date(entry.at).toLocaleString('zh-CN'))).toHaveLength(2)
})

it('appends older entries using the opaque next cursor', async () => {
  vi.mocked(historyApi.list).mockResolvedValueOnce({ items: [entry], nextCursor: 'opaque+/=' }).mockResolvedValueOnce({ items: [{ ...entry, id: 'older', changes: [{ field: 'steps[2].actualResult', before: null, after: '实际值' }] }], nextCursor: null })
  render(panel()); fireEvent.click(await screen.findByRole('button', { name: '加载更多历史' }))
  expect(await screen.findByText('实际值')).toBeInTheDocument()
  expect(screen.getByText('旧标题')).toBeInTheDocument()
  expect(screen.getByText('步骤 2 实际结果')).toBeInTheDocument()
  expect(historyApi.list).toHaveBeenLastCalledWith('p', 'defect', 'd', 'opaque+/=', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(screen.queryByRole('button', { name: '加载更多历史' })).not.toBeInTheDocument()
})

it('shows empty history', async () => {
  render(panel()); expect(await screen.findByText('暂无历史')).toBeInTheDocument()
})

it.each([403, 404])('keeps %s denial inside history and clears previously visible data', async status => {
  vi.mocked(historyApi.list).mockResolvedValueOnce({ items: [entry], nextCursor: 'next' }).mockRejectedValueOnce({ status })
  render(panel()); fireEvent.click(await screen.findByRole('button', { name: '加载更多历史' }))
  expect(await screen.findByText('无权查看或对象不存在')).toBeInTheDocument()
  expect(screen.queryByText('旧标题')).not.toBeInTheDocument()
})

it('retries a failed next page without losing the first page', async () => {
  vi.mocked(historyApi.list).mockResolvedValueOnce({ items: [entry], nextCursor: 'next' }).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ items: [], nextCursor: null })
  render(panel()); fireEvent.click(await screen.findByRole('button', { name: '加载更多历史' }))
  fireEvent.click(await screen.findByRole('button', { name: '重试加载历史' }))
  await waitFor(() => expect(historyApi.list).toHaveBeenCalledTimes(3))
  expect(vi.mocked(historyApi.list).mock.calls[2][3]).toBe('next')
  expect(screen.getByText('旧标题')).toBeInTheDocument()
})

it('aborts on object change and unmount and ignores a late old response', async () => {
  let resolve!: (v: HistoryPage) => void
  vi.mocked(historyApi.list).mockImplementationOnce(() => new Promise(r => { resolve = r }))
  const view = render(panel()); const signal = vi.mocked(historyApi.list).mock.calls[0][4]!.signal!
  view.rerender(panel('new')); expect(signal.aborted).toBe(true)
  await act(async () => resolve({ items: [entry], nextCursor: null }))
  expect(await screen.findByText('暂无历史')).toBeInTheDocument()
  expect(screen.queryByText('旧标题')).not.toBeInTheDocument()
  const currentSignal = vi.mocked(historyApi.list).mock.calls[1][4]!.signal!
  view.unmount(); expect(currentSignal.aborted).toBe(true)
})

it('survives StrictMode effect replay without accepting the cancelled response', async () => {
  let resolve!: (v: HistoryPage) => void
  vi.mocked(historyApi.list).mockImplementationOnce(() => new Promise(r => { resolve = r }))
  render(<StrictMode>{panel()}</StrictMode>)
  expect(vi.mocked(historyApi.list).mock.calls[0][4]!.signal!.aborted).toBe(true)
  expect(await screen.findByText('暂无历史')).toBeInTheDocument()
  await act(async () => resolve({ items: [entry], nextCursor: null }))
  expect(screen.queryByText('旧标题')).not.toBeInTheDocument()
})
