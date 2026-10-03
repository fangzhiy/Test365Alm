package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * HTTP boundary smoke proof.  Authenticated member/VIEWER matrices are kept
 * in the OIDC-owned integration fixture; this class ensures the new routes do
 * not accidentally become anonymous while that fixture is unavailable.
 */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class TestCaseHttpSecurityIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test_case_http_it")
            .withUsername("test_case_http_owner")
            .withPassword("test_case_http_owner_password")
            .withInitScript("r03-test-role.sql");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Test
    void httpMatrixRejectsUnauthorizedAndInvalidInput() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/projects/"
                + UUID.randomUUID() + "/tests");
        HttpResponse<String> list = client.send(HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, list.statusCode());
        HttpResponse<String> create = client.send(HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Idempotency-Key", "anonymous-test-key")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        // Spring Security's CSRF filter runs before the authentication entry
        // point for this state-changing request, so the contract is 403.
        assertEquals(403, create.statusCode());
    }
}

