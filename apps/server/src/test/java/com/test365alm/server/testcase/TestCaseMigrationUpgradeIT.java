package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real V9-to-V11 upgrade proof with a pre-existing V9 row. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class TestCaseMigrationUpgradeIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String OWNER = "test_case_upgrade_owner";
    private static final String PASSWORD = "test_case_upgrade_owner_password";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test_case_upgrade_it")
            .withUsername(OWNER)
            .withPassword(PASSWORD)
            .withInitScript("r03-test-role.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Hold the application at the runtime boundary. This test owns the
        // upgrade and invokes Flyway with the migration identity explicitly.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Test
    void v9DataSurvivesV11Upgrade() throws Exception {
        Flyway v9 = flyway("9");
        assertEquals(9, v9.migrate().migrationsExecuted);
        UUID principal = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID requirement = UUID.randomUUID();
        UUID revisionOne = UUID.randomUUID();
        UUID revisionTwo = UUID.randomUUID();
        UUID testCase = UUID.randomUUID();
        UUID testRevision = UUID.randomUUID();
        UUID testStep = UUID.randomUUID();
        String legacyKey = "v9-legacy-" + UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO platform_metadata (metadata_key, metadata_value) VALUES (?, ?)",
                    "r05-v9-sentinel", "preserved before manual test case migration");
            execute(connection, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                    principal, "https://r05-upgrade.example/realm", "v9-principal-" + principal, "V9 Principal");
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)",
                    tenant, "r05-v9-tenant-" + tenant, "V9 Tenant", principal);
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)",
                    domain, tenant, "V9 Domain");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    tenant, principal, "{TENANT_ADMIN}");
            execute(connection, "INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                    project, tenant, domain, "V9-PROJECT-" + project, "V9 Project", principal);
            execute(connection, "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ?::text[])",
                    tenant, project, principal, "{PROJECT_MEMBER}");
            execute(connection, "INSERT INTO requirement (tenant_id, project_id, id, display_number, row_version, created_by) VALUES (?, ?, ?, 1, 2, ?)",
                    tenant, project, requirement, principal);
            execute(connection, "INSERT INTO requirement_revision"
                    + " (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)"
                    + " VALUES (?, ?, ?, ?, 1, 'V9 original', 'V9 original body', 'HIGH', ?)",
                    tenant, project, revisionOne, requirement, principal);
            execute(connection, "INSERT INTO requirement_revision"
                    + " (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)"
                    + " VALUES (?, ?, ?, ?, 2, 'V9 current', 'V9 current body', 'CRITICAL', ?)",
                    tenant, project, revisionTwo, requirement, principal);
            execute(connection, "UPDATE requirement SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE tenant_id = ? AND project_id = ? AND id = ?", revisionTwo, tenant, project, requirement);
            execute(connection, "INSERT INTO requirement_number_allocator (tenant_id, project_id, next_number) VALUES (?, ?, 2)",
                    tenant, project);
            execute(connection, "INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id, object_revision)"
                    + " VALUES (?, ?, ?, 'requirement.created', 'requirement', ?, 1)",
                    tenant, project, principal, requirement);
            execute(connection, "INSERT INTO outbox_event"
                    + " (tenant_id, project_id, aggregate_id, requirement_id, revision_id, event_type)"
                    + " VALUES (?, ?, ?, ?, ?, 'requirement.created')",
                    tenant, project, requirement, requirement, revisionOne);
            execute(connection, "INSERT INTO requirement_idempotency"
                    + " (tenant_id, project_id, principal_id, route, idempotency_key, request_hash, requirement_id, revision_id)"
                    + " VALUES (?, ?, ?, 'requirements:create', ?, ?, ?, ?)",
                    tenant, project, principal, legacyKey, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    requirement, revisionOne);
        }

        Flyway v10 = flyway("10");
        assertEquals(1, v10.migrate().migrationsExecuted);
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO test_case"
                    + " (tenant_id, project_id, id, display_number, test_type, row_version, created_by)"
                    + " VALUES (?, ?, ?, 1, 'MANUAL', 1, ?)", tenant, project, testCase, principal);
            execute(connection, "INSERT INTO test_revision"
                    + " (tenant_id, project_id, test_case_id, id, revision_no, title, description, preconditions, created_by)"
                    + " VALUES (?, ?, ?, ?, 1, 'V10 case', 'V10 description', 'V10 setup', ?)",
                    tenant, project, testCase, testRevision, principal);
            execute(connection, "INSERT INTO test_step"
                    + " (tenant_id, project_id, test_case_id, revision_id, step_key, ordinal, action, expected)"
                    + " VALUES (?, ?, ?, ?, ?, 1, 'V10 action', 'V10 expected')",
                    tenant, project, testCase, testRevision, testStep);
            execute(connection, "UPDATE test_case SET current_revision_id = ? WHERE id = ?", testRevision, testCase);
        }

        Flyway latest = flyway(null);
        assertEquals(6, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted, "V10/V11/V12/V13/V14/V15/V16 must be idempotent after the upgrade");
        try (Connection connection = ownerConnection()) {
            assertEquals(16, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = TRUE"));
            assertEquals("preserved before manual test case migration",
                    text(connection, "SELECT metadata_value FROM platform_metadata WHERE metadata_key = ?", "r05-v9-sentinel"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM principal WHERE id = ?", principal));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM tenant_member WHERE tenant_id = ? AND principal_id = ?", tenant, principal));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM project_member WHERE tenant_id = ? AND project_id = ? AND principal_id = ?",
                    tenant, project, principal));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", requirement));
            assertEquals(revisionTwo, uuid(connection, "SELECT current_revision_id FROM requirement WHERE id = ?", requirement));
            assertEquals(2, scalar(connection, "SELECT row_version FROM requirement WHERE id = ?", requirement));
            assertEquals("V9 original", text(connection, "SELECT title FROM requirement_revision WHERE id = ?", revisionOne));
            assertEquals("V9 current body", text(connection, "SELECT body FROM requirement_revision WHERE id = ?", revisionTwo));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM audit_event WHERE object_id = ?", requirement));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", requirement));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM requirement_idempotency WHERE idempotency_key = ?", legacyKey));
            assertEquals(false, bool(connection, "SELECT replay_compatible FROM requirement_idempotency WHERE idempotency_key = ?", legacyKey));
            assertTrue(text(connection, "SELECT to_regclass('public.test_case')") != null);
            assertTrue(text(connection, "SELECT to_regclass('public.test_revision')") != null);
            assertTrue(text(connection, "SELECT to_regclass('public.test_step')") != null);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_case WHERE id = ?", testCase));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_revision WHERE id = ? AND sealed_at IS NOT NULL", testRevision));
            assertEquals("V10 action", text(connection, "SELECT action FROM test_step WHERE step_key = ?", testStep));
        }
    }

    private static Flyway flyway(String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER, PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER, PASSWORD);
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static long scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String text(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static boolean bool(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static UUID uuid(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }
}
