package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(120)
class OidcCallbackSecurityIT {
    private static final String POSTGRES_IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";
    private static final String CLIENT_ID = "test365alm-web";
    private static final String WEB_ORIGIN = "http://127.0.0.1:5173";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final OffsetDateTime BASELINE_TIMESTAMP = OffsetDateTime.ofInstant(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalOidcProvider IDP = LocalOidcProvider.start();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("r03_oidc_callback_it")
            .withUsername("r03_oidc_owner")
            .withPassword("r03_oidc_owner_password")
            .withInitScript("r03-test-role.sql");

    @LocalServerPort
    private int appPort;

    /**
     * This is deliberately the application datasource, not the owner
     * connection used by the fixture helpers below.  Keeping this assertion
     * in the real HTTP callback suite prevents a test from accidentally
     * passing while the application has silently fallen back to the migration
     * owner.
     */
    @Autowired
    private JdbcTemplate runtimeJdbc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("test365alm.oidc.enabled", () -> "true");
        registry.add("test365alm.web.origin", () -> WEB_ORIGIN);
        registry.add("spring.security.oauth2.client.registration.test365alm.provider", () -> "test365alm");
        registry.add("spring.security.oauth2.client.registration.test365alm.client-id", () -> CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.test365alm.client-authentication-method", () -> "none");
        registry.add("spring.security.oauth2.client.registration.test365alm.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.test365alm.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.test365alm.scope",
                () -> "openid,profile,email");
        registry.add("spring.security.oauth2.client.provider.test365alm.issuer-uri", IDP::issuer);
    }

    @AfterAll
    static void stopProvider() {
        IDP.stop();
    }

    @Test
    void realHttpApplicationUsesRestrictedRuntimeRole() {
        Map<String, Object> role = runtimeJdbc.queryForMap(
                "SELECT current_user AS username, r.rolsuper, r.rolbypassrls "
                        + "FROM pg_roles r WHERE r.rolname = current_user");
        assertEquals(RUNTIME_USER, role.get("username"));
        assertEquals(Boolean.FALSE, role.get("rolsuper"));
        assertEquals(Boolean.FALSE, role.get("rolbypassrls"));
        assertTrue(runtimeJdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class) >= 1);
        org.springframework.dao.DataAccessException ddlFailure = null;
        try {
            runtimeJdbc.execute("CREATE TABLE r03_runtime_must_not_ddl (id integer)");
        } catch (org.springframework.dao.DataAccessException expected) {
            ddlFailure = expected;
        }
        assertNotNull(ddlFailure, "the HTTP application's runtime role must not be able to run DDL");
    }

