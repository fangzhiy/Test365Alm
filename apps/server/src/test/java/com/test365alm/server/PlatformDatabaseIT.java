package com.test365alm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.identity.PrincipalRepository;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PlatformDatabaseIT {
    private static final String POSTGRES_IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test365alm_it")
            .withUsername("test365alm_it")
            .withPassword("test365alm_it_password")
            .withInitScript("r03-test-role.sql");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private PrincipalRepository principals;

    @Test
    void oidcIdentityIsStableAndNeverMergedByDisplayName() {
        var first = principals.upsertVerified("https://issuer.example/realm", "subject-1", "Same Name");
        var again = principals.upsertVerified("https://issuer.example/realm", "subject-1", "New Name");
        var second = principals.upsertVerified("https://issuer.example/realm", "subject-2", "Same Name");
        var third = principals.upsertVerified("https://other.example/realm", "subject-1", "Same Name");
        assertEquals(first.id(), again.id());
        assertTrue(!first.id().equals(second.id()));
        assertTrue(!first.id().equals(third.id()));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM principal", Integer.class));
        jdbcTemplate.update("UPDATE principal SET disabled_at = CURRENT_TIMESTAMP WHERE id = ?", first.id());
        org.junit.jupiter.api.Assertions.assertThrows(PrincipalRepository.DisabledPrincipalException.class,
                () -> principals.upsertVerified("https://issuer.example/realm", "subject-1", "Same Name"));
    }

    @Test
    void concurrentFirstLoginCreatesOnePrincipal() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<java.util.UUID>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> principals.upsertVerified(
                        "https://issuer.example/concurrent", "same-subject", "Concurrent").id()));
            }
            var ids = new HashSet<java.util.UUID>();
            for (var task : tasks) ids.add(task.get());
            assertEquals(1, ids.size());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM principal WHERE issuer = 'https://issuer.example/concurrent'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runtimeRoleHasDmlButNoDdlOrBypassRls() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "test365alm_runtime", "r03_isolated_test_role_only");
                var statement = connection.createStatement()) {
            assertTrue(statement.executeQuery("SELECT 1 FROM platform_metadata").next());
            assertTrue(statement.executeQuery("SELECT 1 FROM flyway_schema_history").next());
            assertTrue(statement.executeQuery("SELECT COUNT(*) FROM principal").next());
            assertEquals(1, statement.executeUpdate("INSERT INTO principal (issuer, subject, display_name) "
                    + "VALUES ('https://role.example', 'runtime', 'Runtime')"));
            assertEquals(1, statement.executeUpdate("UPDATE principal SET display_name = 'Updated' "
                    + "WHERE issuer = 'https://role.example' AND subject = 'runtime'"));
            var roles = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user");
            assertTrue(roles.next());
            assertTrue(!roles.getBoolean(1));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE forbidden_ddl (id integer)"));
        }
    }

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
    void unauthenticatedIdentityRequestsNeverTrustHeadersOrReturnLoginHtml() throws Exception {
        HttpRequest forged = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("X-User-Id", "admin")
                .header("X-Tenant-Id", "admin")
                .header("Cookie", "JSESSIONID=damaged")
                .GET().build();
        var response = HttpClient.newHttpClient().send(forged, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("UNAUTHENTICATED"));

        var csrf = request("/api/v1/csrf");
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.body().contains("headerName"));
        var logout = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, HttpClient.newHttpClient().send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
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
