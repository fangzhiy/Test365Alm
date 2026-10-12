package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves destructive readiness injection is confined to a disposable target
 * container and cannot change a separate temporary comparison database.
 */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PlatformDatabaseIsolationIT {
    private static final String POSTGRES_IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";

    @Container
    static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test365alm_target")
            .withUsername("test365alm_target")
            .withPassword("test365alm_target_password");

    @Container
    static final PostgreSQLContainer<?> CONTROL = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test365alm_control")
            .withUsername("test365alm_control")
            .withPassword("test365alm_control_password");

    @DynamicPropertySource
    static void registerTargetDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TARGET::getJdbcUrl);
        registry.add("spring.datasource.username", TARGET::getUsername);
        registry.add("spring.datasource.password", TARGET::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate targetJdbc;

    @Test
    void destructiveTargetCheckLeavesSeparateControlDatabaseUntouched() throws Exception {
        assertTrue(TARGET.isRunning());
        assertTrue(CONTROL.isRunning());
        assertNotEquals(TARGET.getContainerId(), CONTROL.getContainerId());

        JdbcTemplate controlJdbc = new JdbcTemplate(new DriverManagerDataSource(
                CONTROL.getJdbcUrl(), CONTROL.getUsername(), CONTROL.getPassword()));
        controlJdbc.execute("CREATE TABLE isolation_sentinel (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
        controlJdbc.update("INSERT INTO isolation_sentinel (id, value) VALUES (1, ?), (2, ?)", "one", "two");

        targetJdbc.execute("DROP TABLE platform_metadata");
        HttpResponse<String> ready = request("/health/ready");
        assertEquals(503, ready.statusCode());
        assertTrue(ready.body().contains("\"database\":\"UP\""));
        assertTrue(ready.body().contains("\"migration\":\"NOT_APPLIED\""));

        assertEquals(2, controlJdbc.queryForObject("SELECT COUNT(*) FROM isolation_sentinel", Integer.class));
        assertEquals("one", controlJdbc.queryForObject(
                "SELECT value FROM isolation_sentinel WHERE id = 1", String.class));
        assertEquals("two", controlJdbc.queryForObject(
                "SELECT value FROM isolation_sentinel WHERE id = 2", String.class));
    }

    private HttpResponse<String> request(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