    @Test
    void realOidcSessionCanUseProjectHttpEndpointsThroughRuntimeDatasource() throws Exception {
        String subject = uniqueSubject("project-http");
        Flow flow = runAuthorization(Variant.VALID, subject);
        UUID actor;
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("SELECT id FROM principal WHERE issuer = ? AND subject = ?")) {
            statement.setString(1, IDP.issuer());
            statement.setString(2, subject);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the real OIDC callback must persist the principal before project access");
                actor = rows.getObject(1, UUID.class);
            }
        }
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.prepareStatement("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)")) {
                statement.setObject(1, tenant);
                statement.setString(2, "http-tenant-" + tenant);
                statement.setString(3, "HTTP Tenant");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)")) {
                statement.setObject(1, domain);
                statement.setObject(2, tenant);
                statement.setString(3, "HTTP Domain");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['TENANT_ADMIN']::text[])")) {
                statement.setObject(1, tenant);
                statement.setObject(2, actor);
                statement.executeUpdate();
            }
        }
        HttpResponse<String> tenants = get(flow.client, "/api/v1/tenants");
        assertEquals(200, tenants.statusCode());
        assertTrue(tenants.body().contains(tenant.toString()));
        HttpResponse<String> domains = get(flow.client, "/api/v1/domains?tenantId=" + tenant);
        assertEquals(200, domains.statusCode());
        assertTrue(domains.body().contains(domain.toString()));

        HttpResponse<String> csrf = get(flow.client, "/api/v1/csrf");
        assertEquals(200, csrf.statusCode());
        String csrfHeader = jsonField(csrf.body(), "headerName");
        String csrfToken = jsonField(csrf.body(), "token");
        HttpRequest create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/api/v1/projects"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + tenant
                        + "\",\"domainId\":\"" + domain + "\",\"code\":\"HTTP-PROJECT-"
                        + tenant + "\",\"name\":\"HTTP Project\"}"))
                .build();
        HttpResponse<String> created = flow.client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        String projectId = jsonField(created.body(), "id");
        HttpRequest missingVersion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort
                + "/api/v1/projects/" + projectId + "/members/" + actor))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"roles\":[\"PROJECT_VIEWER\"]}"))
                .build();
        assertEquals(400, flow.client.send(missingVersion, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest staleVersion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort
                + "/api/v1/projects/" + projectId + "/members/" + actor))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"roles\":[\"PROJECT_VIEWER\"],\"authorizationVersion\":99}"))
                .build();
        HttpResponse<String> visibleBeforeStale = get(flow.client, "/api/v1/projects/" + projectId);
        assertEquals(200, visibleBeforeStale.statusCode(), visibleBeforeStale.body());
        HttpResponse<String> stale = flow.client.send(staleVersion, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, stale.statusCode(), stale.body());
        HttpResponse<String> project = get(flow.client, "/api/v1/projects/" + projectId);
        assertEquals(200, project.statusCode());
        assertTrue(project.body().contains("HTTP Project"));
    }

    @Test
    void unbootstrappedOidcSubjectCannotCreateTenantOrProjectAccess() throws Exception {
        String subject = uniqueSubject("unbootstrapped");
        PrincipalSnapshot before = snapshot();
        int tenantsBefore = ownerCount("tenant");
        int projectsBefore = ownerCount("project");
        Flow flow = runAuthorization(Variant.VALID, subject);
        assertEquals(200, get(flow.client, "/api/v1/me").statusCode());
        HttpResponse<String> tenants = get(flow.client, "/api/v1/tenants");
        assertEquals(200, tenants.statusCode());
        assertEquals("[]", tenants.body().trim());
        HttpResponse<String> csrf = get(flow.client, "/api/v1/csrf");
        String csrfHeader = jsonField(csrf.body(), "headerName");
        String csrfToken = jsonField(csrf.body(), "token");
        HttpRequest create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/api/v1/projects"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"domainId\":\"" + UUID.randomUUID()
                        + "\",\"code\":\"UNBOOTSTRAPPED\",\"name\":\"Denied\"}"))
                .build();
        HttpResponse<String> denied = flow.client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertEquals(tenantsBefore, ownerCount("tenant"));
        assertEquals(projectsBefore, ownerCount("project"));
        PrincipalSnapshot after = snapshot();
        assertEquals(before.rows.size() + 1, after.rows.size());
        assertTrue(after.has(IDP.issuer(), subject));
    }

    @Test
    void legalTokenCompletesHttpCallbackAndPersistsPrincipal() throws Exception {
        String subject = uniqueSubject("legal");
        PrincipalSnapshot before = snapshot();
        Flow flow = runAuthorization(Variant.VALID, subject);

        assertEquals(1, flow.scenario.tokenRequests.get());
        assertTrue(flow.scenario.protocolValid);
        assertEquals(302, flow.callback.statusCode());
        assertFalse(Objects.requireNonNull(flow.callback.headers().firstValue("location").orElse(""))
                .contains("login=failed"));
        HttpResponse<String> me = get(flow.client, "/api/v1/me");
        assertEquals(200, me.statusCode());
        assertTrue(me.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(me.body().contains(subject));
        PrincipalSnapshot after = snapshot();
        assertEquals(before.rows.size() + 1, after.rows.size());
        assertTrue(after.has(IDP.issuer(), subject));
    }

    @Test
    void wrongSignatureIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite() throws Exception {
        assertRejected(Variant.WRONG_SIGNATURE);
    }

    @Test
    void wrongIssuerIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite() throws Exception {
        assertRejected(Variant.WRONG_ISSUER);
    }

    @Test
    void wrongAudienceIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite() throws Exception {
        assertRejected(Variant.WRONG_AUDIENCE);
    }

    @Test
    void expiredTokenIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite() throws Exception {
        assertRejected(Variant.EXPIRED);
    }

    @Test
    void wrongNonceIsRejectedAfterRealTokenExchangeWithoutPrincipalWrite() throws Exception {
        assertRejected(Variant.WRONG_NONCE);
    }

    @Test
    void failedCallbackDoesNotPoisonClientAndFreshLegalAuthorizationRecovers() throws Exception {
        ClientFixture fixture = newClientFixture();
        seedEnabledBaseline(Variant.WRONG_AUDIENCE);
        String invalidSubject = uniqueSubject("recovery-invalid");
        PrincipalSnapshot before = snapshot();
        Flow rejected = runAuthorization(Variant.WRONG_AUDIENCE, invalidSubject, fixture.client());
        assertUnauthenticated(rejected, before, invalidSubject, false);
        assertSame(fixture.client(), rejected.client);
        assertSame(fixture.cookies(), rejected.client().cookieHandler().orElseThrow());

        String legalSubject = uniqueSubject("recovery-legal");
        Flow recovered = runAuthorization(Variant.VALID, legalSubject, fixture.client());
        assertSame(fixture.client(), recovered.client);
        assertSame(fixture.cookies(), recovered.client().cookieHandler().orElseThrow());
        assertEquals(200, get(recovered.client, "/api/v1/me").statusCode());
        assertTrue(snapshot().has(IDP.issuer(), legalSubject));
    }

    private void assertRejected(Variant variant) throws Exception {
        seedEnabledBaseline(variant);
        PrincipalSnapshot before = snapshot();
        assertFalse(before.rows().isEmpty(), "invalid-token checks require a non-empty baseline");
        String absentSubject = absentSubject(variant);
        String existingSubject = existingSubject(variant);
        assertFalse(before.has(IDP.issuer(), absentSubject));
        assertTrue(before.enabled(targetIssuer(variant), existingSubject));
        assertRejectedForSubject(variant, absentSubject, before, false);
        assertRejectedForSubject(variant, existingSubject, before, true);
    }

    private void assertRejectedForSubject(Variant variant, String subject, PrincipalSnapshot before,
            boolean subjectPresent) throws Exception {
        Flow flow = runAuthorization(variant, subject);
        assertUnauthenticated(flow, before, subject, subjectPresent);
        assertTrue(flow.scenario.protocolValid);
        assertEquals(1, flow.scenario.tokenRequests.get());
    }

    private void assertUnauthenticated(Flow flow, PrincipalSnapshot before, String subject,
            boolean subjectPresent) throws Exception {
        assertEquals(302, flow.callback.statusCode());
        String location = flow.callback.headers().firstValue("location").orElse("");
        assertTrue(location.contains("login=failed"), "OIDC failure must use the configured failure redirect");
        HttpResponse<String> me = get(flow.client, "/api/v1/me");
        assertEquals(401, me.statusCode());
        assertTrue(me.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(me.body().contains("UNAUTHENTICATED"));
        PrincipalSnapshot after = snapshot();
        assertEquals(before, after, "an invalid token must not insert or update any principal");
        assertEquals(subjectPresent, after.has(targetIssuer(flow.scenario.variant), subject));
    }

    private Flow runAuthorization(Variant variant, String subject) throws Exception {
        return runAuthorization(variant, subject, newClientFixture().client());
    }

    private Flow runAuthorization(Variant variant, String subject, HttpClient client) throws Exception {
        Scenario scenario = IDP.prepare(variant, subject);

        HttpResponse<String> start = get(client, "/oauth2/authorization/test365alm");
        assertEquals(302, start.statusCode());
        URI authorization = URI.create(start.headers().firstValue("location").orElseThrow());
        assertEquals(IDP.authorizationEndpoint(), withoutQuery(authorization));
        Map<String, String> request = query(authorization);
        assertEquals("code", request.get("response_type"));
        assertEquals(CLIENT_ID, request.get("client_id"));
        assertTrue(request.getOrDefault("scope", "").contains("openid"));
        assertEquals("S256", request.get("code_challenge_method"));
        assertTrue(request.get("state") != null && !request.get("state").isBlank());
        assertTrue(request.get("nonce") != null && !request.get("nonce").isBlank());
        assertTrue(request.get("redirect_uri").startsWith("http://127.0.0.1:" + appPort));
        assertTrue(request.get("code_challenge") != null && !request.get("code_challenge").isBlank());

        HttpResponse<String> authorizationResponse = client.send(HttpRequest.newBuilder(authorization)
                .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, authorizationResponse.statusCode());
        URI callback = URI.create(authorizationResponse.headers().firstValue("location").orElseThrow());
        assertEquals("/login/oauth2/code/test365alm", callback.getPath());
        assertEquals(request.get("state"), query(callback).get("state"));
        assertTrue(query(callback).get("code") != null && !query(callback).get("code").isBlank());

        HttpResponse<String> callbackResponse = client.send(HttpRequest.newBuilder(callback)
                .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(scenario.tokenRequests.get() == 1, "application must exchange the one-time authorization code");
        return new Flow(client, scenario, callbackResponse);
    }

    private ClientFixture newClientFixture() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        assertSame(cookies, client.cookieHandler().orElseThrow(), "fixture must retain its CookieManager");
        return new ClientFixture(client, cookies);
    }

    private void seedEnabledBaseline(Variant variant) throws Exception {
        String target = existingSubject(variant);
        String sentinelA = sentinelSubject(variant, "a");
        String sentinelB = sentinelSubject(variant, "b");
        String targetIssuer = targetIssuer(variant);
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        INSERT INTO principal (id, issuer, subject, display_name, disabled_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, NULL, ?, ?)
                        ON CONFLICT (issuer, subject) DO NOTHING
                        """)) {
            insertBaseline(statement, targetIssuer, target, "FIX02 existing target");
            insertBaseline(statement, IDP.issuer(), sentinelA, "FIX02 baseline sentinel A");
            insertBaseline(statement, IDP.issuer(), sentinelB, "FIX02 baseline sentinel B");
        }
    }

    private void insertBaseline(java.sql.PreparedStatement statement, String issuer, String subject, String displayName)
            throws java.sql.SQLException {
        statement.setObject(1, baselineId(subject));
        statement.setString(2, issuer);
        statement.setString(3, subject);
        statement.setString(4, displayName);
        statement.setObject(5, BASELINE_TIMESTAMP);
        statement.setObject(6, BASELINE_TIMESTAMP);
        statement.executeUpdate();
    }

    private static UUID baselineId(String subject) {
        return UUID.nameUUIDFromBytes(("r03-fix02-baseline:" + subject).getBytes(StandardCharsets.UTF_8));
    }

    private static String absentSubject(Variant variant) {
        return "r03-fix02-" + variant.name().toLowerCase() + "-absent-target";
    }

    private static String existingSubject(Variant variant) {
        return "r03-fix02-" + variant.name().toLowerCase() + "-existing-target";
    }

    private static String sentinelSubject(Variant variant, String suffix) {
        return "r03-fix02-" + variant.name().toLowerCase() + "-baseline-sentinel-" + suffix;
    }

    private static String targetIssuer(Variant variant) {
        return variant == Variant.WRONG_ISSUER ? "https://example.invalid/r03-wrong-issuer" : IDP.issuer();
    }

    private PrincipalSnapshot snapshot() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT id, issuer, subject, display_name, disabled_at, "
                        + "created_at, updated_at FROM principal ORDER BY issuer, subject")) {
            List<Map<String, String>> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String column : List.of("id", "issuer", "subject", "display_name", "disabled_at",
                        "created_at", "updated_at")) {
                    Object value = result.getObject(column);
                    row.put(column, value == null ? null : value.toString());
                }
                rows.add(row);
            }
            return new PrincipalSnapshot(rows);
        }
    }

    private int ownerCount(String table) throws Exception {
        if (!List.of("tenant", "project").contains(table)) throw new IllegalArgumentException("unexpected table");
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonField(String body, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(matcher.find(), "missing JSON field " + field);
        return matcher.group(1);
    }

    private static String uniqueSubject(String prefix) {
        return "r03-fix02-" + prefix + "-" + UUID.randomUUID();
    }

    private static String withoutQuery(URI uri) {
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath()).toString();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private record ClientFixture(HttpClient client, CookieManager cookies) { }

    private record Flow(HttpClient client, Scenario scenario, HttpResponse<String> callback) { }

    private record PrincipalSnapshot(List<Map<String, String>> rows) {
        boolean has(String issuer, String subject) {
            return rows.stream().anyMatch(row -> issuer.equals(row.get("issuer"))
                    && subject.equals(row.get("subject")));
        }

        boolean enabled(String issuer, String subject) {
            return rows.stream().anyMatch(row -> issuer.equals(row.get("issuer"))
                    && subject.equals(row.get("subject"))
                    && row.get("disabled_at") == null);
        }

    }

    private enum Variant { VALID, WRONG_SIGNATURE, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED, WRONG_NONCE }

    private static final class Scenario {
        private final Variant variant;
        private final String subject;
        private final AtomicInteger tokenRequests = new AtomicInteger();
        private volatile String authorizationNonce;
        private volatile boolean protocolValid;

        private Scenario(Variant variant, String subject) {
            this.variant = variant;
            this.subject = subject;
        }
    }

    private static final class CodeGrant {
        private final Scenario scenario;
        private final String redirectUri;
        private final String challenge;
        private boolean used;

        private CodeGrant(Scenario scenario, String redirectUri, String challenge) {
            this.scenario = scenario;
            this.redirectUri = redirectUri;
            this.challenge = challenge;
        }
    }

    private static final class LocalOidcProvider {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor;
        private final RSAKey trustedKey;
        private final RSAKey untrustedKey;
        private final String issuer;
        private final Deque<Scenario> pending = new ArrayDeque<>();
        private final Map<String, CodeGrant> codes = new ConcurrentHashMap<>();
        private final Map<String, Scenario> accessTokens = new ConcurrentHashMap<>();

        private LocalOidcProvider(HttpServer server, java.util.concurrent.ExecutorService executor,
                RSAKey trustedKey, RSAKey untrustedKey) {
            this.server = server;
            this.executor = executor;
            this.trustedKey = trustedKey;
            this.untrustedKey = untrustedKey;
            this.issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
        }

        static LocalOidcProvider start() {
            try {
                RSAKey trusted = new RSAKeyGenerator(2048).keyID("fix02-trusted").generate();
                RSAKey untrusted = new RSAKeyGenerator(2048).keyID("fix02-trusted").generate();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var executor = java.util.concurrent.Executors.newCachedThreadPool();
                server.setExecutor(executor);
                LocalOidcProvider provider = new LocalOidcProvider(server, executor, trusted, untrusted);
                provider.registerContexts();
                server.start();
                return provider;
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to start local OIDC test provider", ex);
            }
        }

        String issuer() { return issuer; }

        String authorizationEndpoint() { return issuer + "/protocol/auth"; }

        Scenario prepare(Variant variant, String subject) {
            Scenario scenario = new Scenario(variant, subject);
            synchronized (pending) { pending.addLast(scenario); }
            return scenario;
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void registerContexts() {
            server.createContext("/issuer/.well-known/openid-configuration", this::discovery);
            server.createContext("/issuer/protocol/auth", this::authorize);
            server.createContext("/issuer/protocol/token", this::token);
            server.createContext("/issuer/protocol/jwks", this::jwks);
            server.createContext("/issuer/protocol/userinfo", this::userinfo);
        }

        private void discovery(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "application/json", "{"
                    + "\"issuer\":\"" + issuer + "\","
                    + "\"authorization_endpoint\":\"" + authorizationEndpoint() + "\","
                    + "\"token_endpoint\":\"" + issuer + "/protocol/token\","
                    + "\"jwks_uri\":\"" + issuer + "/protocol/jwks\","
                    + "\"userinfo_endpoint\":\"" + issuer + "/protocol/userinfo\","
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"subject_types_supported\":[\"public\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                    + "\"scopes_supported\":[\"openid\",\"profile\",\"email\"]"
                    + "}");
        }

        private void authorize(HttpExchange exchange) throws IOException {
            Map<String, String> params = query(exchange.getRequestURI());
            Scenario scenario;
            synchronized (pending) { scenario = pending.pollFirst(); }
            if (scenario == null) {
                respond(exchange, 400, "text/plain", "no pending test scenario");
                return;
            }
            if (!"code".equals(params.get("response_type"))
                    || !CLIENT_ID.equals(params.get("client_id"))
                    || !"S256".equals(params.get("code_challenge_method"))
                    || isBlank(params.get("state")) || isBlank(params.get("nonce"))
                    || isBlank(params.get("redirect_uri")) || isBlank(params.get("code_challenge"))) {
                respond(exchange, 400, "text/plain", "invalid authorization request");
                return;
            }
            scenario.authorizationNonce = params.get("nonce");
            String code = "fix02-code-" + UUID.randomUUID();
            codes.put(code, new CodeGrant(scenario, params.get("redirect_uri"), params.get("code_challenge")));
            String callback = params.get("redirect_uri") + "?code=" + encode(code)
                    + "&state=" + encode(params.get("state"));
            respondRedirect(exchange, callback);
        }

        private void token(HttpExchange exchange) throws IOException {
            Map<String, String> form = form(exchange);
            CodeGrant grant = codes.get(form.get("code"));
            boolean pkce = grant != null && !grant.used
                    && Objects.equals(grant.redirectUri, form.get("redirect_uri"))
                    && Objects.equals(grant.challenge, sha256(form.get("code_verifier")));
            if (!pkce || !CLIENT_ID.equals(form.get("client_id"))
                    || !"authorization_code".equals(form.get("grant_type"))) {
                respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
                return;
            }
            grant.used = true;
            grant.scenario.tokenRequests.incrementAndGet();
            grant.scenario.protocolValid = true;
            String access = "fix02-access-" + UUID.randomUUID();
            accessTokens.put(access, grant.scenario);
            String idToken = idToken(grant.scenario);
            respond(exchange, 200, "application/json", "{\"access_token\":\"" + access
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"id_token\":\""
                    + idToken + "\"}");
        }

        private void jwks(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "application/json", "{\"keys\":["
                    + trustedKey.toPublicJWK().toJSONString() + "]}");
        }

        private void userinfo(HttpExchange exchange) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String access = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring("Bearer ".length()) : "";
            Scenario scenario = accessTokens.get(access);
            if (scenario == null) {
                respond(exchange, 401, "application/json", "{\"error\":\"invalid_token\"}");
                return;
            }
            respond(exchange, 200, "application/json", "{\"sub\":\"" + scenario.subject
                    + "\",\"name\":\"HTTP Callback Tester\",\"preferred_username\":\"r03-fix02\"}");
        }

        private String idToken(Scenario scenario) throws IOException {
            try {
                Instant now = Instant.now();
                Instant issued = now.minusSeconds(5);
                Instant expiry = now.plusSeconds(300);
                String tokenIssuer = issuer;
                String audience = CLIENT_ID;
                String nonce = scenarioNonce(scenario);
                RSAKey key = trustedKey;
                switch (scenario.variant) {
                    case WRONG_SIGNATURE -> key = untrustedKey;
                    case WRONG_ISSUER -> tokenIssuer = "https://example.invalid/wrong-issuer";
                    case WRONG_AUDIENCE -> audience = "different-client";
                    case EXPIRED -> {
                        issued = now.minusSeconds(1_200);
                        expiry = now.minusSeconds(600);
                    }
                    case WRONG_NONCE -> nonce = sha256("wrong-nonce");
                    default -> { }
                }
                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                        .issuer(tokenIssuer).subject(scenario.subject).audience(audience)
                        .issueTime(java.util.Date.from(issued)).expirationTime(java.util.Date.from(expiry))
                        .claim("nonce", nonce).claim("name", "HTTP Callback Tester")
                        .claim("preferred_username", "r03-fix02").build();
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(trustedKey.getKeyID()).build(), claims);
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (Exception ex) {
                throw new IOException("unable to create test ID token", ex);
            }
        }

        private String scenarioNonce(Scenario scenario) {
            if (scenario.authorizationNonce == null || scenario.authorizationNonce.isBlank()) {
                throw new IllegalStateException("OIDC authorization request did not contain nonce");
            }
            // The real Spring resolver sends the hashed nonce as the authorization
            // request parameter and retains the raw nonce in the session. The
            // callback validator compares the ID-token claim with that sent value.
            return scenario.authorizationNonce;
        }

        private static void respondRedirect(HttpExchange exchange, String location) throws IOException {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private static void respond(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static Map<String, String> form(HttpExchange exchange) throws IOException {
            return parse(exchange.getRequestBody().readAllBytes());
        }

        private static Map<String, String> parse(byte[] bytes) {
            String body = new String(bytes, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String part : body.split("&")) {
                String[] pair = part.split("=", 2);
                values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return values;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
