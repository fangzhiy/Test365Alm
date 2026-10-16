import { useEffect, useState } from 'react'
import { coreApi } from './coreApi'
import type { Field, FieldDefinition } from './coreApi'
import type { ProjectAccess } from './projectAccess'
import { newIdempotencyKey } from './requirements'
import { useCoreRequest } from './useCoreRequest'

export default function FieldMetadataPanel({ projectId, access, resetSignal = 0 }: { projectId: string; access: ProjectAccess | null; resetSignal?: number }) {
  if (!access?.permissions.includes('project:read') || access.projectId !== projectId) return null
  return <Fields key={`${projectId}:${access.principalId}:${access.permissions.join(',')}:${resetSignal}`} projectId={projectId} admin={access.permissions.includes('project:manage-members')} />
}
function Fields({ projectId, admin }: { projectId: string; admin: boolean }) {
  const { run, busy, message, setMessage, denied } = useCoreRequest()
  const [items, setItems] = useState<Field[]>([]), [system, setSystem] = useState<string[]>([]), [cursor, setCursor] = useState<string | null>(null), [selected, setSelected] = useState<Field | null>(null)
  const [key, setKey] = useState(''), [type, setType] = useState<FieldDefinition['type']>('TEXT'), [required, setRequired] = useState(false)
  const [max, setMax] = useState('100'), [precision, setPrecision] = useState('10'), [scale, setScale] = useState('2'), [options, setOptions] = useState(''), [minimum, setMinimum] = useState(''), [maximum, setMaximum] = useState(''), [defaultValue, setDefaultValue] = useState('')
  const [value, setValue] = useState(''), [intent, setIntent] = useState<{ key: string; definition: FieldDefinition; target: Field | null } | null>(null)
  useEffect(() => { void run(signal => Promise.all([coreApi.fields(projectId, null, { signal }), coreApi.system(projectId, { signal })]), ([page, sys]) => { setItems(page.items); setCursor(page.nextCursor); setSystem(sys) }) }, [projectId, run])
  const definition = (): FieldDefinition => ({ key, type, required, ...(type === 'TEXT' ? { maxLength: Number(max) } : type === 'NUMBER' ? { precision: Number(precision), scale: Number(scale), ...(minimum ? { minimum } : {}), ...(maximum ? { maximum } : {}) } : { options: options.split(',').map(x => x.trim()) }), ...(defaultValue ? { defaultValue } : {}) })
  const choose = (f: Field) => { setSelected(f); setKey(f.definition.key); setType(f.definition.type); setRequired(f.definition.required); setMax(String(f.definition.maxLength ?? 100)); setPrecision(String(f.definition.precision ?? 10)); setScale(String(f.definition.scale ?? 2)); setOptions(f.definition.options?.join(',') ?? ''); setMinimum(f.definition.minimum ?? ''); setMaximum(f.definition.maximum ?? ''); setDefaultValue(f.definition.defaultValue ?? '') }
  const persist = async () => {
    const command = intent ?? { key: newIdempotencyKey(), definition: definition(), target: selected }; setIntent(command)
    const result = await run(signal => command.target ? coreApi.reviseField(projectId, command.target, command.definition, { signal, idempotencyKey: command.key }) : coreApi.createField(projectId, command.definition, { signal, idempotencyKey: command.key }), f => { setItems(old => [f, ...old.filter(x => x.id !== f.id)]); choose(f); setMessage('字段修订已保存') })
    if (result === 'ok' || result === 'rejected') setIntent(null)
  }
  const locked = busy || intent !== null
  if (denied) return <section aria-label="字段元数据"><p role="alert">该字段范围不可访问；请重新加载项目权限。{message}</p></section>
  return <section aria-label="字段元数据" className="version-panel"><h2>项目字段元数据</h2><p>系统字段（只读保留名）：{system.map(s => <span key={s}>{s} </span>)}</p><p>仅定义与验证字段；尚未将自定义值写入业务对象。</p>
    <button disabled={locked} onClick={() => void run(signal => coreApi.fields(projectId, null, { signal }), page => { setItems(page.items); setCursor(page.nextCursor) })}>刷新字段</button>
    <ul>{items.map(f => <li key={f.id}><button disabled={locked} onClick={() => choose(f)}>{f.definition.key} · {f.definition.type} · v{f.rowVersion}</button></li>)}</ul>
    {cursor && <button disabled={locked} onClick={() => void run(signal => coreApi.fields(projectId, cursor, { signal }), page => { setItems(old => [...old, ...page.items]); setCursor(page.nextCursor) })}>更多字段</button>}
    {admin && <fieldset disabled={locked}><legend>{selected ? '编辑字段新修订' : '新建字段'}</legend>
      <label>字段键<input value={key} disabled={selected !== null} onChange={e => setKey(e.target.value)} /></label>
      <label>字段类型<select value={type} disabled={selected !== null} onChange={e => setType(e.target.value as FieldDefinition['type'])}><option>TEXT</option><option>NUMBER</option><option>ENUM</option></select></label>
      <label>必填<input type="checkbox" checked={required} onChange={e => setRequired(e.target.checked)} /></label>
      {type === 'TEXT' && <label>文本最大长度<input type="number" value={max} onChange={e => setMax(e.target.value)} /></label>}
      {type === 'NUMBER' && <><label>数字精度<input type="number" value={precision} onChange={e => setPrecision(e.target.value)} /></label><label>数字小数位<input type="number" value={scale} onChange={e => setScale(e.target.value)} /></label><label>数字下界<input value={minimum} onChange={e => setMinimum(e.target.value)} /></label><label>数字上界<input value={maximum} onChange={e => setMaximum(e.target.value)} /></label></>}
      {type === 'ENUM' && <label>枚举选项（逗号分隔）<input value={options} onChange={e => setOptions(e.target.value)} /></label>}
      <label>默认值（留空不设置）<input value={defaultValue} onChange={e => setDefaultValue(e.target.value)} /></label>
      <button onClick={() => void persist()}>{selected ? '保存字段修订' : '创建字段'}</button><button onClick={() => { setSelected(null); setKey(''); setMessage('') }}>定义另一个字段</button>
    </fieldset>}
    {intent && !busy && <button onClick={() => void persist()}>重试原字段意图</button>}
    {selected && <fieldset disabled={locked}><legend>按修订验证值 v{selected.rowVersion}</legend><label>待验证值<input value={value} onChange={e => setValue(e.target.value)} /></label><button onClick={() => void run(signal => coreApi.validate(projectId, selected, value, { signal }), () => setMessage('值验证通过'))}>验证值</button></fieldset>}
    <p role="status">{busy ? '正在读取或保存…' : message}</p>
  </section>
}
