package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PrincipalUpgradeIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("r03_upgrade")
            .withUsername("r03_upgrade")
            .withPassword("synthetic_test_only");

    @Test
    void v1DataSurvivesUpgradeToV2() throws Exception {
        var v1 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("1").load();
        assertEquals(1, v1.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE platform_metadata SET metadata_value = 'keep-me' "
                    + "WHERE metadata_key = 'schema-purpose'");
        }

        var v2 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("2").load();
        assertEquals(1, v2.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO principal (issuer, subject, display_name) "
                    + "VALUES ('https://upgrade.example', 'survives-v2', 'Survives V2')");
        }

        var latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        // V3 through V11 (including the requirement slice, its integrity
        // boundary, legacy replay guard and sealed manual test-case slice)
        // are pending after the V2 checkpoint; each must apply exactly once.
        assertEquals(9, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT metadata_value FROM platform_metadata "
                    + "WHERE metadata_key = 'schema-purpose'");
            result.next();
            assertEquals("keep-me", result.getString(1));
            var principal = statement.executeQuery("SELECT COUNT(*) FROM principal WHERE issuer = 'https://upgrade.example' "
                    + "AND subject = 'survives-v2' AND display_name = 'Survives V2'");
            principal.next();
            assertEquals(1, principal.getInt(1));
        }
    }
}
