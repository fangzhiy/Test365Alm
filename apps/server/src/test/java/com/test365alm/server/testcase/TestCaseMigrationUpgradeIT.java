package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

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

/** Real V9-to-V10 upgrade proof with a pre-existing V9 row. */
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
    void v9DataSurvivesV10Upgrade() throws Exception {
        Flyway v9 = flyway("9");
        assertEquals(9, v9.migrate().migrationsExecuted);
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO platform_metadata (metadata_key, metadata_value) VALUES (?, ?)",
                    "r05-v9-sentinel", "preserved before manual test case migration");
        }

        Flyway latest = flyway(null);
        assertEquals(1, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted, "V10 must be idempotent after the upgrade");
        try (Connection connection = ownerConnection()) {
            assertEquals(10, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = TRUE"));
            assertEquals("preserved before manual test case migration",
                    text(connection, "SELECT metadata_value FROM platform_metadata WHERE metadata_key = ?", "r05-v9-sentinel"));
            assertTrue(text(connection, "SELECT to_regclass('public.test_case')") != null);
            assertTrue(text(connection, "SELECT to_regclass('public.test_revision')") != null);
            assertTrue(text(connection, "SELECT to_regclass('public.test_step')") != null);
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM test_case"));
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
}

