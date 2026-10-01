package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runtime-role project authorization tests.  The owner connection is used
 * only to create disposable, verified fixtures and to inspect final state;
 * the Spring application itself always connects as test365alm_runtime.
 */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(120)
class ProjectAuthorizationIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_authorization_it")
            .withUsername("project_authorization_owner")
            .withPassword("project_authorization_owner_password")
            .withInitScript("r03-test-role.sql");

    @Autowired
    private JdbcTemplate runtimeJdbc;

    @Autowired
    private DataSource runtimeDataSource;

    @Autowired
    private ProjectService projects;

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void setContext(Connection connection, UUID tenant, UUID principal, UUID project) throws SQLException {
        setConfig(connection, "test365alm.tenant_id", tenant == null ? "" : tenant.toString());
        setConfig(connection, "test365alm.principal_id", principal == null ? "" : principal.toString());
        setConfig(connection, "test365alm.project_id", project == null ? "" : project.toString());
    }

    private static void setConfig(Connection connection, String key, String value) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeQuery();
        }
    }

    private static UUID principal(String suffix, String displayName) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, "https://project-it.example/realm");
            statement.setString(3, suffix + "-" + id);
            statement.setString(4, displayName);
            statement.executeUpdate();
        }
        return id;
    }

    private Fixture fixture() throws SQLException {
        UUID admin = principal("admin", "Project Admin");
        UUID member = principal("member", "Project Member");
        UUID viewer = principal("viewer", "Project Viewer");
        UUID secondAdmin = principal("second-admin", "Second Project Admin");
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (var connection = ownerConnection()) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)")) {
                statement.setObject(1, tenant);
                statement.setString(2, "runtime-tenant-" + tenant);
                statement.setString(3, "Runtime Tenant");
                statement.setObject(4, admin);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)")) {
                statement.setObject(1, domain);
                statement.setObject(2, tenant);
                statement.setString(3, "Runtime Domain");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])")) {
                for (UUID principal : List.of(admin, member, viewer, secondAdmin)) {
                    statement.setObject(1, tenant);
                    statement.setObject(2, principal);
                    statement.setString(3, principal.equals(admin) ? "{TENANT_ADMIN}" : "{MEMBER}");
                    statement.executeUpdate();
                }
            }
        }
        var project = projects.createProject(admin, tenant, domain, "P-" + UUID.randomUUID(), "Runtime Project");
        return new Fixture(admin, member, viewer, secondAdmin, tenant, domain, project.id());
    }

    private void grant(Fixture f, UUID principal, List<String> roles, long version) {
        projects.putMember(f.admin(), f.project(), principal, roles, version, false);
    }

    @Test
    void runtimeIdentityAndDirectSqlWritesAreConstrained() throws Exception {
        Fixture f = fixture();
        grant(f, f.viewer(), List.of("PROJECT_VIEWER"), 0);
        var role = runtimeJdbc.queryForMap(
                "SELECT current_user AS username, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user");
        assertEquals(RUNTIME_USER, role.get("username"));
        assertEquals(Boolean.FALSE, role.get("rolsuper"));
        assertEquals(Boolean.FALSE, role.get("rolbypassrls"));

        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setAutoCommit(false);
            setContext(connection, f.tenant(), f.viewer(), f.project());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM project"));
            assertDenied(connection,
                    "UPDATE project SET name = 'viewer-write' WHERE id = ?", f.project());
            assertDenied(connection,
                    "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, '{PROJECT_ADMIN}')",
                    f.tenant(), f.project(), f.viewer());
            assertDenied(connection,
                    "UPDATE tenant_member SET roles = '{TENANT_ADMIN}' WHERE tenant_id = ? AND principal_id = ?",
                    f.tenant(), f.viewer());
            assertDenied(connection,
                    "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), "runtime-must-not-bootstrap", f.viewer(), f.viewer());
            assertDenied(connection,
                    "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, '{MEMBER}')",
                    f.tenant(), f.viewer());
            assertDenied(connection,
                    "UPDATE audit_event SET action = 'tampered' WHERE tenant_id = ?", f.tenant());
            assertDenied(connection, "TRUNCATE audit_event");
            connection.rollback();
        }
    }

    @Test
    void inactiveTenantDomainAndProjectAreRejectedBeforeCommands() throws Exception {
        Fixture f = fixture();
        ownerUpdate("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", f.tenant());
        assertThrows(ProjectAccessException.class,
                () -> projects.createProject(f.admin(), f.tenant(), f.domain(),
                        "INACTIVE-TENANT-" + UUID.randomUUID(), "Tenant must be active"));

        ownerUpdate("UPDATE tenant SET status = 'ACTIVE' WHERE id = ?", f.tenant());
        ownerUpdate("UPDATE domain SET status = 'SUSPENDED' WHERE id = ?", f.domain());
        assertThrows(ProjectAccessException.class,
                () -> projects.createProject(f.admin(), f.tenant(), f.domain(),
                        "INACTIVE-DOMAIN-" + UUID.randomUUID(), "Domain must be active"));

        ownerUpdate("UPDATE domain SET status = 'ACTIVE' WHERE id = ?", f.domain());
        ownerUpdate("UPDATE project SET state = 'ARCHIVED' WHERE id = ?", f.project());
        assertThrows(ProjectAccessException.class,
                () -> projects.updateProject(f.admin(), f.project(), "Project must be active", 0L));
    }

    @Test
    void auditInsertFailureRollsBackProjectAndMemberCommands() throws Exception {
        Fixture f = fixture();
        revokeRuntimeAuditInsert();
        try {
            int projectsBefore = ownerCount("SELECT COUNT(*) FROM project WHERE tenant_id = ?", f.tenant());
            assertThrows(Exception.class, () -> projects.createProject(f.admin(), f.tenant(), f.domain(),
                    "AUDIT-" + UUID.randomUUID(), "Must Roll Back"));
            assertEquals(projectsBefore, ownerCount("SELECT COUNT(*) FROM project WHERE tenant_id = ?", f.tenant()));

            int membersBefore = ownerCount("SELECT COUNT(*) FROM project_member WHERE project_id = ?", f.project());
            assertThrows(Exception.class, () -> grant(f, f.member(), List.of("PROJECT_VIEWER"), 0));
            assertEquals(membersBefore, ownerCount("SELECT COUNT(*) FROM project_member WHERE project_id = ?", f.project()));
        } finally {
            grantRuntimeAuditInsert();
        }
    }

    @Test
    void effectiveLastAdminExcludesDisabledOrInvalidAdministratorsAndAllowsValidSelfRevoke() throws Exception {
        Fixture f = fixture();
        grant(f, f.secondAdmin(), List.of("PROJECT_ADMIN"), 0);
        int baselineAudits = ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", f.project());
        setPrincipalState(f.secondAdmin(), "disabled_at = CURRENT_TIMESTAMP");
        assertThrows(ProjectAccessException.class,
                () -> projects.revokeMember(f.admin(), f.project(), f.admin(), 1L));
        assertEquals(baselineAudits, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", f.project()));
        setPrincipalState(f.secondAdmin(), "disabled_at = NULL");
        setTenantMembershipState(f.secondAdmin(), "valid_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThrows(ProjectAccessException.class,
                () -> projects.revokeMember(f.admin(), f.project(), f.admin(), 1L));
        setTenantMembershipState(f.secondAdmin(), "valid_until = NULL");

        var revoked = projects.revokeMember(f.admin(), f.project(), f.admin(), 1L);
        assertEquals("REVOKED", revoked.state());
        assertEquals(baselineAudits + 1,
                ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", f.project()));
        try (Connection connection = ownerConnection();
                var statement = connection.prepareStatement("""
                        SELECT before_roles::text, after_roles::text,
                               before_authorization_version, after_authorization_version
                        FROM audit_event
                        WHERE project_id = ? AND action = 'project.member.revoked'
                        ORDER BY at DESC, id DESC LIMIT 1""")) {
            statement.setObject(1, f.project());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("{PROJECT_ADMIN}", rows.getString(1));
                assertEquals("{PROJECT_ADMIN}", rows.getString(2));
                assertEquals(1L, rows.getLong(3));
                assertEquals(2L, rows.getLong(4));
            }
        }
    }

    @Test
    void poolOneClearsContextsAndConcurrentStrictVersionsSerialize() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        grant(a, a.viewer(), List.of("PROJECT_VIEWER"), 0);
        grant(b, b.viewer(), List.of("PROJECT_VIEWER"), 0);
        long backendPid = runtimeBackendPid();
        assertEquals(1, projects.listProjects(a.viewer(), a.tenant()).size());
        assertEquals(backendPid, runtimeBackendPid(), "application pool should reuse its sole physical connection");
        assertEquals(1, projects.listProjects(b.viewer(), b.tenant()).size());
        assertEquals(backendPid, runtimeBackendPid(), "tenant B must run on the same bounded pool connection");
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(backendPid, backendPid(connection));
            setContext(connection, a.tenant(), a.viewer(), a.project());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM project"));
            connection.rollback();
        }
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(backendPid, backendPid(connection));
            setContext(connection, b.tenant(), b.viewer(), b.project());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM project"));
            connection.rollback();
        }
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(backendPid, backendPid(connection));
            setContext(connection, null, null, null);
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM project"));
            connection.commit();
        }
        assertEquals(backendPid, runtimeBackendPid(), "clearing context must not replace the pooled connection");
        assertEquals(1, projects.listProjects(a.viewer(), a.tenant()).size());
        assertEquals(backendPid, runtimeBackendPid(), "tenant A must still be isolated after context reset");
        /*
         * Keep the original direct setting check as a regression guard: the
         * transaction-local settings must not leak after the service call.
         */
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals("", setting(connection, "test365alm.tenant_id"));
            assertEquals("", setting(connection, "test365alm.project_id"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM project"));
            connection.commit();
        }

        grant(a, a.secondAdmin(), List.of("PROJECT_ADMIN"), 0);
        try (HikariDataSource pool = runtimePool(4)) {
            ProjectService isolated = new ProjectService(new JdbcTemplate(pool));
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Throwable> attempt = () -> {
                    try {
                        tx.execute(status -> {
                            isolated.putMember(a.admin(), a.project(), a.viewer(), List.of("PROJECT_MEMBER"), 1L, false);
                            return null;
                        });
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                };
                Future<Throwable> first = workers.submit(attempt);
                Future<Throwable> second = workers.submit(attempt);
                Throwable left = first.get();
                Throwable right = second.get();
                assertTrue((left == null) ^ (right == null), "one stale writer must be rejected");
                Throwable rejected = left == null ? right : left;
                assertTrue(rejected instanceof ProjectAccessException,
                        "unexpected concurrency failure: " + rejected);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private HikariDataSource runtimePool(int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(RUNTIME_USER);
        config.setPassword(RUNTIME_PASSWORD);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        return new HikariDataSource(config);
    }

    private void revokeRuntimeAuditInsert() throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            statement.execute("REVOKE INSERT ON audit_event FROM test365alm_runtime");
        }
    }

    private void grantRuntimeAuditInsert() throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            statement.execute("GRANT INSERT ON audit_event TO test365alm_runtime");
        }
    }

    private void setPrincipalState(UUID principal, String assignment) throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE principal SET " + assignment + " WHERE id = ?")) {
            statement.setObject(1, principal);
            statement.executeUpdate();
        }
    }

    private void setTenantMembershipState(UUID principal, String assignment) throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE tenant_member SET " + assignment + " WHERE principal_id = ?")) {
            statement.setObject(1, principal);
            statement.executeUpdate();
        }
    }

    private void ownerUpdate(String sql, UUID value) throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.executeUpdate();
        }
    }

    private int ownerCount(String sql, UUID value) throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String setting(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT current_setting(?, true)")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private long runtimeBackendPid() throws SQLException {
        try (Connection connection = runtimeDataSource.getConnection()) {
            return backendPid(connection);
        }
    }

    private static long backendPid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()"); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void assertDenied(Connection connection, String sql, Object... values) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            int affected = execute(connection, sql, values);
            assertEquals(0, affected, "runtime write unexpectedly changed a protected row");
        } catch (SQLException expected) {
            // A privilege/RLS error is also a valid denial. Roll back to the
            // savepoint so the next independent denial remains observable.
        } finally {
            connection.rollback(savepoint);
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement.executeUpdate();
        }
    }

    private record Fixture(UUID admin, UUID member, UUID viewer, UUID secondAdmin, UUID tenant, UUID domain,
            UUID project) { }
}
