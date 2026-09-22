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
        assertTrue(version.body().contains("\"commit\":\"local-r02-integration\""));
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
