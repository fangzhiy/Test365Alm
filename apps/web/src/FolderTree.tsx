import { useEffect, useRef, useState } from 'react'
import { foldersApi, type Folder, type FolderInput, type FolderKind, type Placement } from './folders'
import { newIdempotencyKey } from './requirements'
import './folders.css'

type Props = { projectId: string; kind: FolderKind; writable: boolean; selected: string; objectId?: string; disabled?: boolean; onSelect: (id: string) => void; onMoved: () => void }
const message = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error ? String(error.message) : '目录请求失败，请重试'

export default function FolderTree({ projectId, kind, writable, selected, objectId, disabled = false, onSelect, onMoved }: Props) {
  const [nodes, setNodes] = useState<Folder[]>([])
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<Folder | null>(null)
  const [name, setName] = useState('')
  const [parent, setParent] = useState('root')
  const [sortOrder, setSortOrder] = useState(0)
  const [location, setLocation] = useState<Placement | null>(null)
  const [destination, setDestination] = useState('root')
  const [revision, setRevision] = useState(0)
  const scope = `${projectId}:${kind}`
  const active = useRef(scope)
  const intent = useRef<{ input: string; key: string } | null>(null)
  const objectRef = useRef(objectId)
  useEffect(() => { active.current = scope; return () => { active.current = '' } }, [scope])
  useEffect(() => { objectRef.current = objectId }, [objectId])

  useEffect(() => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 5000)
    void foldersApi.tree(projectId, kind, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) { setNodes(value); setEditing(null); setName(''); setParent('root'); setNotice('') } }).catch(e => { if (!controller.signal.aborted) setNotice(message(e)) }).finally(() => window.clearTimeout(timeout))
    return () => { controller.abort(); window.clearTimeout(timeout) }
  }, [projectId, kind, revision])

  useEffect(() => {
    const controller = new AbortController()
    if (objectId) void foldersApi.location(projectId, kind, objectId, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) { setLocation(value); setDestination(value.folderId ?? 'root') } }).catch(e => { if (!controller.signal.aborted) setNotice(message(e)) })
    return () => controller.abort()
  }, [projectId, kind, objectId, writable])

  const run = async (operation: () => Promise<void>) => {
    if (busy || disabled) return
    setBusy(true); setNotice('')
    try { await operation() } catch (e) { if (active.current === scope) setNotice(message(e)) }
    finally { if (active.current === scope) setBusy(false) }
  }
  const choose = (node?: Folder) => { onSelect(node?.id ?? 'root'); setEditing(node ?? null); setName(node?.name ?? ''); setParent(node?.parentId ?? 'root'); setSortOrder(node?.sortOrder ?? 0); intent.current = null }
  const descendants = new Set<string>()
  if (editing) {
    descendants.add(editing.id)
    let changed = true
    while (changed) { changed = false; for (const n of nodes) if (n.parentId && descendants.has(n.parentId) && !descendants.has(n.id)) { descendants.add(n.id); changed = true } }
  }
  const input: FolderInput = { name, parentId: parent === 'root' ? null : parent, sortOrder }
  const save = (create: boolean) => run(async () => {
    if (create) {
      const encoded = JSON.stringify(input)
      if (!intent.current || intent.current.input !== encoded) intent.current = { input: encoded, key: newIdempotencyKey() }
      await foldersApi.create(projectId, kind, input, { idempotencyKey: intent.current.key })
    } else if (editing) await foldersApi.update(projectId, editing, input)
    if (active.current !== scope) return
    intent.current = null; setRevision(v => v + 1)
  })
  const renderBranch = (parentId: string | null, seen = new Set<string>()) => <ul>{nodes.filter(n => n.parentId === parentId && !seen.has(n.id)).map(n => <li key={n.id}><button type="button" aria-current={selected === n.id ? 'location' : undefined} onClick={() => choose(n)} disabled={locked}>{n.name}</button>{renderBranch(n.id, new Set([...seen, n.id]))}</li>)}</ul>
  const locked = busy || disabled
  const rootName = kind === 'TEST_PLAN' ? 'Subject' : 'Root'
  const label = (node: Folder) => {
    const names = [node.name], visited = new Set([node.id])
    let ancestor = nodes.find(n => n.id === node.parentId)
    while (ancestor && !visited.has(ancestor.id)) { names.unshift(ancestor.name); visited.add(ancestor.id); ancestor = nodes.find(n => n.id === ancestor?.parentId) }
    return names.join(' / ')
  }
  return <aside className="folder-tree" aria-label={`${rootName}目录`}>
    <strong>{kind === 'TEST_PLAN' ? '测试计划目录' : '测试实验室目录'}</strong>
    <nav aria-label={`${rootName}目录浏览`}><button type="button" aria-current={selected === 'root' ? 'location' : undefined} onClick={() => choose()} disabled={locked}>{rootName}（未归档）</button>{renderBranch(null)}</nav>
    {notice && <p aria-live="polite" className="access-error">{notice}</p>}
    <button type="button" onClick={() => setRevision(v => v + 1)} disabled={locked}>刷新目录</button>
    {writable && <>
      <form onSubmit={e => { e.preventDefault(); void save(!editing) }} aria-label="整理目录">
        <label>目录名称<input aria-label="目录名称" required maxLength={200} value={name} onChange={e => setName(e.target.value)} disabled={locked} /></label>
        <label>父目录<select aria-label="父目录" value={parent} onChange={e => setParent(e.target.value)} disabled={locked}><option value="root">{rootName}</option>{nodes.filter(n => !descendants.has(n.id)).map(n => <option key={n.id} value={n.id}>{label(n)}</option>)}</select></label>
        <label>同级排序<input aria-label="同级排序" type="number" min={0} max={2147483647} required value={sortOrder} onChange={e => setSortOrder(Number(e.target.value))} disabled={locked} /></label>
        <button type="submit" disabled={locked || !name.trim()}>{editing ? '保存目录' : '新建目录'}</button>
        {editing && <><button type="button" disabled={locked} onClick={() => { setEditing(null); setParent(selected); setName(''); setSortOrder(0); intent.current = null }}>新建子目录</button><button type="button" disabled={locked} onClick={() => void run(async () => { await foldersApi.remove(projectId, editing); if (active.current === scope) { choose(); setRevision(v => v + 1) } })}>删除空目录</button></>}
      </form>
      {objectId && <form onSubmit={e => { e.preventDefault(); if (location?.objectId === objectId) void run(async () => { const movedId = objectId; const value = await foldersApi.place(projectId, kind, location, destination === 'root' ? null : destination); if (active.current === scope && objectRef.current === movedId) { setLocation(value); onMoved() } }) }} aria-label="移动选中对象">
        <label>移至目录<select aria-label="移至目录" value={destination} onChange={e => setDestination(e.target.value)} disabled={locked || location?.objectId !== objectId}><option value="root">{rootName}（未归档）</option>{nodes.map(n => <option key={n.id} value={n.id}>{label(n)}</option>)}</select></label>
        <button type="submit" disabled={locked || location?.objectId !== objectId}>移动{kind === 'TEST_PLAN' ? '用例' : '测试集'}</button>
      </form>}
    </>}
  </aside>
}
