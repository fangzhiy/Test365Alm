import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import FolderTree from './FolderTree'
import { foldersApi, type Folder } from './folders'

vi.mock('./folders', () => ({ foldersApi: { tree: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), location: vi.fn(), place: vi.fn() } }))
const oq: Folder = { id: 'oq', kind: 'TEST_PLAN', name: 'OQ', parentId: null, sortOrder: 0, rowVersion: 1 }
const batch: Folder = { ...oq, id: 'batch', name: 'Batch', parentId: 'oq' }
const props = { projectId: 'p', kind: 'TEST_PLAN' as const, writable: true, selected: 'root', onSelect: vi.fn(), onMoved: vi.fn() }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(foldersApi.tree).mockResolvedValue([oq, batch]); vi.mocked(foldersApi.location).mockResolvedValue({ objectId: 'test', folderId: null, rowVersion: 1 }) })
afterEach(cleanup)

describe('FolderTree', () => {
  it('renders nested folders and lets viewers browse without write controls', async () => {
    render(<FolderTree {...props} writable={false} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Batch' }))
    expect(props.onSelect).toHaveBeenCalledWith('batch')
    expect(screen.queryByRole('form', { name: '整理目录' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Subject（未归档）' }))
    expect(props.onSelect).toHaveBeenLastCalledWith('root')
  })
  it('creates a named child with ordering and a persistent intent key', async () => {
    vi.mocked(foldersApi.create).mockRejectedValueOnce({ message: '连接中断' }).mockResolvedValueOnce(batch)
    render(<FolderTree {...props} />)
    await screen.findByRole('button', { name: 'OQ' })
    fireEvent.change(screen.getByLabelText('目录名称'), { target: { value: 'New' } })
    fireEvent.change(screen.getByLabelText('父目录'), { target: { value: 'oq' } })
    fireEvent.change(screen.getByLabelText('同级排序'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: '新建目录' }))
    await screen.findByText('连接中断')
    const first = vi.mocked(foldersApi.create).mock.calls[0]
    expect(first.slice(0, 3)).toEqual(['p', 'TEST_PLAN', { name: 'New', parentId: 'oq', sortOrder: 3 }])
    fireEvent.click(screen.getByRole('button', { name: '新建目录' }))
    await waitFor(() => expect(foldersApi.create).toHaveBeenCalledTimes(2))
    expect(vi.mocked(foldersApi.create).mock.calls[1][3]?.idempotencyKey).toEqual(first[3]?.idempotencyKey)
  })
  it('renames or moves a folder with its saved version and excludes descendant targets', async () => {
    vi.mocked(foldersApi.update).mockResolvedValue({ ...oq, name: 'PQ', rowVersion: 2 })
    render(<FolderTree {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: 'OQ' }))
    const select = screen.getByLabelText('父目录')
    expect(select.querySelectorAll('option')).toHaveLength(1)
    fireEvent.change(screen.getByLabelText('目录名称'), { target: { value: 'PQ' } })
    fireEvent.click(screen.getByRole('button', { name: '保存目录' }))
    await waitFor(() => expect(foldersApi.update).toHaveBeenCalledWith('p', oq, { name: 'PQ', parentId: null, sortOrder: 0 }))
  })
  it('keeps nonempty delete rejection visible and refreshes an empty deletion', async () => {
    vi.mocked(foldersApi.remove).mockRejectedValueOnce({ message: '目录非空' }).mockResolvedValueOnce()
    render(<FolderTree {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: 'OQ' }))
    fireEvent.click(screen.getByRole('button', { name: '删除空目录' }))
    await screen.findByText('目录非空')
    fireEvent.click(screen.getByRole('button', { name: '删除空目录' }))
    await waitFor(() => expect(props.onSelect).toHaveBeenLastCalledWith('root'))
    expect(foldersApi.remove).toHaveBeenCalledWith('p', oq)
  })
  it('moves a selected test using the placement version and supports returning to root', async () => {
    vi.mocked(foldersApi.location).mockResolvedValue({ objectId: 'test', folderId: 'batch', rowVersion: 4 })
    vi.mocked(foldersApi.place).mockResolvedValue({ objectId: 'test', folderId: null, rowVersion: 5 })
    render(<FolderTree {...props} objectId="test" />)
    await waitFor(() => expect(screen.getByLabelText('移至目录')).toHaveValue('batch'))
    fireEvent.change(screen.getByLabelText('移至目录'), { target: { value: 'root' } })
    fireEvent.click(screen.getByRole('button', { name: '移动用例' }))
    await waitFor(() => expect(foldersApi.place).toHaveBeenCalledWith('p', 'TEST_PLAN', { objectId: 'test', folderId: 'batch', rowVersion: 4 }, null))
    expect(props.onMoved).toHaveBeenCalledOnce()
  })
  it('renders the independent lab tree and moves test sets', async () => {
    vi.mocked(foldersApi.tree).mockResolvedValue([{ ...oq, kind: 'TEST_LAB' }])
    render(<FolderTree {...props} kind="TEST_LAB" objectId="set" />)
    expect(await screen.findByRole('button', { name: 'Root（未归档）' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '移动测试集' })).toBeInTheDocument()
    expect(foldersApi.tree).toHaveBeenCalledWith('p', 'TEST_LAB', expect.anything())
  })
  it('discards a late tree response after project switch', async () => {
    let resolve!: (value: Folder[]) => void
    vi.mocked(foldersApi.tree).mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValueOnce([])
    const view = render(<FolderTree {...props} />)
    view.rerender(<FolderTree {...props} projectId="other" />)
    resolve([oq])
    await waitFor(() => expect(foldersApi.tree).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'OQ' })).not.toBeInTheDocument()
  })
})
