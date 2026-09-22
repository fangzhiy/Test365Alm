package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformDatabaseIT {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private BuildProperties buildProperties;

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
    void rerunningFlywayPreservesExistingMetadata() throws Exception {
        jdbcTemplate.update(
                "UPDATE platform_metadata SET metadata_value = ? WHERE metadata_key = 'schema-purpose'",
                "preserved-by-integration-test");

        flyway.migrate();

        String value = jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM platform_metadata WHERE metadata_key = 'schema-purpose'", String.class);
        assertEquals("preserved-by-integration-test", value);
    }

    @Test
    void readinessDistinguishesReachableDatabaseFromMissingRequiredStructure() throws Exception {
        // This test only mutates the isolated integration database. Restore the
        // migration-owned table in finally so the shared test context remains usable.
        jdbcTemplate.execute("DROP TABLE platform_metadata");
        try {
            HttpResponse<String> ready = request("/health/ready");
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), ready.statusCode());
            assertTrue(ready.body().contains("\"database\":\"UP\""));
            assertTrue(ready.body().contains("\"migration\":\"NOT_APPLIED\""));
        } finally {
            jdbcTemplate.execute("""
                    CREATE TABLE platform_metadata (
                        metadata_key TEXT PRIMARY KEY,
                        metadata_value TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbcTemplate.update(
                    "INSERT INTO platform_metadata (metadata_key, metadata_value) VALUES (?, ?)",
                    "schema-purpose", "Test365Alm platform bootstrap metadata");
        }
    }

    private HttpResponse<String> request(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
