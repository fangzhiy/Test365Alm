import { useState } from 'react'
import type { FormEvent } from 'react'
import { accessApi } from './projectAccess'
import type { MemberCandidate, Project, ProjectAccess, ProjectMember, ProjectRole, Tenant } from './projectAccess'

type PanelState = 'idle' | 'loading' | 'ready' | 'error'
const roles: ProjectRole[] = ['PROJECT_ADMIN', 'PROJECT_MEMBER', 'PROJECT_VIEWER']
const roleLabel: Record<ProjectRole, string> = { PROJECT_ADMIN: '项目管理员', PROJECT_MEMBER: '项目成员', PROJECT_VIEWER: '项目查看者' }
const messageFor = (error: unknown) => typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : '访问范围加载失败，请稍后重试'

export default function ProjectAccessPanel() {
  const [state, setState] = useState<PanelState>('idle')
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [candidates, setCandidates] = useState<MemberCandidate[]>([])
  const [access, setAccess] = useState<ProjectAccess | null>(null)
  const [tenantId, setTenantId] = useState('')
  const [projectId, setProjectId] = useState('')
  const [principalId, setPrincipalId] = useState('')
  const [role, setRole] = useState<ProjectRole>('PROJECT_MEMBER')
  const [domainId, setDomainId] = useState('')
  const [projectCode, setProjectCode] = useState('')
  const [projectName, setProjectName] = useState('')
  const [notice, setNotice] = useState('')

  const loadProject = async (nextProjectId: string) => {
    setProjectId(nextProjectId)
    setNotice('')
    if (!nextProjectId) { setMembers([]); setCandidates([]); setAccess(null); return }
    const [nextMembers, nextCandidates, nextAccess] = await Promise.all([accessApi.listMembers(nextProjectId), accessApi.listMemberCandidates(nextProjectId), accessApi.getPermissions(nextProjectId)])
    setMembers(nextMembers)
    setCandidates(nextCandidates)
    setAccess(nextAccess)
  }

  const loadTenant = async (nextTenantId: string) => {
    setTenantId(nextTenantId)
    setProjects([])
    setMembers([])
    setCandidates([])
    setAccess(null)
    if (!nextTenantId) return
    const nextProjects = await accessApi.listProjects(nextTenantId)
    setProjects(nextProjects)
    await loadProject(nextProjects[0]?.id ?? '')
  }

  const load = async () => {
    setState('loading')
    setNotice('')
    try {
      const nextTenants = await accessApi.listTenants()
      setTenants(nextTenants)
      await loadTenant(nextTenants[0]?.id ?? '')
      setState('ready')
      if (nextTenants.length === 0) setNotice('当前会话没有可管理的租户')
    } catch (error) {
      setState('error')
      setNotice(messageFor(error))
    }
  }

  const mutateMember = async (event: FormEvent) => {
    event.preventDefault()
    if (!projectId || !principalId.trim()) return
    try {
      const member = await accessApi.setMemberRoles(projectId, principalId.trim(), [role])
      setMembers((current) => [...current.filter((item) => item.principalId !== member.principalId), member])
      setPrincipalId('')
      setNotice('成员授权已保存')
    } catch (error) { setNotice(messageFor(error)) }
  }

  const createProject = async (event: FormEvent) => {
    event.preventDefault()
    if (!tenantId) return
    try {
      const project = await accessApi.createProject({ tenantId, domainId: domainId.trim(), code: projectCode.trim(), name: projectName.trim() })
      setProjects((current) => [...current, project])
      setProjectCode('')
      setProjectName('')
      setNotice('项目已创建')
      await loadProject(project.id)
    } catch (error) { setNotice(messageFor(error)) }
  }

  const revoke = async (member: ProjectMember) => {
    if (!projectId) return
    try {
      await accessApi.revokeMember(projectId, member.principalId)
      setMembers((current) => current.map((item) => item.principalId === member.principalId ? { ...item, state: 'REVOKED' } : item))
      setNotice(`${member.displayName} 已撤销项目访问`)
    } catch (error) { setNotice(messageFor(error)) }
  }

  return <section className="access-panel" aria-labelledby="access-heading">
    <div className="access-panel-header"><div><span className="panel-label">项目访问管理</span><h2 id="access-heading">租户、项目与成员</h2><p>所有列表和授权动作都由服务端按当前会话过滤。</p></div><button type="button" className="secondary-button" onClick={() => void load()} disabled={state === 'loading'}>{state === 'loading' ? '加载中…' : '加载访问范围'}</button></div>
    {state === 'idle' && <p className="access-empty" role="status">登录后加载你有权查看的租户和项目。</p>}
    {state === 'error' && <p className="access-error" role="alert">{notice}</p>}
    {state !== 'idle' && state !== 'error' && <>
      <div className="management-forms">
        {tenantId && <form className="compact-form" onSubmit={(event) => void createProject(event)}><strong>新建项目</strong><label>域 ID<input value={domainId} onChange={(event) => setDomainId(event.target.value)} placeholder="domain UUID" required /></label><label>项目代码<input value={projectCode} onChange={(event) => setProjectCode(event.target.value)} required /></label><label>显示名称<input value={projectName} onChange={(event) => setProjectName(event.target.value)} required /></label><button type="submit" className="primary-button">创建项目</button></form>}
      </div>
      <div className="access-selects">
        <label>租户<select value={tenantId} onChange={(event) => void loadTenant(event.target.value)}><option value="">选择租户</option>{tenants.map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.name}（{tenant.code}）</option>)}</select></label>
        <label>项目<select value={projectId} onChange={(event) => void loadProject(event.target.value)}><option value="">选择项目</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}（{project.code}）</option>)}</select></label>
      </div>
      {notice && <p className="access-notice" role="status">{notice}</p>}
      {access && <p className="access-scope">当前角色：{access.roles.map((item) => roleLabel[item]).join('、') || '无'} · 权限：{access.permissions.join('、') || '无'}</p>}
      {projectId && <>
        <form className="member-form" onSubmit={(event) => void mutateMember(event)}><label>同租户候选主体<select aria-label="同租户候选主体" value={principalId} onChange={(event) => setPrincipalId(event.target.value)}><option value="">选择主体（或手动输入 ID）</option>{candidates.map((candidate) => <option key={candidate.principalId} value={candidate.principalId}>{candidate.displayName}（{candidate.principalId}）</option>)}</select></label><label>主体 ID<input value={principalId} onChange={(event) => setPrincipalId(event.target.value)} placeholder="例如 principal-123" required /></label><label>固定角色<select value={role} onChange={(event) => setRole(event.target.value as ProjectRole)}>{roles.map((item) => <option key={item} value={item}>{roleLabel[item]}</option>)}</select></label><button type="submit" className="primary-button">保存成员</button></form>
        <div className="member-list" aria-label="项目成员"><div className="member-list-heading"><strong>项目成员</strong><span>{members.length} 人</span></div>{members.length === 0 ? <p className="access-empty">当前项目没有可显示的成员。</p> : members.map((member) => <div className="member-row" key={member.principalId}><div><strong>{member.displayName}</strong><small>{member.principalId} · {member.roles.map((item) => roleLabel[item]).join('、')} · {member.state}</small></div><button type="button" className="link-button" onClick={() => void revoke(member)} disabled={member.state === 'REVOKED'}>{member.state === 'REVOKED' ? '已撤销' : '撤销访问'}</button></div>)}</div>
      </>}
    </>}
  </section>
}
