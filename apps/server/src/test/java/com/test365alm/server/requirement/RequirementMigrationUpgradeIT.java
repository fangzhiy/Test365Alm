package com.test365alm.server.requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.test365alm.server.project.ProjectAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Upgrade proof for V7 rows and the explicit safe rejection of legacy replays. */
@ActiveProfiles("integration")
// Keep a real web application context so the production security filter chain
// and runtime service wiring are exercised.  The test invokes the service
// boundary directly after owner-controlled migration setup, but a non-web
// context would remove HttpSecurity and fail before the test starts.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class RequirementMigrationUpgradeIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String OWNER = "requirement_upgrade_owner";
    private static final String PASSWORD = "requirement_upgrade_owner_password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("requirement_upgrade_it")
            .withUsername(OWNER)
            .withPassword(PASSWORD)
            .withInitScript("r03-test-role.sql");

    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Autowired
    private RequirementService requirements;

    @DynamicPropertySource
    static void runtimeDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Test
    void v7RowsUpgradeRejectsUnsafeLegacyReplayThroughRuntimeService() throws Exception {
        Flyway v7 = flyway("7");
        assertEquals(7, v7.migrate().migrationsExecuted);

        UUID admin = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID requirement = UUID.randomUUID();
        UUID revisionOne = UUID.randomUUID();
        UUID revisionTwo = UUID.randomUUID();
        String key = "v7-upgrade-" + UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                    admin, "https://requirement-upgrade.example/realm", "admin-" + admin, "Upgrade Admin");
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)",
                    tenant, "upgrade-tenant-" + tenant, "Upgrade Tenant", admin);
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)",
                    domain, tenant, "Upgrade Domain");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    tenant, admin, "{TENANT_ADMIN}");
            execute(connection, "INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                    project, tenant, domain, "UPG-" + project, "Upgrade Project", admin);
            execute(connection, "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ?::text[])",
                    tenant, project, admin, "{PROJECT_MEMBER}");
            execute(connection, "INSERT INTO requirement (tenant_id, project_id, id, display_number, row_version, created_by)"
                    + " VALUES (?, ?, ?, 1, 2, ?)", tenant, project, requirement, admin);
            execute(connection, "INSERT INTO requirement_revision"
                    + " (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)"
                    + " VALUES (?, ?, ?, ?, 1, 'V7 title', 'V7 body', 'HIGH', ?)",
                    tenant, project, revisionOne, requirement, admin);
            execute(connection, "INSERT INTO requirement_revision"
                    + " (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)"
                    + " VALUES (?, ?, ?, ?, 2, 'Current title', 'Current body', 'CRITICAL', ?)",
                    tenant, project, revisionTwo, requirement, admin);
            execute(connection, "UPDATE requirement SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE tenant_id = ? AND project_id = ? AND id = ?", revisionTwo, tenant, project, requirement);
            execute(connection, "INSERT INTO outbox_event"
                    + " (tenant_id, project_id, aggregate_id, requirement_id, revision_id, event_type)"
                    + " VALUES (?, ?, ?, ?, ?, 'requirement.created')", tenant, project, requirement, requirement, revisionOne);
            execute(connection, "INSERT INTO requirement_idempotency"
                    + " (tenant_id, project_id, principal_id, route, idempotency_key, request_hash, requirement_id, revision_id)"
                    + " VALUES (?, ?, ?, 'requirements:create', ?, ?, ?, ?)",
                    tenant, project, admin, key, legacyCreateHash("V7 title", "V7 body", "HIGH"), requirement, revisionOne);
        }

        Flyway latest = flyway(null);
        assertEquals(2, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted, "V8/V9 must be idempotent after the upgrade");

        try (Connection connection = ownerConnection()) {
            assertEquals(9, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = TRUE"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = TRUE"));
            assertEquals(2, scalar(connection, "SELECT row_version FROM requirement WHERE id = ?", requirement));
            assertEquals(revisionTwo, uuid(connection, "SELECT current_revision_id FROM requirement WHERE id = ?", requirement));
            assertEquals("V7 title", text(connection, "SELECT title FROM requirement_revision WHERE id = ?", revisionOne));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", requirement));
            assertEquals("V7 title", text(connection, "SELECT result_title FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals("V7 body", text(connection, "SELECT result_body FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(1, scalar(connection, "SELECT result_revision_no FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(2, scalar(connection, "SELECT result_row_version FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(1, scalar(connection, "SELECT result_display_number FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertNotNull(text(connection, "SELECT result_created_at::text FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(admin, uuid(connection, "SELECT result_created_by FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(legacyCreateHash("V7 title", "V7 body", "HIGH"),
                    text(connection, "SELECT request_hash FROM requirement_idempotency WHERE idempotency_key = ?", key));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM requirement_idempotency"
                    + " WHERE idempotency_key = ? AND replay_compatible = TRUE", key));
        }

        // Exercise the upgraded record through the actual application service
        // and its restricted runtime datasource.  The old hash is intentionally
        // not accepted by the current length-prefixed request hashing scheme.
        int requirementsBefore = countForProject("requirement", project);
        int revisionsBefore = countForProject("requirement_revision", project);
        int outboxBefore = countForProject("outbox_event", project);
        ProjectAccessException legacy = org.junit.jupiter.api.Assertions.assertThrows(ProjectAccessException.class,
                () -> requirements.create(admin, project,
                        new RequirementService.CreateCommand("V7 title", "V7 body", "HIGH"), key));
        assertEquals("IDEMPOTENCY_LEGACY_UNSUPPORTED", legacy.code());
        assertEquals(HttpStatus.CONFLICT, legacy.status());
        assertEquals(requirementsBefore, countForProject("requirement", project));
        assertEquals(revisionsBefore, countForProject("requirement_revision", project));
        assertEquals(outboxBefore, countForProject("outbox_event", project));

        String newKey = "current-format-" + UUID.randomUUID();
        RequirementService.RequirementView created = requirements.create(admin, project,
                new RequirementService.CreateCommand("Current format", "new body", "LOW"), newKey);
        RequirementService.RequirementView replay = requirements.create(admin, project,
                new RequirementService.CreateCommand("Current format", "new body", "LOW"), newKey);
        assertEquals(created.id(), replay.id());
        assertEquals(created.rowVersion(), replay.rowVersion());

        try (Connection connection = ownerConnection()) {
            execute(connection, "UPDATE project_member SET revoked_at = CURRENT_TIMESTAMP"
                    + " WHERE project_id = ? AND principal_id = ?", project, admin);
        }
        ProjectAccessException revoked = org.junit.jupiter.api.Assertions.assertThrows(ProjectAccessException.class,
                () -> requirements.create(admin, project,
                        new RequirementService.CreateCommand("V7 title", "V7 body", "HIGH"), key));
        // The runtime RLS policy hides revoked project rows.  A safe replay
        // denial can therefore be explicit FORBIDDEN or resource-hiding
        // NOT_FOUND, but it must never return the old snapshot.
        assertTrue(revoked.status() == HttpStatus.FORBIDDEN || revoked.status() == HttpStatus.NOT_FOUND);
        assertEquals(requirementsBefore + 1, countForProject("requirement", project));
    }

    private int countForProject(String table, UUID project) throws SQLException {
        try (Connection connection = ownerConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?")) {
            statement.setObject(1, project);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String legacyCreateHash(String title, String body, String priority) {
        String canonical = String.join("\u0000", title, body, priority) + "\u0000";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Flyway flyway(String target) {
        FluentConfiguration configure = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER, PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) configure.target(target);
        return configure.load();
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

