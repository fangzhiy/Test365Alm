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

        var latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        assertEquals(1, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT metadata_value FROM platform_metadata "
                    + "WHERE metadata_key = 'schema-purpose'");
            result.next();
            assertEquals("keep-me", result.getString(1));
            var principal = statement.executeQuery("SELECT COUNT(*) FROM principal");
            principal.next();
            assertEquals(0, principal.getInt(1));
        }
    }
}
