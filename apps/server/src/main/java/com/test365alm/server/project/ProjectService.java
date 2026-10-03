package com.test365alm.server.project;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional tenant/domain/project and fixed-role commands. */
@Service
public class ProjectService {
    private static final Set<String> PROJECT_ROLES = Set.of("PROJECT_ADMIN", "PROJECT_MEMBER", "PROJECT_VIEWER");
    private static final Set<String> TENANT_ADMINS = Set.of("TENANT_ADMIN");
    private static final Set<String> PROJECT_ADMINS = Set.of("PROJECT_ADMIN");
    private final JdbcTemplate jdbc;

    public ProjectService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public TenantView createTenant(UUID actor, String code, String name) {
        String safeCode = required(code, "code");
        String safeName = name == null || name.isBlank() ? safeCode : required(name, "name");
        UUID id = UUID.randomUUID();
        setContext(id, actor);
        try {
            jdbc.update("INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)",
                    id, safeCode, safeName, actor);
            jdbc.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?)",
                    id, actor, sqlArray("TENANT_ADMIN"));
            audit(id, null, actor, "tenant.created", "tenant", id, 0);
            return jdbc.queryForObject("""
                    SELECT id, code, name, status, row_version, TRUE AS can_create_project
                    FROM tenant WHERE id = ?
                    """, ProjectService::tenant, id);
        } catch (DataIntegrityViolationException ex) {
            throw ProjectAccessException.conflict("TENANT_CODE_EXISTS", "Tenant code is already in use");
        }
    }

    @Transactional(readOnly = true)
    public List<TenantView> listTenants(UUID actor) {
        setPrincipalContext(actor);
        return jdbc.query("""
                SELECT t.id, t.code, t.name, t.status, t.row_version,
                       EXISTS (SELECT 1 FROM tenant_member admin_tm
                               JOIN principal admin_p ON admin_p.id = admin_tm.principal_id
                               WHERE admin_tm.tenant_id = t.id AND admin_tm.principal_id = ?
                                 AND admin_tm.revoked_at IS NULL
                                 AND (admin_tm.valid_until IS NULL OR admin_tm.valid_until > CURRENT_TIMESTAMP)
                                 AND admin_p.disabled_at IS NULL
                                 AND admin_tm.roles && ARRAY['TENANT_ADMIN']::text[]) AS can_create_project
                FROM tenant t JOIN tenant_member tm ON tm.tenant_id = t.id
                    JOIN principal p ON p.id = tm.principal_id
                WHERE tm.principal_id = ? AND tm.revoked_at IS NULL
                  AND p.disabled_at IS NULL
                  AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                ORDER BY t.code
                """, ProjectService::tenant, actor, actor);
    }

    @Transactional(readOnly = true)
    public List<DomainView> listDomains(UUID actor, UUID tenantId) {
        requireTenantMember(actor, tenantId);
        setContext(tenantId, actor);
        return jdbc.query("""
                SELECT d.id, d.tenant_id, d.name, d.status, d.row_version
                FROM domain d WHERE d.tenant_id = ? AND d.status = 'ACTIVE' ORDER BY d.name
                """, ProjectService::domain, tenantId);
    }

    @Transactional
    public DomainView createDomain(UUID actor, UUID tenantId, String name) {
        requireTenantAdmin(actor, tenantId);
        requireTenantActive(tenantId);
        setContext(tenantId, actor);
        try {
            UUID id = UUID.randomUUID();
            DomainView domain = jdbc.queryForObject("""
                    INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)
                    RETURNING id, tenant_id, name, status, row_version
                    """, ProjectService::domain, id, tenantId, required(name, "name"));
            audit(tenantId, null, actor, "domain.created", "domain", id, 0);
            return domain;
        } catch (DataIntegrityViolationException ex) {
            throw ProjectAccessException.conflict("DOMAIN_NAME_EXISTS", "Domain name is already in use");
        }
    }

    @Transactional
    public ProjectView createProject(UUID actor, UUID tenantId, UUID domainId, String code, String name) {
        requireTenantAdmin(actor, tenantId);
        requireTenantActive(tenantId);
        requireDomainActive(tenantId, domainId);
        setContext(tenantId, actor);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO project (id, tenant_id, domain_id, code, name, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, tenantId, domainId, required(code, "code"), required(name, "name"), actor);
            jdbc.update("INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ?)",
                    tenantId, id, actor, sqlArray("PROJECT_ADMIN"));
            audit(tenantId, id, actor, "project.created", "project", id, 0);
            return jdbc.queryForObject("""
                    SELECT id, tenant_id, domain_id, code, name, state, schema_version, row_version
                    FROM project WHERE id = ?
                    """, ProjectService::project, id);
        } catch (DataIntegrityViolationException ex) {
            throw ProjectAccessException.conflict("PROJECT_CODE_EXISTS", "Project code is already in use");
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(UUID actor, UUID tenantId) {
        if (tenantId != null) requireTenantMember(actor, tenantId);
        setContext(tenantId, actor);
        String sql = """
                SELECT DISTINCT p.id, p.tenant_id, p.domain_id, p.code, p.name, p.state,
                       p.schema_version, p.row_version
                FROM project p
                JOIN tenant t ON t.id = p.tenant_id
                JOIN domain d ON d.tenant_id = p.tenant_id AND d.id = p.domain_id
                JOIN project_member pm ON pm.tenant_id = p.tenant_id AND pm.project_id = p.id
                    AND pm.principal_id = ? AND pm.revoked_at IS NULL
                    AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
                WHERE p.state = 'ACTIVE' AND d.status = 'ACTIVE' AND t.status = 'ACTIVE'
                  AND (? IS NULL OR p.tenant_id = ?)
                ORDER BY p.code
                """;
        return jdbc.query(sql, ProjectService::project, actor, tenantId, tenantId);
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAccess(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        return project;
    }

    @Transactional
    public ProjectView updateProject(UUID actor, UUID projectId, String name, Long rowVersion) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAdmin(actor, project.tenantId(), project.id());
        requireProjectActive(project);
        if (rowVersion == null || rowVersion < 0) throw ProjectAccessException.invalid("rowVersion is required");
        setContext(project.tenantId(), actor);
        int changed = jdbc.update("""
                UPDATE project SET name = ?, row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ? AND row_version = ?
                """, required(name, "name"), project.tenantId(), project.id(), rowVersion);
        if (changed == 0) throw ProjectAccessException.conflict("STALE_VERSION", "Project was changed by another request");
        audit(project.tenantId(), project.id(), actor, "project.updated", "project", project.id(), rowVersion + 1);
        return findProject(actor, projectId);
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAdmin(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        return jdbc.query("""
                SELECT pm.principal_id, p.display_name, p.disabled_at, pm.roles, pm.revoked_at, pm.valid_until,
                       pm.authorization_version
                FROM project_member pm JOIN principal p ON p.id = pm.principal_id
                WHERE pm.tenant_id = ? AND pm.project_id = ? ORDER BY p.display_name, pm.principal_id
                """, ProjectService::member, project.tenantId(), project.id());
    }

    @Transactional(readOnly = true)
    public List<MemberCandidateView> listCandidates(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAdmin(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        return jdbc.query("""
                SELECT tm.principal_id, p.display_name
                FROM tenant_member tm JOIN principal p ON p.id = tm.principal_id
                WHERE tm.tenant_id = ? AND tm.revoked_at IS NULL
                  AND p.disabled_at IS NULL
                  AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                AND NOT EXISTS (SELECT 1 FROM project_member pm
                                  WHERE pm.tenant_id = tm.tenant_id AND pm.project_id = ?
                                    AND pm.principal_id = tm.principal_id AND pm.revoked_at IS NULL
                                    AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP))
                ORDER BY p.display_name, tm.principal_id LIMIT 200
                """, (rs, row) -> new MemberCandidateView(rs.getObject("principal_id", UUID.class),
                        rs.getString("display_name")), project.tenantId(), project.id());
    }

    @Transactional
    public MemberView putMember(UUID actor, UUID projectId, UUID principalId, Collection<String> requestedRoles) {
        return putMember(actor, projectId, principalId, requestedRoles, null);
    }

    @Transactional
    public MemberView putMember(UUID actor, UUID projectId, UUID principalId, Collection<String> requestedRoles,
            Long expectedVersion) {
        return putMember(actor, projectId, principalId, requestedRoles, expectedVersion, expectedVersion == null);
    }

    /**
     * HTTP commands use a required authorization version.  The compatibility
     * overload above remains for existing internal bootstrap tests; it never
     * bypasses the row lock or the role checks.
     */
    @Transactional
    public MemberView putMember(UUID actor, UUID projectId, UUID principalId, Collection<String> requestedRoles,
            Long expectedVersion, boolean allowMissingVersion) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAdmin(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        lockProjectMembers(project.tenantId(), project.id());
        recheckProjectAdminAfterLock(actor, project);
        requireProjectActive(project);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM principal WHERE id = ? AND disabled_at IS NULL", Integer.class, principalId) == 0) {
            throw ProjectAccessException.notFound();
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_member tm
                JOIN principal p ON p.id = tm.principal_id
                WHERE tm.tenant_id = ? AND tm.principal_id = ? AND p.disabled_at IS NULL
                  AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, project.tenantId(), principalId) == 0) {
            throw ProjectAccessException.conflict("PRINCIPAL_NOT_TENANT_MEMBER",
                    "Project members must belong to the same tenant");
        }
        Set<String> roles = normalizeRoles(requestedRoles, PROJECT_ROLES);
        Set<String> currentRoles = currentRoles(project.tenantId(), project.id(), principalId);
        long currentVersion = memberVersion(project.tenantId(), project.id(), principalId);
        if (!allowMissingVersion && expectedVersion == null) {
            throw ProjectAccessException.invalid("authorizationVersion is required");
        }
        if (expectedVersion != null && expectedVersion.longValue() != currentVersion) {
            throw ProjectAccessException.conflict("STALE_VERSION", "Membership was changed by another request");
        }
        if (currentRoles.equals(roles) && memberIsActive(project.tenantId(), project.id(), principalId)) {
            return member(project.tenantId(), project.id(), principalId);
        }
        if (isAdminRoles(currentRoles, PROJECT_ADMINS)
                && !isAdminRoles(roles, PROJECT_ADMINS) && countActiveAdmins(project.tenantId(), project.id(), principalId) == 0) {
            throw ProjectAccessException.conflict("LAST_ADMIN", "A project must retain one active administrator");
        }
        Set<String> beforeRoles = currentRoles;
        long beforeVersion = currentVersion;
        long nextVersion = currentVersion == 0 ? 1 : currentVersion + 1;
        audit(project.tenantId(), project.id(), actor, "project.member.granted", "project_member", principalId,
                nextVersion, beforeRoles, roles, beforeVersion, nextVersion);
        jdbc.update("""
                INSERT INTO project_member (tenant_id, project_id, principal_id, roles, revoked_at, valid_until)
                VALUES (?, ?, ?, ?, NULL, NULL)
                ON CONFLICT (tenant_id, project_id, principal_id) DO UPDATE SET roles = EXCLUDED.roles,
                    revoked_at = NULL, valid_until = NULL,
                    row_version = project_member.row_version + 1,
                    authorization_version = project_member.authorization_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                """, project.tenantId(), project.id(), principalId, sqlArray(roles));
        return member(project.tenantId(), project.id(), principalId);
    }

    @Transactional
    public MemberView revokeMember(UUID actor, UUID projectId, UUID principalId) {
        return revokeMember(actor, projectId, principalId, null, true);
    }

    /** Revoke with a required optimistic authorization version for HTTP callers. */
    @Transactional
    public MemberView revokeMember(UUID actor, UUID projectId, UUID principalId, Long expectedVersion) {
        return revokeMember(actor, projectId, principalId, expectedVersion, false);
    }

    private MemberView revokeMember(UUID actor, UUID projectId, UUID principalId, Long expectedVersion,
            boolean allowMissingVersion) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAdmin(actor, project.tenantId(), project.id());
        requireProjectActive(project);
        setContext(project.tenantId(), actor);
        lockProjectMembers(project.tenantId(), project.id());
        recheckProjectAdminAfterLock(actor, project);
        requireProjectActive(project);
        Set<String> current = currentRoles(project.tenantId(), project.id(), principalId);
        if (current.isEmpty()) throw ProjectAccessException.notFound();
        long currentVersion = memberVersion(project.tenantId(), project.id(), principalId);
        if (!allowMissingVersion && expectedVersion == null) {
            throw ProjectAccessException.invalid("authorizationVersion is required");
        }
        if (expectedVersion != null && expectedVersion.longValue() != currentVersion) {
            throw ProjectAccessException.conflict("STALE_VERSION", "Membership was changed by another request");
        }
        if (!isActiveMember(project.tenantId(), project.id(), principalId)) {
            return member(project.tenantId(), project.id(), principalId);
        }
        if (isAdminRoles(current, PROJECT_ADMINS) && countActiveAdmins(project.tenantId(), project.id(), principalId) == 0) {
            throw ProjectAccessException.conflict("LAST_ADMIN", "A project must retain one active administrator");
        }
        long nextVersion = currentVersion + 1;
        audit(project.tenantId(), project.id(), actor, "project.member.revoked", "project_member", principalId,
                nextVersion, current, current, currentVersion, nextVersion);
        jdbc.update("""
                UPDATE project_member SET revoked_at = CURRENT_TIMESTAMP, valid_until = CURRENT_TIMESTAMP,
                    row_version = row_version + 1,
                    authorization_version = authorization_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ?
                  AND revoked_at IS NULL AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, project.tenantId(), project.id(), principalId);
        return member(project.tenantId(), project.id(), principalId);
    }

    @Transactional(readOnly = true)
    public PermissionView permissions(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAccess(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        Set<String> roles = currentRoles(project.tenantId(), project.id(), actor);
        Set<String> permissions = new LinkedHashSet<>();
        permissions.add("project:read");
        if (isAdminRoles(roles, PROJECT_ADMINS)) permissions.addAll(List.of("project:write", "project:manage-members"));
        // Requirement permissions are deliberately separate from project
        // administration.  Members may edit requirements, while viewers are
        // limited to the read/history actions.
        permissions.add("requirement:read");
        permissions.add("requirement:history:read");
        // Manual test cases use a separate action namespace.  A viewer keeps
        // the two read capabilities, while only an active project writer may
        // create or append a revision; project administration is unrelated.
        permissions.add("test:read");
        permissions.add("test:history:read");
        if (isAdminRoles(roles, PROJECT_ADMINS) || roles.contains("PROJECT_MEMBER")) {
            permissions.addAll(List.of("requirement:create", "requirement:update", "test:create", "test:update"));
        }
        return new PermissionView(project.tenantId(), project.id(), actor, List.copyOf(roles), List.copyOf(permissions));
    }

    /** Authorization boundary shared by project-scoped business modules. */
    @Transactional(readOnly = true)
    public ProjectView requireRequirementReadAccess(UUID actor, UUID projectId) {
        return getProject(actor, projectId);
    }

    /** Requirement write permission is distinct from member administration. */
    @Transactional(readOnly = true)
    public ProjectView requireRequirementWriteAccess(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAccess(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        if (!hasRole(actor, project.tenantId(), project.id(), Set.of("PROJECT_ADMIN", "PROJECT_MEMBER"), true)) {
            throw ProjectAccessException.forbidden();
        }
        return project;
    }

    /** Manual test case reads share the project membership boundary. */
    @Transactional(readOnly = true)
    public ProjectView requireTestReadAccess(UUID actor, UUID projectId) {
        return getProject(actor, projectId);
    }

    /** Manual test case writes are allowed to admins and ordinary members. */
    @Transactional(readOnly = true)
    public ProjectView requireTestWriteAccess(UUID actor, UUID projectId) {
        ProjectView project = findProject(actor, projectId);
        requireProjectAccess(actor, project.tenantId(), project.id());
        setContext(project.tenantId(), actor);
        if (!hasRole(actor, project.tenantId(), project.id(), Set.of("PROJECT_ADMIN", "PROJECT_MEMBER"), true)) {
            throw ProjectAccessException.forbidden();
        }
        return project;
    }

    private void requireTenantAdmin(UUID actor, UUID tenantId) {
        requireTenantMember(actor, tenantId);
        if (!hasRole(actor, tenantId, null, TENANT_ADMINS, false)) throw ProjectAccessException.forbidden();
    }

    private void requireTenantMember(UUID actor, UUID tenantId) {
        setContext(tenantId, actor);
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_member tm JOIN principal p ON p.id = tm.principal_id
                WHERE tm.tenant_id = ? AND tm.principal_id = ? AND p.disabled_at IS NULL
                  AND revoked_at IS NULL AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, tenantId, actor) == 0) throw ProjectAccessException.forbidden();
        requireTenantActive(tenantId);
    }

    private void requireProjectAdmin(UUID actor, UUID tenantId, UUID projectId) {
        requireTenantMember(actor, tenantId);
        if (!hasRole(actor, tenantId, projectId, PROJECT_ADMINS, true)) throw ProjectAccessException.forbidden();
        requireProjectActive(tenantId, projectId);
    }

    private void recheckProjectAdminAfterLock(UUID actor, ProjectView project) {
        // The first authorization check happens before waiting on the project
        // lock.  Membership can be revoked while this transaction waits, so
        // evaluate the effective tenant and project roles again after lock
        // acquisition and before reading or mutating the target member.
        requireTenantMember(actor, project.tenantId());
        if (!hasRole(actor, project.tenantId(), project.id(), PROJECT_ADMINS, true)) {
            throw ProjectAccessException.forbidden();
        }
    }

    private void requireTenantActive(UUID tenantId) {
        String status = jdbc.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, tenantId);
        if (!"ACTIVE".equals(status)) {
            throw ProjectAccessException.conflict("TENANT_NOT_ACTIVE", "Tenant is not active");
        }
    }

    private void requireDomainActive(UUID tenantId, UUID domainId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM domain
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId, domainId);
        if (count == null || count == 0) {
            throw ProjectAccessException.conflict("DOMAIN_NOT_ACTIVE", "Domain is not active in the tenant");
        }
    }

    private void requireProjectActive(ProjectView project) {
        requireProjectActive(project.tenantId(), project.id());
    }

    private void requireProjectActive(UUID tenantId, UUID projectId) {
        String state = jdbc.queryForObject("SELECT state FROM project WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, projectId);
        if (!"ACTIVE".equals(state)) {
            throw ProjectAccessException.conflict("PROJECT_NOT_ACTIVE", "Project is not active");
        }
    }

    private void requireProjectAccess(UUID actor, UUID tenantId, UUID projectId) {
        requireTenantMember(actor, tenantId);
        if (!hasRole(actor, tenantId, projectId, PROJECT_ADMINS, true)
                && !hasRole(actor, tenantId, projectId, PROJECT_ROLES, true)) throw ProjectAccessException.forbidden();
        requireProjectActive(tenantId, projectId);
    }

    private boolean hasRole(UUID actor, UUID tenantId, UUID projectId, Set<String> roles, boolean project) {
        String table = project ? "project_member pm JOIN principal p ON p.id = pm.principal_id JOIN tenant_member tm ON tm.tenant_id = pm.tenant_id AND tm.principal_id = pm.principal_id"
                : "tenant_member tm JOIN principal p ON p.id = tm.principal_id";
        String where = project ? "pm.tenant_id = ? AND pm.project_id = ? AND pm.principal_id = ? AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)"
                : "tm.tenant_id = ? AND tm.principal_id = ?";
        Object[] args = project ? new Object[]{tenantId, projectId, actor} : new Object[]{tenantId, actor};
        String alias = project ? "pm" : "tm";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where
                + " AND " + alias + ".revoked_at IS NULL AND (" + alias + ".valid_until IS NULL OR " + alias + ".valid_until > CURRENT_TIMESTAMP)"
                + " AND p.disabled_at IS NULL"
                + " AND " + alias + ".roles && CAST(? AS text[])", Integer.class, append(args, sqlArray(roles)));
        return count != null && count > 0;
    }

    private Set<String> currentRoles(UUID tenantId, UUID projectId, UUID principalId) {
        List<String[]> rows = jdbc.query("SELECT roles FROM project_member WHERE tenant_id = ? AND project_id = ? AND principal_id = ?"
                , (rs, row) -> array(rs.getArray("roles")), tenantId, projectId, principalId);
        return rows.isEmpty() ? Set.of() : new LinkedHashSet<>(Arrays.asList(rows.get(0)));
    }

    private int countActiveAdmins(UUID tenantId, UUID projectId, UUID excluded) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_member pm
                JOIN principal p ON p.id = pm.principal_id
                JOIN tenant_member tm ON tm.tenant_id = pm.tenant_id AND tm.principal_id = pm.principal_id
                WHERE pm.tenant_id = ? AND pm.project_id = ? AND pm.principal_id <> ?
                  AND p.disabled_at IS NULL AND pm.revoked_at IS NULL
                  AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
                  AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                  AND pm.roles && CAST(? AS text[])
                """, Integer.class, tenantId, projectId, excluded, sqlArray(PROJECT_ADMINS));
        return count == null ? 0 : count;
    }

    private boolean isActiveMember(UUID tenantId, UUID projectId, UUID principalId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_member pm
                JOIN principal p ON p.id = pm.principal_id
                JOIN tenant_member tm ON tm.tenant_id = pm.tenant_id AND tm.principal_id = pm.principal_id
                WHERE pm.tenant_id = ? AND pm.project_id = ? AND pm.principal_id = ?
                  AND p.disabled_at IS NULL AND pm.revoked_at IS NULL
                  AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
                  AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, tenantId, projectId, principalId);
        return count != null && count > 0;
    }

    private void lockProjectMembers(UUID tenantId, UUID projectId) {
        // Lock the project row as well as existing memberships.  A target that
        // is not yet a member has no row to lock; serializing on the project
        // closes that optimistic-version race for concurrent first grants.
        jdbc.query("SELECT id FROM project WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, projectId);
        jdbc.query("SELECT principal_id FROM project_member WHERE tenant_id = ? AND project_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("principal_id", UUID.class), tenantId, projectId);
    }

    private long memberVersion(UUID tenantId, UUID projectId, UUID principalId) {
        List<Long> versions = jdbc.query("""
                SELECT authorization_version FROM project_member
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ?
                """, (rs, row) -> rs.getLong("authorization_version"), tenantId, projectId, principalId);
        return versions.isEmpty() ? 0 : versions.get(0);
    }

    private boolean memberIsActive(UUID tenantId, UUID projectId, UUID principalId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_member pm
                JOIN principal p ON p.id = pm.principal_id
                JOIN tenant_member tm ON tm.tenant_id = pm.tenant_id AND tm.principal_id = pm.principal_id
                WHERE pm.tenant_id = ? AND pm.project_id = ? AND pm.principal_id = ?
                  AND p.disabled_at IS NULL AND pm.revoked_at IS NULL
                  AND (pm.valid_until IS NULL OR pm.valid_until > CURRENT_TIMESTAMP)
                  AND tm.revoked_at IS NULL AND (tm.valid_until IS NULL OR tm.valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, tenantId, projectId, principalId);
        return count != null && count > 0;
    }

    private MemberView member(UUID tenantId, UUID projectId, UUID principalId) {
        List<MemberView> values = jdbc.query("""
                SELECT pm.principal_id, p.display_name, p.disabled_at, pm.roles, pm.revoked_at, pm.valid_until, pm.authorization_version
                FROM project_member pm JOIN principal p ON p.id = pm.principal_id
                WHERE pm.tenant_id = ? AND pm.project_id = ? AND pm.principal_id = ?
                """, ProjectService::member, tenantId, projectId, principalId);
        return values.isEmpty() ? null : values.get(0);
    }

    private ProjectView findProject(UUID actor, UUID projectId) {
        // The id is globally unique, but the runtime role is still protected
        // by RLS. The principal context lets the policy resolve its memberships
        // before a tenant context is known; the project context keeps this
        // lookup narrow instead of exposing every project in the principal's
        // tenants when a tenant has not yet been selected.
        setPrincipalContext(actor);
        setProjectContext(projectId);
        List<ProjectView> values = jdbc.query("""
                SELECT id, tenant_id, domain_id, code, name, state, schema_version, row_version
                FROM project WHERE id = ?
                """, ProjectService::project, projectId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }


    private void audit(UUID tenantId, UUID projectId, UUID actor, String action, String type, UUID object, long revision) {
        audit(tenantId, projectId, actor, action, type, object, revision, null, null, null, null);
    }

    private void audit(UUID tenantId, UUID projectId, UUID actor, String action, String type, UUID object,
            long revision, Collection<String> beforeRoles, Collection<String> afterRoles,
            Long beforeVersion, Long afterVersion) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id,
                        object_revision, before_roles, after_roles, before_authorization_version,
                        after_authorization_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, tenantId);
            statement.setObject(2, projectId);
            statement.setObject(3, actor);
            statement.setString(4, action);
            statement.setString(5, type);
            statement.setObject(6, object);
            statement.setLong(7, revision);
            if (beforeRoles == null) statement.setNull(8, java.sql.Types.ARRAY);
            else statement.setArray(8, connection.createArrayOf("text", beforeRoles.toArray(String[]::new)));
            if (afterRoles == null) statement.setNull(9, java.sql.Types.ARRAY);
            else statement.setArray(9, connection.createArrayOf("text", afterRoles.toArray(String[]::new)));
            if (beforeVersion == null) statement.setNull(10, java.sql.Types.BIGINT);
            else statement.setLong(10, beforeVersion);
            if (afterVersion == null) statement.setNull(11, java.sql.Types.BIGINT);
            else statement.setLong(11, afterVersion);
            return statement;
        });
    }

    private void setContext(UUID tenantId, UUID actor) {
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id', ?, true)", String.class,
                tenantId == null ? "" : tenantId.toString());
        setPrincipalOnly(actor);
        clearProjectContext();
    }

    private void setPrincipalContext(UUID actor) {
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id', ?, true)", String.class, "");
        setPrincipalOnly(actor);
        clearProjectContext();
    }

    private void setPrincipalOnly(UUID actor) {
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id', ?, true)", String.class,
                actor == null ? "" : actor.toString());
    }

    private void clearProjectContext() {
        jdbc.queryForObject("SELECT set_config('test365alm.project_id', ?, true)", String.class, "");
    }

    private void setProjectContext(UUID projectId) {
        jdbc.queryForObject("SELECT set_config('test365alm.project_id', ?, true)", String.class,
                projectId == null ? "" : projectId.toString());
    }

    private SqlArrayValue sqlArray(String role) {
        return sqlArray(Set.of(role));
    }

    private SqlArrayValue sqlArray(Collection<String> roles) {
        return new SqlArrayValue("text", (Object[]) roles.toArray(String[]::new));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) throw ProjectAccessException.invalid(field + " is required");
        return value.trim();
    }

    private static Set<String> normalizeRoles(Collection<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty() || values.size() > 2) {
            throw ProjectAccessException.invalid("roles must contain one or two fixed roles");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String value : values) {
            String role = value == null ? "" : value.trim().toUpperCase();
            if (!allowed.contains(role)) throw ProjectAccessException.invalid("Unknown role: " + role);
            roles.add(role);
        }
        return roles;
    }

    private static boolean isAdminRoles(Collection<String> roles, Set<String> admins) {
        return roles.stream().anyMatch(admins::contains);
    }

    private static Object[] append(Object[] values, Object item) {
        Object[] result = Arrays.copyOf(values, values.length + 1);
        result[values.length] = item;
        return result;
    }

    private static TenantView tenant(ResultSet rs, int row) throws SQLException {
        return new TenantView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getLong("row_version"), rs.getBoolean("can_create_project"));
    }

    private static DomainView domain(ResultSet rs, int row) throws SQLException {
        return new DomainView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("status"), rs.getLong("row_version"));
    }

    private static ProjectView project(ResultSet rs, int row) throws SQLException {
        return new ProjectView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("domain_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("state"),
                rs.getInt("schema_version"), rs.getLong("row_version"));
    }

    private static MemberView member(ResultSet rs, int row) throws SQLException {
        OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
        OffsetDateTime validUntil = rs.getObject("valid_until", OffsetDateTime.class);
        OffsetDateTime disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
        boolean active = revokedAt == null && (validUntil == null || validUntil.isAfter(OffsetDateTime.now()));
        return new MemberView(rs.getObject("principal_id", UUID.class), rs.getString("display_name"),
                Arrays.asList(array(rs.getArray("roles"))), disabledAt != null ? "DISABLED" : active ? "ACTIVE" : "REVOKED", validUntil,
                rs.getLong("authorization_version"));
    }

    private static String[] array(Array array) throws SQLException {
        if (array == null) return new String[0];
        return (String[]) array.getArray();
    }

    public record TenantView(UUID id, String code, String name, String status, long rowVersion,
            boolean canCreateProject) { }
    public record DomainView(UUID id, UUID tenantId, String name, String status, long rowVersion) { }
    public record ProjectView(UUID id, UUID tenantId, UUID domainId, String code, String name, String state,
            int schemaVersion, long rowVersion) { }
    public record MemberView(UUID principalId, String displayName, List<String> roles, String state,
            OffsetDateTime validUntil, long authorizationVersion) { }
    public record MemberCandidateView(UUID principalId, String displayName) { }
    public record PermissionView(UUID tenantId, UUID projectId, UUID principalId, List<String> roles,
            List<String> permissions) { }
}

