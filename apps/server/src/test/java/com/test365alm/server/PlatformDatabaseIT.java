package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.identity.PrincipalRepository;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PlatformDatabaseIT {
    private static final String POSTGRES_IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test365alm_it")
            .withUsername("test365alm_it")
            .withPassword("test365alm_it_password")
            .withInitScript("r03-test-role.sql");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private PrincipalRepository principals;

    @Autowired
    private ProjectService projects;

    @Test
    void oidcIdentityIsStableAndNeverMergedByDisplayName() {
        var first = principals.upsertVerified("https://issuer.example/realm", "subject-1", "Same Name");
        var again = principals.upsertVerified("https://issuer.example/realm", "subject-1", "New Name");
        var second = principals.upsertVerified("https://issuer.example/realm", "subject-2", "Same Name");
        var third = principals.upsertVerified("https://other.example/realm", "subject-1", "Same Name");
        assertEquals(first.id(), again.id());
        assertTrue(!first.id().equals(second.id()));
        assertTrue(!first.id().equals(third.id()));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM principal", Integer.class));
        jdbcTemplate.update("UPDATE principal SET disabled_at = CURRENT_TIMESTAMP WHERE id = ?", first.id());
        org.junit.jupiter.api.Assertions.assertThrows(PrincipalRepository.DisabledPrincipalException.class,
                () -> principals.upsertVerified("https://issuer.example/realm", "subject-1", "Same Name"));
    }

    @Test
    void concurrentFirstLoginCreatesOnePrincipal() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<java.util.UUID>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> principals.upsertVerified(
                        "https://issuer.example/concurrent", "same-subject", "Concurrent").id()));
            }
            var ids = new HashSet<java.util.UUID>();
            for (var task : tasks) ids.add(task.get());
            assertEquals(1, ids.size());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM principal WHERE issuer = 'https://issuer.example/concurrent'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runtimeRoleHasDmlButNoDdlOrBypassRls() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "test365alm_runtime", "r03_isolated_test_role_only");
                var statement = connection.createStatement()) {
            assertTrue(statement.executeQuery("SELECT 1 FROM platform_metadata").next());
            assertTrue(statement.executeQuery("SELECT 1 FROM flyway_schema_history").next());
            assertTrue(statement.executeQuery("SELECT COUNT(*) FROM principal").next());
            assertEquals(1, statement.executeUpdate("INSERT INTO principal (issuer, subject, display_name) "
                    + "VALUES ('https://role.example', 'runtime', 'Runtime')"));
            assertEquals(1, statement.executeUpdate("UPDATE principal SET display_name = 'Updated' "
                    + "WHERE issuer = 'https://role.example' AND subject = 'runtime'"));
            var roles = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user");
            assertTrue(roles.next());
            assertTrue(!roles.getBoolean(1));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE forbidden_ddl (id integer)"));
        }
    }

    @Test
    void runtimeRoleSeesOnlyTheCurrentTenantThroughRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID principalA = UUID.randomUUID();
        UUID principalB = UUID.randomUUID();
        UUID domainA = UUID.randomUUID();
        UUID domainB = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                principalA, "https://rls.example", "tenant-a-" + principalA, "Tenant A");
        jdbcTemplate.update("INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                principalB, "https://rls.example", "tenant-b-" + principalB, "Tenant B");
        jdbcTemplate.update("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)",
                tenantA, "rls-a-" + tenantA, "RLS Tenant A");
        jdbcTemplate.update("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)",
                tenantB, "rls-b-" + tenantB, "RLS Tenant B");
        jdbcTemplate.update("INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)",
                domainA, tenantA, "RLS Domain A");
        jdbcTemplate.update("INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)",
                domainB, tenantB, "RLS Domain B");
        jdbcTemplate.update("INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                projectA, tenantA, domainA, "rls-project-a-" + projectA, "RLS Project A", principalA);
        jdbcTemplate.update("INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                projectB, tenantB, domainB, "rls-project-b-" + projectB, "RLS Project B", principalB);
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id) VALUES (?, ?)", tenantA, principalA);
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id) VALUES (?, ?)", tenantB, principalB);
        jdbcTemplate.update("INSERT INTO project_member (tenant_id, project_id, principal_id) VALUES (?, ?, ?)",
                tenantA, projectA, principalA);
        jdbcTemplate.update("INSERT INTO project_member (tenant_id, project_id, principal_id) VALUES (?, ?, ?)",
                tenantB, projectB, principalB);
        jdbcTemplate.update("INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tenantA, projectA, principalA, "CREATE", "project", projectA);
        jdbcTemplate.update("INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tenantB, projectB, principalB, "CREATE", "project", projectB);

        String[] protectedTables = {"tenant", "domain", "tenant_member", "project", "project_member", "audit_event"};
        for (String table : protectedTables) {
            assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE oid = CAST(? AS regclass)",
                    Boolean.class, table), "RLS must be enabled on " + table);
        }

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "test365alm_runtime", "r03_isolated_test_role_only")) {
            connection.setAutoCommit(false);
            // A tenant context alone is not authorization.  The runtime role
            // must also carry a verified principal with an active membership.
            assertEquals(0, count(connection, "tenant"));
            assertEquals(0, count(connection, "domain"));
            assertEquals(0, count(connection, "tenant_member"));
            assertEquals(0, count(connection, "project"));
            assertEquals(0, count(connection, "project_member"));
            assertEquals(0, count(connection, "audit_event"));

            setPrincipal(connection, principalA);
            assertEquals(0, count(connection, "project"));
            assertEquals(0, countProject(connection, projectA));
            connection.rollback();

            setTenant(connection, tenantA);
            assertEquals(0, count(connection, "tenant"));
            assertEquals(0, count(connection, "domain"));
            assertEquals(0, count(connection, "tenant_member"));
            assertEquals(0, count(connection, "project"));
            assertEquals(0, count(connection, "project_member"));
            assertEquals(0, count(connection, "audit_event"));
            assertEquals(0, countProject(connection, projectA));
            connection.commit();

            setPrincipal(connection, principalA);
            setTenant(connection, tenantA);
            assertEquals(1, count(connection, "tenant"));
            assertEquals(1, count(connection, "domain"));
            assertEquals(1, count(connection, "tenant_member"));
            assertEquals(1, count(connection, "project"));
            assertEquals(1, count(connection, "project_member"));
            assertEquals(1, count(connection, "audit_event"));
            assertEquals(1, countProject(connection, projectA));
            assertEquals(0, countProject(connection, projectB));
            connection.commit();

            jdbcTemplate.update("UPDATE tenant_member SET revoked_at = CURRENT_TIMESTAMP, valid_until = CURRENT_TIMESTAMP, "
                    + "authorization_version = authorization_version + 1 WHERE tenant_id = ? AND principal_id = ?",
                    tenantA, principalA);
            setPrincipal(connection, principalA);
            setTenant(connection, tenantA);
            assertEquals(0, count(connection, "tenant"));
            assertEquals(0, count(connection, "project"));
            assertEquals(0, countProject(connection, projectA));
            connection.commit();

            setPrincipal(connection, principalA);
            setTenant(connection, tenantB);
            assertEquals(0, count(connection, "tenant"));
            assertEquals(0, count(connection, "domain"));
            assertEquals(0, count(connection, "tenant_member"));
            assertEquals(0, count(connection, "project"));
            assertEquals(0, count(connection, "project_member"));
            assertEquals(0, count(connection, "audit_event"));
            assertEquals(0, countProject(connection, projectA));
            assertEquals(0, countProject(connection, projectB));
            connection.rollback();
        }
    }

    @Test
    void projectSliceCreatesAuditsGrantsViewerAndRevokesWithoutCrossTenantLeak() {
        UUID admin = principals.upsertVerified("https://project.example", "admin-" + UUID.randomUUID(), "Project Admin").id();
        UUID member = principals.upsertVerified("https://project.example", "member-" + UUID.randomUUID(), "Read Only").id();
        var tenant = projects.createTenant(admin, "slice-" + UUID.randomUUID(), "Project Slice");
        var domain = projects.createDomain(admin, tenant.id(), "Quality");
        var project = projects.createProject(admin, tenant.id(), domain.id(), "WEB-" + UUID.randomUUID(), "Web quality");
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['MEMBER']::text[])",
                tenant.id(), member);

        projects.putMember(admin, project.id(), member, java.util.List.of("PROJECT_VIEWER"));
        assertEquals(1, projects.listProjects(member, tenant.id()).size());
        assertTrue(projects.permissions(member, project.id()).permissions().containsAll(
                java.util.List.of("project:read", "requirement:read", "requirement:history:read")));
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", Integer.class,
                project.id()) >= 2);

        projects.revokeMember(admin, project.id(), member);
        assertEquals(0, projects.listProjects(member, tenant.id()).size());
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id = ? AND action = 'project.member.revoked'",
                Integer.class, project.id()) >= 1);
    }

    @Test
    void tenantAdminOnlySeesProjectsWhereTheyAreExplicitlyJoined() throws Exception {
        UUID tenantAdmin = principals.upsertVerified("https://project-fix.example",
                "tenant-admin-" + UUID.randomUUID(), "Tenant Admin").id();
        UUID otherAdmin = principals.upsertVerified("https://project-fix.example",
                "other-admin-" + UUID.randomUUID(), "Other Admin").id();
        var tenant = projects.createTenant(tenantAdmin, "fix-tenant-" + UUID.randomUUID(), "Fix Tenant");
        var domain = projects.createDomain(tenantAdmin, tenant.id(), "Fix Domain");
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['TENANT_ADMIN']::text[])",
                tenant.id(), otherAdmin);
        var joined = projects.createProject(tenantAdmin, tenant.id(), domain.id(), "joined-" + UUID.randomUUID(), "Joined");
        var unjoined = projects.createProject(otherAdmin, tenant.id(), domain.id(), "unjoined-" + UUID.randomUUID(), "Unjoined");

        assertEquals(java.util.List.of(joined.id()), projects.listProjects(tenantAdmin, tenant.id()).stream()
                .map(ProjectService.ProjectView::id).toList());
        assertThrows(ProjectAccessException.class, () -> projects.getProject(tenantAdmin, unjoined.id()));

        jdbcTemplate.update("INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id) "
                + "VALUES (?, ?, ?, 'test.unjoined.audit', 'project', ?)",
                tenant.id(), unjoined.id(), otherAdmin, unjoined.id());
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "test365alm_runtime", "r03_isolated_test_role_only")) {
            connection.setAutoCommit(false);
            setPrincipal(connection, tenantAdmin);
            setTenant(connection, tenant.id());
            assertEquals(0, countAuditProject(connection, unjoined.id()));
            connection.commit();
        }
    }

    @Test
    void revokedTenantMembershipInvalidatesExistingProjectMembership() {
        UUID admin = principals.upsertVerified("https://project-fix.example",
                "revocation-admin-" + UUID.randomUUID(), "Revocation Admin").id();
        UUID member = principals.upsertVerified("https://project-fix.example",
                "revocation-member-" + UUID.randomUUID(), "Revocation Member").id();
        var tenant = projects.createTenant(admin, "revocation-tenant-" + UUID.randomUUID(), "Revocation Tenant");
        var domain = projects.createDomain(admin, tenant.id(), "Revocation Domain");
        var project = projects.createProject(admin, tenant.id(), domain.id(), "revocation-project-" + UUID.randomUUID(), "Revocation");
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['MEMBER']::text[])",
                tenant.id(), member);
        projects.putMember(admin, project.id(), member, java.util.List.of("PROJECT_VIEWER"));

        jdbcTemplate.update("UPDATE tenant_member SET revoked_at = CURRENT_TIMESTAMP, valid_until = CURRENT_TIMESTAMP, "
                + "authorization_version = authorization_version + 1 WHERE tenant_id = ? AND principal_id = ?",
                tenant.id(), member);

        assertThrows(ProjectAccessException.class, () -> projects.getProject(member, project.id()));
        assertThrows(ProjectAccessException.class, () -> projects.permissions(member, project.id()));
        assertThrows(ProjectAccessException.class, () -> projects.listProjects(member, tenant.id()));
    }

    @Test
    void authorizationVersionLastAdminAndAuditAreTransactional() {
        UUID admin = principals.upsertVerified("https://project-fix.example",
                "version-admin-" + UUID.randomUUID(), "Version Admin").id();
        UUID secondAdmin = principals.upsertVerified("https://project-fix.example",
                "version-second-admin-" + UUID.randomUUID(), "Second Admin").id();
        var tenant = projects.createTenant(admin, "version-tenant-" + UUID.randomUUID(), "Version Tenant");
        var domain = projects.createDomain(admin, tenant.id(), "Version Domain");
        var project = projects.createProject(admin, tenant.id(), domain.id(), "version-project-" + UUID.randomUUID(), "Version");
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['TENANT_ADMIN']::text[])",
                tenant.id(), secondAdmin);

        var granted = projects.putMember(admin, project.id(), secondAdmin, java.util.List.of("PROJECT_ADMIN"));
        assertEquals(1L, granted.authorizationVersion());
        var demoted = projects.putMember(admin, project.id(), secondAdmin, java.util.List.of("PROJECT_VIEWER"), 1L);
        assertEquals(2L, demoted.authorizationVersion());
        jdbcTemplate.update("UPDATE project_member SET valid_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE tenant_id = ? AND project_id = ? AND principal_id = ?", tenant.id(), project.id(), secondAdmin);
        var renewed = projects.putMember(admin, project.id(), secondAdmin, java.util.List.of("PROJECT_VIEWER"), 2L);
        assertEquals(3L, renewed.authorizationVersion());
        int auditBeforeLastAdmin = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id = ? "
                + "AND action = 'project.member.revoked'", Integer.class, project.id());
        assertThrows(ProjectAccessException.class, () -> projects.revokeMember(admin, project.id(), admin));
        assertEquals(auditBeforeLastAdmin, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id = ? "
                + "AND action = 'project.member.revoked'", Integer.class, project.id()));

        var revoked = projects.revokeMember(admin, project.id(), secondAdmin);
        assertEquals(4L, revoked.authorizationVersion());
        assertEquals(4L, jdbcTemplate.queryForObject("SELECT authorization_version FROM project_member "
                + "WHERE tenant_id = ? AND project_id = ? AND principal_id = ?", Long.class,
                tenant.id(), project.id(), secondAdmin));
        assertEquals(4L, jdbcTemplate.queryForObject("SELECT object_revision FROM audit_event WHERE tenant_id = ? "
                + "AND project_id = ? AND action = 'project.member.revoked' ORDER BY at DESC, id DESC LIMIT 1", Long.class,
                tenant.id(), project.id()));
    }

    @Test
    void concurrentAdminRevokesRetainOneAdministrator() throws Exception {
        UUID first = principals.upsertVerified("https://project-fix.example",
                "concurrent-first-" + UUID.randomUUID(), "Concurrent First").id();
        UUID second = principals.upsertVerified("https://project-fix.example",
                "concurrent-second-" + UUID.randomUUID(), "Concurrent Second").id();
        var tenant = projects.createTenant(first, "concurrent-tenant-" + UUID.randomUUID(), "Concurrent Tenant");
        var domain = projects.createDomain(first, tenant.id(), "Concurrent Domain");
        var project = projects.createProject(first, tenant.id(), domain.id(), "concurrent-project-" + UUID.randomUUID(), "Concurrent");
        jdbcTemplate.update("INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['TENANT_ADMIN']::text[])",
                tenant.id(), second);
        projects.putMember(first, project.id(), second, java.util.List.of("PROJECT_ADMIN"));

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = pool.submit(() -> attemptRevoke(first, project.id(), second));
            Future<Throwable> secondResult = pool.submit(() -> attemptRevoke(second, project.id(), first));
            Throwable firstFailure = firstResult.get();
            Throwable secondFailure = secondResult.get();
            assertTrue((firstFailure == null) ^ (secondFailure == null),
                    "exactly one concurrent revoke may remove the other administrator");
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project_member WHERE tenant_id = ? "
                    + "AND project_id = ? AND revoked_at IS NULL AND roles && ARRAY['PROJECT_ADMIN']::text[]", Integer.class,
                    tenant.id(), project.id()));
        } finally {
            pool.shutdownNow();
        }
    }

    private Throwable attemptRevoke(UUID actor, UUID projectId, UUID target) {
        try {
            projects.revokeMember(actor, projectId, target);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void setTenant(java.sql.Connection connection, UUID tenantId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT set_config('test365alm.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.executeQuery();
        }
    }

    private static void setPrincipal(java.sql.Connection connection, UUID principalId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT set_config('test365alm.principal_id', ?, true)")) {
            statement.setString(1, principalId.toString());
            statement.executeQuery();
        }
    }

    private static int count(java.sql.Connection connection, String table) throws SQLException {
        return switch (table) {
            case "tenant" -> countRows(connection, "SELECT COUNT(*) FROM tenant");
            case "domain" -> countRows(connection, "SELECT COUNT(*) FROM domain");
            case "tenant_member" -> countRows(connection, "SELECT COUNT(*) FROM tenant_member");
            case "project" -> countRows(connection, "SELECT COUNT(*) FROM project");
            case "project_member" -> countRows(connection, "SELECT COUNT(*) FROM project_member");
            case "audit_event" -> countRows(connection, "SELECT COUNT(*) FROM audit_event");
            default -> throw new IllegalArgumentException("unexpected RLS table: " + table);
        };
    }

    private static int countProject(java.sql.Connection connection, UUID projectId) throws SQLException {
        return countRows(connection, "SELECT COUNT(*) FROM project WHERE id = ?", projectId);
    }

    private static int countAuditProject(java.sql.Connection connection, UUID projectId) throws SQLException {
        return countRows(connection, "SELECT COUNT(*) FROM audit_event WHERE project_id = ?", projectId);
    }

    private static int countRows(java.sql.Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    void migratesDatabaseAndServesPlatformEndpoints() throws Exception {
        Integer metadataRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_metadata WHERE metadata_key = 'schema-purpose'", Integer.class);
        assertEquals(1, metadataRows);

        HttpResponse<String> live = request("/health/live");
        assertEquals(HttpStatus.OK.value(), live.statusCode());

        HttpResponse<String> ready = request("/health/ready");
        assertEquals(HttpStatus.OK.value(), ready.statusCode());
        assertTrue(ready.body().contains("\"database\":\"UP\""));
        assertTrue(ready.body().contains("\"migration\":\"APPLIED\""));

        HttpResponse<String> version = request("/api/v1/version");
        assertEquals(HttpStatus.OK.value(), version.statusCode());
        assertTrue(version.body().contains("\"productName\""));
        assertTrue(version.body().contains("\"commit\":\"" + buildProperties.get("buildCommit") + "\""));
    }

    @Test
    void unauthenticatedIdentityRequestsNeverTrustHeadersOrReturnLoginHtml() throws Exception {
        HttpRequest forged = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("X-User-Id", "admin")
                .header("X-Tenant-Id", "admin")
                .header("Cookie", "JSESSIONID=damaged")
                .GET().build();
        var response = HttpClient.newHttpClient().send(forged, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("UNAUTHENTICATED"));

        var csrf = request("/api/v1/csrf");
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.body().contains("headerName"));
        var logout = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, HttpClient.newHttpClient().send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void rerunningFlywayPreservesExistingMetadata() throws Exception {
        jdbcTemplate.update(
                "UPDATE platform_metadata SET metadata_value = ? WHERE metadata_key = 'schema-purpose'",
                "preserved-by-integration-test");

        flyway.migrate();

        String value = jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM platform_metadata WHERE metadata_key = 'schema-purpose'", String.class);
        assertEquals("preserved-by-integration-test", value);
    }

    private HttpResponse<String> request(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
