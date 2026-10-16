package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.sql.SQLException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

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

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        com.test365alm.server.asset.AssetTestInfrastructure.register(registry);
        com.test365alm.server.asset.AssetTestInfrastructure.registerScannerDatabase(registry, POSTGRES);
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

    /**
     * M09 K06/K09: the real OIDC session and restricted application pool must
     * drive the complete execution HTTP slice.  Owner SQL is limited to
     * disposable tenant membership fixtures and post-command assertions.
     */
    @Test
    void realOidcManualExecutionHttpRunsAndRejectsViewerWrites() throws Exception {
        String adminSubject = uniqueSubject("m09-http-admin");
        String memberSubject = uniqueSubject("m09-http-member");
        String viewerSubject = uniqueSubject("m09-http-viewer");
        Flow admin = runAuthorization(Variant.VALID, adminSubject);
        Flow member = runAuthorization(Variant.VALID, memberSubject);
        Flow viewer = runAuthorization(Variant.VALID, viewerSubject);
        UUID adminId = principalId(adminSubject);
        UUID memberId = principalId(memberSubject);
        UUID viewerId = principalId(viewerSubject);
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (var connection = ownerConnection()) {
            insertTenantFixture(connection, tenant, "m09-http-tenant-" + tenant, "M09 HTTP tenant");
            insertDomainFixture(connection, domain, tenant, "M09 HTTP domain");
            insertTenantMemberFixture(connection, tenant, adminId, "TENANT_ADMIN");
            insertTenantMemberFixture(connection, tenant, memberId, "MEMBER");
            insertTenantMemberFixture(connection, tenant, viewerId, "MEMBER");
        }

        String adminCsrfBody = get(admin.client, "/api/v1/csrf").body();
        String adminCsrfHeader = jsonField(adminCsrfBody, "headerName");
        String adminCsrf = jsonField(adminCsrfBody, "token");
        HttpResponse<String> projectResponse = postJson(admin.client, "/api/v1/projects",
                adminCsrfHeader, adminCsrf, "{\"tenantId\":\"" + tenant + "\",\"domainId\":\""
                        + domain + "\",\"code\":\"m09-http-project-" + tenant
                        + "\",\"name\":\"M09 HTTP project\"}");
        assertEquals(201, projectResponse.statusCode(), projectResponse.body());
        UUID project = UUID.fromString(jsonField(projectResponse.body(), "id"));
        try (var connection = ownerConnection()) {
            insertProjectMemberFixture(connection, tenant, project, memberId, "PROJECT_MEMBER");
            insertProjectMemberFixture(connection, tenant, project, viewerId, "PROJECT_VIEWER");
        }

        String memberCsrfBody = get(member.client, "/api/v1/csrf").body();
        String memberCsrfHeader = jsonField(memberCsrfBody, "headerName");
        String memberCsrf = jsonField(memberCsrfBody, "token");
        String testPath = "/api/v1/projects/" + project + "/tests";
        HttpResponse<String> createdCase = postExecution(member.client, testPath, memberCsrfHeader, memberCsrf,
                "m09-http-case-" + UUID.randomUUID(), "{\"testType\":\"MANUAL\",\"title\":\"M09 HTTP case\","
                        + "\"description\":\"HTTP execution\",\"preconditions\":\"ready\","
                        + "\"steps\":[{\"ordinal\":1,\"action\":\"Open\",\"expected\":\"Visible\"}]}" );
        assertEquals(201, createdCase.statusCode(), createdCase.body());
        UUID testCase = UUID.fromString(jsonField(createdCase.body(), "id"));
        HttpResponse<String> revisions = get(member.client, testPath + "/" + testCase + "/revisions");
        assertEquals(200, revisions.statusCode(), revisions.body());
        UUID revision = UUID.fromString(jsonText(revisions.body(), "0", "id"));

        String setPath = "/api/v1/projects/" + project + "/test-sets";
        HttpResponse<String> createdSet = postExecution(member.client, setPath, memberCsrfHeader, memberCsrf,
                "m09-http-set-" + UUID.randomUUID(), "{\"name\":\"M09 HTTP set\",\"description\":\"run\"}");
        assertEquals(201, createdSet.statusCode(), createdSet.body());
        UUID set = UUID.fromString(jsonField(createdSet.body(), "id"));
        HttpResponse<String> added = postExecution(member.client, setPath + "/" + set + "/instances",
                memberCsrfHeader, memberCsrf, "m09-http-instance-" + UUID.randomUUID(),
                "{\"testCaseId\":\"" + testCase + "\",\"testRevisionId\":\"" + revision + "\"}");
        assertEquals(201, added.statusCode(), added.body());
        UUID instance = UUID.fromString(jsonField(added.body(), "id"));

        HttpResponse<String> createdRun = postExecution(member.client, "/api/v1/projects/" + project + "/runs",
                memberCsrfHeader, memberCsrf, "m09-http-run-" + UUID.randomUUID(),
                "{\"testInstanceId\":\"" + instance + "\",\"mode\":\"MANUAL\"}");
        assertEquals(201, createdRun.statusCode(), createdRun.body());
        UUID run = UUID.fromString(jsonText(createdRun.body(), "run", "id"));
        UUID attempt = UUID.fromString(jsonText(createdRun.body(), "currentAttempt", "id"));
        long attemptVersion = jsonLong(createdRun.body(), "currentAttempt", "rowVersion");
        UUID step = UUID.fromString(jsonText(createdRun.body(), "currentAttempt", "steps", "0", "stepKey"));
        long stepVersion = jsonLong(createdRun.body(), "currentAttempt", "steps", "0", "rowVersion");
        HttpResponse<String> missingCsrf = putExecutionStep(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/steps/" + step,
                null, null, stepVersion, "m09-http-missing-csrf-" + UUID.randomUUID(),
                "{\"actualResult\":\"Visible\",\"conclusion\":\"PASS\",\"expectedVersion\":" + stepVersion + "}");
        assertEquals(403, missingCsrf.statusCode(), missingCsrf.body());
        HttpResponse<String> conflictingVersion = putExecutionStep(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/steps/" + step,
                memberCsrfHeader, memberCsrf, stepVersion, "m09-http-conflicting-version-" + UUID.randomUUID(),
                "{\"actualResult\":\"Visible\",\"conclusion\":\"PASS\",\"expectedVersion\":" + (stepVersion + 1) + "}");
        assertEquals(400, conflictingVersion.statusCode(), conflictingVersion.body());
        HttpResponse<String> saved = putExecutionStep(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/steps/" + step,
                memberCsrfHeader, memberCsrf, stepVersion, "m09-http-step-" + UUID.randomUUID(),
                "{\"actualResult\":\"Visible\",\"conclusion\":\"PASS\",\"expectedVersion\":" + stepVersion + "}");
        assertEquals(200, saved.statusCode(), saved.body());
        HttpResponse<String> finished = postExecutionVersion(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/finish",
                memberCsrfHeader, memberCsrf, attemptVersion + 1, "m09-http-finish-" + UUID.randomUUID());
        assertEquals(200, finished.statusCode(), finished.body());
        assertEquals("FINISHED", jsonText(finished.body(), "status"));
        assertEquals(404, get(member.client, "/api/v1/projects/" + project + "/runs/" + UUID.randomUUID()).statusCode());
        HttpResponse<String> unknownStep = putExecutionStep(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/steps/" + UUID.randomUUID(),
                memberCsrfHeader, memberCsrf, stepVersion, "m09-http-unknown-step-" + UUID.randomUUID(),
                "{\"actualResult\":\"unknown\",\"conclusion\":\"PASS\",\"expectedVersion\":" + stepVersion + "}");
        assertEquals(404, unknownStep.statusCode(), unknownStep.body());
        HttpResponse<String> unknownTransitionField = postExecution(member.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/pause",
                memberCsrfHeader, memberCsrf, "m09-http-unknown-field-" + UUID.randomUUID(),
                "{\"expectedVersion\":1,\"unexpected\":true}");
        assertEquals(400, unknownTransitionField.statusCode(), unknownTransitionField.body());

        String viewerCsrfBody = get(viewer.client, "/api/v1/csrf").body();
        String viewerCsrfHeader = jsonField(viewerCsrfBody, "headerName");
        String viewerCsrf = jsonField(viewerCsrfBody, "token");
        assertEquals(200, get(viewer.client, "/api/v1/projects/" + project + "/test-sets").statusCode());
        assertEquals(200, get(viewer.client, "/api/v1/projects/" + project + "/runs/" + run).statusCode());
        HttpResponse<String> denied = postExecution(viewer.client, setPath, viewerCsrfHeader, viewerCsrf,
                "m09-http-viewer-denied-" + UUID.randomUUID(), "{\"name\":\"denied\",\"description\":\"\"}");
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals("FORBIDDEN", jsonField(denied.body(), "code"));
        HttpResponse<String> deniedStep = putExecutionStep(viewer.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/steps/" + step,
                viewerCsrfHeader, viewerCsrf, stepVersion, "m09-http-viewer-step-" + UUID.randomUUID(),
                "{\"actualResult\":\"viewer\",\"conclusion\":\"PASS\",\"expectedVersion\":" + stepVersion + "}");
        assertEquals(403, deniedStep.statusCode(), deniedStep.body());
        HttpResponse<String> deniedFinish = postExecutionVersion(viewer.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts/" + attempt + "/finish",
                viewerCsrfHeader, viewerCsrf, attemptVersion, "m09-http-viewer-finish-" + UUID.randomUUID());
        assertEquals(403, deniedFinish.statusCode(), deniedFinish.body());
        HttpResponse<String> deniedRerun = postExecution(viewer.client,
                "/api/v1/projects/" + project + "/runs/" + run + "/attempts",
                viewerCsrfHeader, viewerCsrf, "m09-http-viewer-rerun-" + UUID.randomUUID(), "{}");
        assertEquals(403, deniedRerun.statusCode(), deniedRerun.body());
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

    /**
     * Exercises the project HTTP surface with the application datasource
     * running as the restricted runtime role.  The owner connection below is
     * deliberately used only to prepare and inspect disposable fixtures; all
     * reads, authorization decisions, CSRF checks and membership mutations in
     * the request path go through the real OIDC session and pooled runtime
     * datasource.
     */
    @Test
    void realOidcProjectHttpScopesTenantsAndRejectsViewerWrites() throws Exception {
        String adminSubject = uniqueSubject("scope-admin");
        String memberSubject = uniqueSubject("scope-member");
        String viewerSubject = uniqueSubject("scope-viewer");
        String otherAdminSubject = uniqueSubject("scope-other-admin");
        Flow admin = runAuthorization(Variant.VALID, adminSubject);
        Flow member = runAuthorization(Variant.VALID, memberSubject);
        Flow viewer = runAuthorization(Variant.VALID, viewerSubject);
        Flow otherAdmin = runAuthorization(Variant.VALID, otherAdminSubject);
        UUID adminId = principalId(adminSubject);
        UUID memberId = principalId(memberSubject);
        UUID viewerId = principalId(viewerSubject);
        UUID otherAdminId = principalId(otherAdminSubject);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID domainA = UUID.randomUUID();
        UUID domainB = UUID.randomUUID();
        UUID projectUnjoined = UUID.randomUUID();
        UUID projectOtherTenant = UUID.randomUUID();
        try (var connection = ownerConnection()) {
            insertTenantFixture(connection, tenantA, "scope-a-" + tenantA, "Scope Tenant A");
            insertTenantFixture(connection, tenantB, "scope-b-" + tenantB, "Scope Tenant B");
            insertDomainFixture(connection, domainA, tenantA, "Scope Domain A");
            insertDomainFixture(connection, domainB, tenantB, "Scope Domain B");
            insertTenantMemberFixture(connection, tenantA, adminId, "TENANT_ADMIN");
            insertTenantMemberFixture(connection, tenantA, memberId, "MEMBER");
            insertTenantMemberFixture(connection, tenantA, viewerId, "MEMBER");
            insertTenantMemberFixture(connection, tenantB, otherAdminId, "TENANT_ADMIN");
            insertProjectFixture(connection, projectUnjoined, tenantA, domainA,
                    "scope-unjoined-" + projectUnjoined, "Unjoined project", adminId);
            insertProjectFixture(connection, projectOtherTenant, tenantB, domainB,
                    "scope-other-" + projectOtherTenant, "Other tenant project", adminId);
            insertProjectMemberFixture(connection, tenantB, projectOtherTenant, otherAdminId, "PROJECT_ADMIN");
        }

        String adminCsrfHeader = jsonField(get(admin.client, "/api/v1/csrf").body(), "headerName");
        String adminCsrf = jsonField(get(admin.client, "/api/v1/csrf").body(), "token");
        HttpResponse<String> created = postJson(admin.client, "/api/v1/projects", adminCsrfHeader, adminCsrf,
                "{\"tenantId\":\"" + tenantA + "\",\"domainId\":\"" + domainA
                        + "\",\"code\":\"scope-joined-" + tenantA + "\",\"name\":\"Joined project\"}");
        assertEquals(201, created.statusCode(), created.body());
        UUID projectJoined = UUID.fromString(jsonField(created.body(), "id"));

        // Prepare the viewer membership through the owner-only fixture path,
        // then verify the application itself can read it via the runtime pool.
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) "
                                + "VALUES (?, ?, ?, ARRAY['PROJECT_VIEWER']::text[])")) {
            statement.setObject(1, tenantA);
            statement.setObject(2, projectJoined);
            statement.setObject(3, viewerId);
            statement.executeUpdate();
        }
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) "
                                + "VALUES (?, ?, ?, ARRAY['PROJECT_MEMBER']::text[])")) {
            statement.setObject(1, tenantA);
            statement.setObject(2, projectJoined);
            statement.setObject(3, memberId);
            statement.executeUpdate();
        }

        HttpResponse<String> viewerProjects = get(viewer.client, "/api/v1/projects?tenantId=" + tenantA);
        assertEquals(200, viewerProjects.statusCode(), viewerProjects.body());
        assertTrue(viewerProjects.body().contains(projectJoined.toString()));
        assertFalse(viewerProjects.body().contains(projectUnjoined.toString()));
        assertFalse(viewerProjects.body().contains(projectOtherTenant.toString()));
        HttpResponse<String> memberProjects = get(member.client, "/api/v1/projects?tenantId=" + tenantA);
        assertEquals(200, memberProjects.statusCode(), memberProjects.body());
        assertTrue(memberProjects.body().contains(projectJoined.toString()));
        assertFalse(memberProjects.body().contains(projectUnjoined.toString()));
        HttpResponse<String> otherTenantProjects = get(otherAdmin.client, "/api/v1/projects?tenantId=" + tenantB);
        assertEquals(200, otherTenantProjects.statusCode(), otherTenantProjects.body());
        assertTrue(otherTenantProjects.body().contains(projectOtherTenant.toString()));
        assertFalse(otherTenantProjects.body().contains(projectJoined.toString()));
        assertEquals(404, get(viewer.client, "/api/v1/projects/" + projectUnjoined).statusCode());
        assertEquals(404, get(viewer.client, "/api/v1/projects/" + projectOtherTenant).statusCode());

        String viewerCsrfJson = get(viewer.client, "/api/v1/csrf").body();
        String viewerCsrfHeader = jsonField(viewerCsrfJson, "headerName");
        String viewerCsrf = jsonField(viewerCsrfJson, "token");
        HttpResponse<String> viewerPatch = patchJson(viewer.client, "/api/v1/projects/" + projectJoined,
                viewerCsrfHeader, viewerCsrf, "{\"name\":\"viewer must not write\",\"rowVersion\":0}");
        assertEquals(403, viewerPatch.statusCode(), viewerPatch.body());
        HttpResponse<String> viewerPut = putJson(viewer.client,
                "/api/v1/projects/" + projectJoined + "/members/" + viewerId,
                viewerCsrfHeader, viewerCsrf, "{\"roles\":[\"PROJECT_ADMIN\"],\"authorizationVersion\":1}");
        assertEquals(403, viewerPut.statusCode(), viewerPut.body());
        HttpResponse<String> viewerDelete = deleteJson(viewer.client,
                "/api/v1/projects/" + projectJoined + "/members/" + viewerId,
                viewerCsrfHeader, viewerCsrf, "{\"authorizationVersion\":1}");
        assertEquals(403, viewerDelete.statusCode(), viewerDelete.body());

        HttpResponse<String> members = get(admin.client, "/api/v1/projects/" + projectJoined + "/members");
        assertEquals(200, members.statusCode(), members.body());
        assertTrue(members.body().contains(viewerId.toString()));
        long version = jsonNumber(members.body(), "authorizationVersion");
        int auditBeforeIdempotent = ownerAuditCount(projectJoined);
        HttpResponse<String> idempotent = putJson(admin.client,
                "/api/v1/projects/" + projectJoined + "/members/" + viewerId,
                adminCsrfHeader, adminCsrf,
                "{\"roles\":[\"PROJECT_VIEWER\"],\"authorizationVersion\":" + version + "}");
        assertEquals(200, idempotent.statusCode(), idempotent.body());
        assertEquals(auditBeforeIdempotent, ownerAuditCount(projectJoined),
                "an idempotent grant must not append an audit event");
        HttpResponse<String> stale = putJson(admin.client,
                "/api/v1/projects/" + projectJoined + "/members/" + viewerId,
                adminCsrfHeader, adminCsrf,
                "{\"roles\":[\"PROJECT_MEMBER\"],\"authorizationVersion\":0}");
        assertEquals(409, stale.statusCode(), stale.body());

        HttpResponse<String> revoked = deleteJson(admin.client,
                "/api/v1/projects/" + projectJoined + "/members/" + viewerId,
                adminCsrfHeader, adminCsrf, "{\"authorizationVersion\":" + version + "}");
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(404, get(viewer.client, "/api/v1/projects/" + projectJoined).statusCode(),
                "an existing OIDC session loses access on its next request");

        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "UPDATE tenant_member SET revoked_at = CURRENT_TIMESTAMP, valid_until = CURRENT_TIMESTAMP, "
                                + "authorization_version = authorization_version + 1 WHERE tenant_id = ? AND principal_id = ?")) {
            statement.setObject(1, tenantA);
            statement.setObject(2, viewerId);
            statement.executeUpdate();
        }
        assertEquals(403, get(viewer.client, "/api/v1/domains?tenantId=" + tenantA).statusCode(),
                "tenant revocation invalidates an old session without requiring /me first");
    }

    /**
     * Requirement HTTP security matrix.  This deliberately stays on the
     * real OIDC/random-port/runtime-datasource path above instead of mocking
     * the controller or authorization service.  The owner connection is only
     * used to prepare disposable fixtures and inspect side effects.
     */
    @Test
    void realOidcRequirementHttpEnforcesStrongEtagsCsrfAndMembershipScope() throws Exception {
        String adminSubject = uniqueSubject("requirement-http-admin");
        String memberSubject = uniqueSubject("requirement-http-member");
        String viewerSubject = uniqueSubject("requirement-http-viewer");
        String outsiderSubject = uniqueSubject("requirement-http-outsider");
        Flow admin = runAuthorization(Variant.VALID, adminSubject);
        Flow member = runAuthorization(Variant.VALID, memberSubject);
        Flow viewer = runAuthorization(Variant.VALID, viewerSubject);
        Flow outsider = runAuthorization(Variant.VALID, outsiderSubject);
        UUID adminId = principalId(adminSubject);
        UUID memberId = principalId(memberSubject);
        UUID viewerId = principalId(viewerSubject);
        UUID outsiderId = principalId(outsiderSubject);
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        try (var connection = ownerConnection()) {
            insertTenantFixture(connection, tenant, "requirement-http-tenant-" + tenant, "Requirement HTTP tenant");
            insertDomainFixture(connection, domain, tenant, "Requirement HTTP domain");
            insertTenantMemberFixture(connection, tenant, adminId, "TENANT_ADMIN");
            insertTenantMemberFixture(connection, tenant, memberId, "MEMBER");
            insertTenantMemberFixture(connection, tenant, viewerId, "MEMBER");
            insertTenantMemberFixture(connection, tenant, outsiderId, "MEMBER");
            insertProjectFixture(connection, project, tenant, domain, "requirement-http-" + project,
                    "Requirement HTTP project", adminId);
            insertProjectMemberFixture(connection, tenant, project, adminId, "PROJECT_ADMIN");
            insertProjectMemberFixture(connection, tenant, project, memberId, "PROJECT_MEMBER");
            insertProjectMemberFixture(connection, tenant, project, viewerId, "PROJECT_VIEWER");
        }

        HttpResponse<String> memberIdentity = get(member.client, "/api/v1/me");
        assertEquals(200, memberIdentity.statusCode(), memberIdentity.body());
        assertEquals(memberId.toString(), jsonField(memberIdentity.body(), "id"));
        HttpResponse<String> memberProject = get(member.client, "/api/v1/projects/" + project);
        assertEquals(200, memberProject.statusCode(), memberProject.body());

        String memberCsrfJson = get(member.client, "/api/v1/csrf").body();
        String memberCsrfHeader = jsonField(memberCsrfJson, "headerName");
        String memberCsrf = jsonField(memberCsrfJson, "token");
        String requirementPath = "/api/v1/projects/" + project + "/requirements";
        HttpResponse<String> created = postRequirement(member.client, requirementPath, memberCsrfHeader,
                memberCsrf, "requirement-create-" + UUID.randomUUID(),
                "{\"title\":\"HTTP requirement\",\"body\":\"body\",\"priority\":\"HIGH\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("\"1\"", created.headers().firstValue("etag").orElseThrow());
        UUID requirementId = UUID.fromString(jsonField(created.body(), "id"));
        RequirementCounts afterCreate = requirementCounts(project);

        HttpResponse<String> updated = patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "\"1\"", "requirement-update-" + UUID.randomUUID(),
                "{\"title\":\"Edited over HTTP\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("\"2\"", updated.headers().firstValue("etag").orElseThrow());
        assertTrue(updated.body().contains("Edited over HTTP"));
        RequirementCounts afterUpdate = requirementCounts(project);
        assertEquals(afterCreate.requirements() + 0, afterUpdate.requirements());
        assertEquals(afterCreate.revisions() + 1, afterUpdate.revisions());
        assertEquals(afterCreate.auditEvents() + 1, afterUpdate.auditEvents());
        assertEquals(afterCreate.outboxEvents() + 1, afterUpdate.outboxEvents());

        assertEquals(401, get(newClientFixture().client(), requirementPath).statusCode());

        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, null, "requirement-empty-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 428, "PRECONDITION_REQUIRED");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "", "requirement-blank-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 428, "PRECONDITION_REQUIRED");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "1", "requirement-bare-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 400, "INVALID_REQUEST");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "W/\"2\"", "requirement-weak-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 400, "INVALID_REQUEST");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "*", "requirement-wildcard-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 428, "PRECONDITION_REQUIRED");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "\"2", "requirement-malformed-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 400, "INVALID_REQUEST");
        assertRequirementError(patchRequirement(member.client, requirementPath + "/" + requirementId,
                memberCsrfHeader, memberCsrf, "\"1\"", "requirement-stale-etag-" + UUID.randomUUID(),
                "{\"title\":\"rejected\"}"), 412, "STALE_VERSION");
        assertEquals(afterUpdate, requirementCounts(project),
                "rejected If-Match variants must not append requirement side effects");

        HttpResponse<String> unknownFields = postRequirement(member.client, requirementPath, memberCsrfHeader,
                memberCsrf, "requirement-unknown-fields-" + UUID.randomUUID(),
                "{\"title\":\"unknown fields\",\"parentId\":\"" + UUID.randomUUID()
                        + "\",\"authorId\":\"" + memberId + "\",\"permissions\":[\"ADMIN\"]}");
        assertEquals(400, unknownFields.statusCode(), unknownFields.body());
        HttpResponse<String> wrongJsonType = postRequirement(member.client, requirementPath, memberCsrfHeader,
                memberCsrf, "requirement-wrong-type-" + UUID.randomUUID(),
                "{\"title\":123,\"body\":\"body\"}");
        assertEquals(400, wrongJsonType.statusCode(), wrongJsonType.body());
        assertRequirementError(postRequirement(member.client, requirementPath, memberCsrfHeader, memberCsrf,
                "requirement-empty-title-" + UUID.randomUUID(), "{\"title\":\"\",\"body\":\"body\"}"),
                400, "INVALID_REQUEST");
        assertRequirementError(postRequirement(member.client, requirementPath, memberCsrfHeader, memberCsrf,
                "requirement-long-title-" + UUID.randomUUID(),
                "{\"title\":\"" + "x".repeat(501) + "\",\"body\":\"body\"}"),
                400, "INVALID_REQUEST");
        assertRequirementError(postRequirement(member.client, requirementPath, memberCsrfHeader, memberCsrf,
                "requirement-long-body-" + UUID.randomUUID(),
                "{\"title\":\"valid\",\"body\":\"" + "x".repeat(100_001) + "\"}"),
                400, "INVALID_REQUEST");
        assertEquals(afterUpdate, requirementCounts(project),
                "invalid JSON and validation requests must not append business rows");

        HttpResponse<String> missingCsrf = postRequirement(member.client, requirementPath, null, null,
                "requirement-missing-csrf-" + UUID.randomUUID(),
                "{\"title\":\"csrf rejected\"}");
        assertRequirementError(missingCsrf, 403, "CSRF_REJECTED");
        HttpResponse<String> wrongCsrf = postRequirement(member.client, requirementPath, memberCsrfHeader,
                "wrong-csrf-token", "requirement-wrong-csrf-" + UUID.randomUUID(),
                "{\"title\":\"csrf rejected\"}");
        assertRequirementError(wrongCsrf, 403, "CSRF_REJECTED");
        assertEquals(afterUpdate, requirementCounts(project),
                "CSRF rejection must not claim idempotency or append business rows");

        String viewerCsrfJson = get(viewer.client, "/api/v1/csrf").body();
        String viewerCsrfHeader = jsonField(viewerCsrfJson, "headerName");
        String viewerCsrf = jsonField(viewerCsrfJson, "token");
        HttpResponse<String> viewerWrite = postRequirement(viewer.client, requirementPath, viewerCsrfHeader,
                viewerCsrf, "requirement-viewer-write-" + UUID.randomUUID(),
                "{\"title\":\"viewer must not write\"}");
        assertRequirementError(viewerWrite, 403, "FORBIDDEN");
        assertEquals(afterUpdate, requirementCounts(project),
                "viewer write rejection must not claim idempotency or append business rows");

        assertEquals(404, get(outsider.client, requirementPath).statusCode(),
                "a tenant member without project membership cannot discover requirements");
        assertEquals(afterUpdate, requirementCounts(project));

        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "UPDATE project_member SET revoked_at = CURRENT_TIMESTAMP, valid_until = CURRENT_TIMESTAMP, "
                                + "authorization_version = authorization_version + 1 "
                                + "WHERE tenant_id = ? AND project_id = ? AND principal_id = ?")) {
            statement.setObject(1, tenant);
            statement.setObject(2, project);
            statement.setObject(3, memberId);
            statement.executeUpdate();
        }
        assertEquals(404, get(member.client, requirementPath).statusCode(),
                "project revocation invalidates an existing OIDC session without /me");
        assertEquals(afterUpdate, requirementCounts(project));

        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "UPDATE principal SET disabled_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setObject(1, memberId);
            statement.executeUpdate();
        }
        assertRequirementError(get(member.client, requirementPath), 403, "FORBIDDEN");
        assertEquals(afterUpdate, requirementCounts(project));
    }

    @Test
    void realOidcTestCaseHttpEnforcesStrongEtagsCsrfAndInputBoundary() throws Exception {
        TestCaseHttpFixture fixture = testCaseHttpFixture();
        HttpActor member = fixture.member();
        String collection = testCasePath(fixture.project());
        String createBody = testCaseBody("HTTP member case", null, true);
        HttpResponse<String> created = testCasePost(member, collection, null, createBody);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("\"1\"", created.headers().firstValue("etag").orElseThrow());
        String id = jsonField(created.body(), "id");
        String item = collection + "/" + id;
        String stepKey = firstStepKey(created.body());
        HttpResponse<String> reread = get(member.flow().client(), item);
        assertEquals(200, reread.statusCode(), reread.body());
        assertEquals(jsonField(created.body(), "id"), jsonField(reread.body(), "id"));
        assertEquals(jsonNumber(created.body(), "displayNumber"), jsonNumber(reread.body(), "displayNumber"));
        assertEquals(firstStepKey(created.body()), firstStepKey(reread.body()),
                "fresh HTTP read preserves the server-generated initial step key");
        assertEquals("\"1\"", reread.headers().firstValue("etag").orElseThrow());
        assertEquals(member.principalId().toString(), jsonField(created.body(), "createdBy"));
        assertTrue(get(member.flow().client(), collection).body().contains(id));

        String revisionPath = item + "/revisions";
        String revisionBody = testCaseBody("Edited through HTTP", stepKey, false);
        HttpResponse<String> edited = testCasePost(member, revisionPath, "\"1\"", revisionBody);
        assertEquals(200, edited.statusCode(), edited.body());
        assertEquals("\"2\"", edited.headers().firstValue("etag").orElseThrow());
        assertEquals(stepKey, firstStepKey(edited.body()));
        assertEquals(2, jsonNumber(edited.body(), "revisionNo"));
        HttpResponse<String> editedRead = get(member.flow().client(), item);
        assertEquals(200, editedRead.statusCode(), editedRead.body());
        assertEquals("Edited through HTTP", jsonField(editedRead.body(), "title"));
        assertEquals(stepKey, firstStepKey(editedRead.body()));
        TestCaseCounts baseline = testCaseCounts(fixture.project());

        for (String absent : new String[] { null, "" }) {
            assertTestCaseRejected(() -> testCasePost(member, revisionPath, absent, revisionBody),
                    428, "PRECONDITION_REQUIRED");
        }
        for (String invalid : List.of("2", "W/\"2\"", "*", "\"2", "\"2\",\"3\"", "\"0\"")) {
            assertTestCaseRejected(() -> testCasePost(member, revisionPath, invalid, revisionBody),
                    400, "INVALID_REQUEST");
        }
        assertTestCaseRejected(() -> testCasePost(member, revisionPath, "\"1\"", revisionBody),
                412, "STALE_VERSION");
        assertTestCaseRejected(() -> testCasePost(member, collection, null, createBody, null, null),
                403, "CSRF_REJECTED");
        assertTestCaseRejected(() -> testCasePost(member, revisionPath, "\"2\"", revisionBody,
                member.csrfHeader(), "synthetic-invalid-csrf"), 403, "CSRF_REJECTED");

        List<String> invalidCreates = List.of(
                createBody.replace("\"MANUAL\"", "\"AUTOMATED\""),
                createBody.replace("\"title\":\"HTTP member case\"", "\"title\":123"),
                createBody.replace("\"description\":\"plain text\"", "\"description\":{}"),
                createBody.replace("\"title\":\"HTTP member case\"", "\"title\":\"\""),
                createBody.replace("HTTP member case", "x".repeat(501)),
                createBody.replace("plain text", "x".repeat(100_001)),
                createBody.replace("\"ordinal\":1", "\"ordinal\":0"),
                createBody.replace("\"ordinal\":1", "\"ordinal\":2"),
                createBody.replace("\"ordinal\":1", "\"ordinal\":\"1\""),
                createBody.replace("\"ordinal\":1", "\"ordinal\":1.5"),
                createBody.replace("\"ordinal\":1", "\"ordinal\":4294967297"),
                createBody.replace("\"action\":\"perform action\"", "\"action\":false"),
                createBody.replace("perform action", "x".repeat(10_001)),
                createBody.replace("expected result", "x".repeat(10_001)),
                "{\"testType\":\"MANUAL\",\"title\":\"bad steps\",\"steps\":{}}",
                "{\"testType\":\"MANUAL\",\"title\":\"bad steps\",\"steps\":[null]}",
                "{\"testType\":\"MANUAL\",\"title\":\"bad steps\",\"steps\":[{\"action\":\"\",\"expected\":\"x\"}]}",
                "{\"testType\":\"MANUAL\",\"title\":\"too many\",\"steps\":["
                        + String.join(",", java.util.Collections.nCopies(201, "{\"action\":\"a\",\"expected\":\"e\"}")) + "]}");
        for (String invalid : invalidCreates) {
            assertTestCaseRejected(() -> testCasePost(member, collection, null, invalid), 400, "INVALID_REQUEST");
        }
        for (String controlledField : List.of("authorId", "tenantId", "permissions", "currentRevisionId", "requirementId")) {
            String invalid = createBody.substring(0, createBody.length() - 1) + ",\"" + controlledField + "\":\"forged\"}";
            assertTestCaseRejected(() -> testCasePost(member, collection, null, invalid), 400, "INVALID_REQUEST");
        }
        String duplicateSteps = "{\"title\":\"duplicate\",\"description\":\"\",\"preconditions\":\"\",\"steps\":["
                + "{\"stepKey\":\"" + stepKey + "\",\"ordinal\":1,\"action\":\"a\",\"expected\":\"e\"},"
                + "{\"stepKey\":\"" + stepKey + "\",\"ordinal\":2,\"action\":\"b\",\"expected\":\"f\"}]}";
        assertTestCaseRejected(() -> testCasePost(member, revisionPath, "\"2\"", duplicateSteps), 400, "INVALID_REQUEST");
        assertTestCaseRejected(() -> testCasePost(member, revisionPath, "\"2\"", createBody), 400, "INVALID_REQUEST");
        assertTestCaseRejected(() -> testCasePost(member, revisionPath, "\"2\"",
                revisionBody.replace("\"stepKey\":\"" + stepKey + "\"", "\"stepKey\":\"not-a-uuid\"")),
                400, "INVALID_REQUEST");

        assertEquals(200, get(fixture.viewer().flow().client(), item).statusCode());
        assertEquals(200, get(fixture.viewer().flow().client(), revisionPath).statusCode());
        assertTestCaseRejected(() -> testCasePost(fixture.viewer(), collection, null, createBody), 403, "FORBIDDEN");
        assertTestCaseRejected(() -> testCasePost(fixture.viewer(), revisionPath, "\"2\"", revisionBody), 403, "FORBIDDEN");
        HttpClient anonymous = newClientFixture().client();
        assertTestCaseRejected(() -> get(anonymous, collection), 401, "UNAUTHENTICATED");
        assertTestCaseRejected(() -> get(anonymous, item), 401, "UNAUTHENTICATED");
        assertTestCaseRejected(() -> get(anonymous, revisionPath), 401, "UNAUTHENTICATED");
        assertEquals(baseline, testCaseCounts(fixture.project()),
                "HTTP rejected requests must not create case, revision, step, audit, outbox or idempotency rows");
    }

    @Test
    void realOidcTestCaseHttpScopesReferencesAndRefreshesOldSessionPermissions() throws Exception {
        TestCaseHttpFixture fixture = testCaseHttpFixture();
        HttpActor member = fixture.member();
        String collection = testCasePath(fixture.project());
        HttpResponse<String> created = testCasePost(member, collection, null, testCaseBody("sensitive case A", null, true));
        assertEquals(201, created.statusCode(), created.body());
        String item = collection + "/" + jsonField(created.body(), "id");
        String ownStep = firstStepKey(created.body());
        HttpResponse<String> sibling = testCasePost(member, collection, null, testCaseBody("sensitive sibling", null, true));
        assertEquals(201, sibling.statusCode(), sibling.body());
        HttpResponse<String> foreignProject = testCasePost(fixture.admin(), testCasePath(fixture.otherProject()),
                null, testCaseBody("foreign project secret", null, true));
        HttpResponse<String> foreignTenant = testCasePost(fixture.admin(), testCasePath(fixture.otherTenantProject()),
                null, testCaseBody("foreign tenant secret", null, true));
        assertEquals(201, foreignProject.statusCode(), foreignProject.body());
        assertEquals(201, foreignTenant.statusCode(), foreignTenant.body());
        TestCaseCounts baseline = testCaseCounts(fixture.project());

        for (HttpResponse<String> foreign : List.of(sibling, foreignProject, foreignTenant)) {
            String foreignRevision = new tools.jackson.databind.json.JsonMapper().readTree(foreign.body())
                    .path("currentRevision").path("id").asText();
            assertTestCaseRejected(() -> get(member.flow().client(), item + "/revisions/" + foreignRevision),
                    404, "NOT_FOUND");
            String badStepBody = testCaseBody("must not accept a foreign step", firstStepKey(foreign.body()), false);
            assertTestCaseRejected(() -> testCasePost(member, item + "/revisions", "\"1\"", badStepBody),
                    400, "INVALID_REQUEST");
        }
        for (HttpResponse<String> foreign : List.of(foreignProject, foreignTenant)) {
            assertTestCaseRejected(() -> get(member.flow().client(), collection + "/" + jsonField(foreign.body(), "id")),
                    404, "NOT_FOUND");
        }
        for (UUID foreignProjectId : List.of(fixture.otherProject(), fixture.otherTenantProject())) {
            assertTestCaseRejected(() -> get(member.flow().client(), testCasePath(foreignProjectId)), 404, "NOT_FOUND");
        }
        String revisionBody = testCaseBody("attempted write", ownStep, false);
        // This outsider is a TENANT_ADMIN, but has no project membership. A malformed ETag must not reveal a version.
        for (String path : List.of(collection, item, item + "/revisions")) {
            assertTestCaseRejected(() -> get(fixture.outsider().flow().client(), path), 404, "NOT_FOUND");
        }
        assertTestCaseRejected(() -> testCasePost(fixture.outsider(), item + "/revisions", "wrong", revisionBody),
                404, "NOT_FOUND");

        try (var connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE project_member SET roles = ARRAY['PROJECT_VIEWER']::text[], authorization_version = authorization_version + 1 "
                        + "WHERE tenant_id = ? AND project_id = ? AND principal_id = ?")) {
            statement.setObject(1, fixture.tenant()); statement.setObject(2, fixture.project());
            statement.setObject(3, member.principalId()); assertEquals(1, statement.executeUpdate());
        }
        assertEquals(200, get(member.flow().client(), item).statusCode(), "same authenticated client remains readable after demotion without /me");
        assertTestCaseRejected(() -> testCasePost(member, collection, null, testCaseBody("demoted", null, true)), 403, "FORBIDDEN");
        assertTestCaseRejected(() -> testCasePost(member, item + "/revisions", "\"1\"", revisionBody), 403, "FORBIDDEN");
        try (var connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE project_member SET revoked_at = CURRENT_TIMESTAMP, authorization_version = authorization_version + 1 "
                        + "WHERE tenant_id = ? AND project_id = ? AND principal_id = ?")) {
            statement.setObject(1, fixture.tenant()); statement.setObject(2, fixture.project());
            statement.setObject(3, member.principalId()); assertEquals(1, statement.executeUpdate());
        }
        for (String path : List.of(collection, item, item + "/revisions")) {
            assertTestCaseRejected(() -> get(member.flow().client(), path), 404, "NOT_FOUND");
        }
        assertTestCaseRejected(() -> testCasePost(member, item + "/revisions", "\"1\"", revisionBody), 404, "NOT_FOUND");
        // A distinct, still-joined viewer proves tenant invalidation and principal disabling independently of project revocation.
        try (var connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE tenant_member SET valid_until = CURRENT_TIMESTAMP WHERE tenant_id = ? AND principal_id = ?")) {
            statement.setObject(1, fixture.tenant()); statement.setObject(2, fixture.viewer().principalId());
            assertEquals(1, statement.executeUpdate());
        }
        assertTestCaseRejected(() -> get(fixture.viewer().flow().client(), item), 404, "NOT_FOUND");
        try (var connection = ownerConnection(); var statement = connection.prepareStatement(
                "UPDATE principal SET disabled_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setObject(1, fixture.admin().principalId()); assertEquals(1, statement.executeUpdate());
        }
        assertTestCaseRejected(() -> get(fixture.admin().flow().client(), item), 403, "FORBIDDEN");
        assertTestCaseRejected(() -> testCasePost(fixture.admin(), item + "/revisions", "\"1\"", revisionBody),
                403, "FORBIDDEN");
        assertEquals(baseline, testCaseCounts(fixture.project()),
                "cross-scope and revoked-session calls must not write case, revision, step, audit, outbox or idempotency rows");
    }

    @Test
    void realOidcFieldMetadataHttpAdminViewerCsrfTypesVersionsAndScope() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project()+"/field-definitions";
        String d="{\"key\":\"estimate\",\"type\":\"NUMBER\",\"required\":true,\"precision\":5,\"scale\":2,\"maximum\":\"100.00\"}";
        var created=testCasePost(f.admin(),root,null,d);assertEquals(201,created.statusCode(),created.body());
        String item=root+"/"+jsonField(created.body(),"id");
        assertEquals("\"1\"",created.headers().firstValue("ETag").orElseThrow());
        assertEquals(200,get(f.viewer().flow().client(),item).statusCode());
        assertEquals(200,testCasePost(f.member(),item+"/validate",null,"{\"revision\":1,\"value\":\"12.34\"}").statusCode());
        var before=coreRows(f.project());
        assertTestCaseRejected(()->testCasePost(f.viewer(),root,null,d),403,"FORBIDDEN");
        assertTestCaseRejected(()->testCasePost(f.member(),root,null,d),403,"FORBIDDEN");
        assertTestCaseRejected(()->testCasePost(f.admin(),root,null,d,null,null),403,"CSRF_REJECTED");
        for(String bad:List.of(d.replace("estimate","title"),d.replace("NUMBER","SCRIPT"),d.replace("true","\"true\"")))assertTestCaseRejected(()->testCasePost(f.admin(),root,null,bad),400,"INVALID_REQUEST");
        assertTestCaseRejected(()->testCasePost(f.viewer(),item+"/validate",null,"{\"revision\":1,\"value\":\"100.01\"}"),422,"VALIDATION_FAILED");
        assertTestCaseRejected(()->get(f.outsider().flow().client(),item),404,"NOT_FOUND");
        assertTestCaseRejected(()->get(f.admin().flow().client(),"/api/v1/projects/"+f.otherTenantProject()+"/field-definitions/"+jsonField(created.body(),"id")),404,"NOT_FOUND");
        assertEquals(before,coreRows(f.project()));
        var revised=corePatch(f.admin(),item,"\"1\"",d);assertEquals(200,revised.statusCode(),revised.body());
        before=coreRows(f.project());assertTestCaseRejected(()->corePatch(f.admin(),item,"\"1\"",d),412,"STALE_VERSION");assertEquals(before,coreRows(f.project()));
        assertEquals(200,get(f.viewer().flow().client(),item+"/revisions/1").statusCode());
        before=coreRows(f.project());
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?")){s.setObject(1,f.admin().principalId());assertEquals(1,s.executeUpdate());}
        assertTestCaseRejected(()->get(f.admin().flow().client(),item),403,"FORBIDDEN");
        assertTestCaseRejected(()->corePatch(f.admin(),item,"\"2\"",d),403,"FORBIDDEN");
        assertEquals(before,coreRows(f.project()));
    }

    @Test
    void realOidcTraceHttpTypedEndpointsViewerCsrfRevokeAndNoIllegalEffects() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project();
        var req=testCasePost(f.member(),root+"/requirements",null,"{\"title\":\"R08 HTTP requirement\",\"body\":\"text\"}");assertEquals(201,req.statusCode(),req.body());
        var tc=testCasePost(f.member(),root+"/tests",null,testCaseBody("R08 HTTP test",null,true));assertEquals(201,tc.statusCode(),tc.body());
        String rid=jsonField(req.body(),"id"),tid=jsonField(tc.body(),"id");String d="{\"requirementId\":\""+rid+"\",\"testCaseId\":\""+tid+"\"}";
        var link=testCasePost(f.member(),root+"/trace-links",null,d);assertEquals(201,link.statusCode(),link.body());
        String item=root+"/trace-links/"+jsonField(link.body(),"id")+"/invalidate";
        assertEquals(200,get(f.viewer().flow().client(),root+"/trace-links?requirementId="+rid).statusCode());
        var stats=get(f.member().flow().client(),root+"/coverage?requirementId="+rid);assertEquals(200,stats.statusCode());assertEquals(0,jsonNode(stats.body(),"passedTests").intValue());
        var before=coreRows(f.project());
        assertTestCaseRejected(()->testCasePost(f.viewer(),root+"/trace-links",null,d),403,"FORBIDDEN");
        assertTestCaseRejected(()->testCasePost(f.member(),root+"/trace-links",null,d,null,null),403,"CSRF_REJECTED");
        assertTestCaseRejected(()->testCasePost(f.member(),root+"/trace-links",null,d),409,"DUPLICATE_LINK");
        assertTestCaseRejected(()->testCasePost(f.member(),root+"/trace-links",null,d.replace(tid,UUID.randomUUID().toString())),404,"NOT_FOUND");
        assertTestCaseRejected(()->testCasePost(f.viewer(),item,"\"1\"","{}"),403,"FORBIDDEN");
        assertEquals(before,coreRows(f.project()));
        var inactive=testCasePost(f.member(),item,"\"1\"","{}");assertEquals(200,inactive.statusCode(),inactive.body());
        before=coreRows(f.project());
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP,authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertTestCaseRejected(()->get(f.member().flow().client(),root+"/trace-links?requirementId="+rid),404,"NOT_FOUND");
        assertTestCaseRejected(()->testCasePost(f.member(),root+"/trace-links",null,d),404,"NOT_FOUND");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?")){s.setObject(1,f.viewer().principalId());assertEquals(1,s.executeUpdate());}
        assertTestCaseRejected(()->get(f.viewer().flow().client(),root+"/trace-links?requirementId="+rid),403,"FORBIDDEN");
        assertTestCaseRejected(()->testCasePost(f.viewer(),root+"/trace-links",null,d),403,"FORBIDDEN");
        assertEquals(before,coreRows(f.project()));
    }
    @Test
    void realOidcDefectHttpMemberEtagsCsrfStrictInputAndViewerBoundary() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project()+"/defects",body=defectBody("HTTP Bug"),key=UUID.randomUUID().toString();
        var created=defectHttp(f.member(),root,"POST",null,body,key,f.member().csrfToken());assertEquals(201,created.statusCode(),created.body());
        assertEquals(created.body(),defectHttp(f.member(),root,"POST",null,body,key,f.member().csrfToken()).body());
        String id=jsonField(created.body(),"id"),item=root+"/"+id;assertEquals("\"1\"",get(f.member().flow().client(),item).headers().firstValue("ETag").orElseThrow());
        assertEquals(f.member().principalId().toString(),JSON.readTree(created.body()).get("currentRevision").get("createdBy").textValue());
        assertEquals(200,get(f.viewer().flow().client(),item+"/revisions").statusCode());
        for(String missing:new String[]{null,"","*"})assertDefectRejected(f.project(),()->defectHttp(f.member(),item,"PATCH",missing,body,UUID.randomUUID().toString(),f.member().csrfToken()),428,"PRECONDITION_REQUIRED");
        for(String bad:List.of("1","W/\"1\"","\"0\"","\"1\",\"2\"","\"999999999999999999999999999999\""))assertDefectRejected(f.project(),()->defectHttp(f.member(),item,"PATCH",bad,body,UUID.randomUUID().toString(),f.member().csrfToken()),400,"INVALID_REQUEST");
        for(String csrf:new String[]{null,"wrong"})assertDefectRejected(f.project(),()->defectHttp(f.member(),root,"POST",null,body,UUID.randomUUID().toString(),csrf),403,"CSRF_REJECTED");
        for(String bad:List.of(body.replace("\"HTTP Bug\"","9"),body.replace("\"HIGH\"","\"CUSTOM\""),body.substring(0,body.length()-1)+",\"author\":\"forged\"}"))assertDefectRejected(f.project(),()->testCasePost(f.member(),root,null,bad),400,"INVALID_REQUEST");
        for(String bad:List.of(body.replace("HTTP Bug",""),body.replace("HTTP Bug","x".repeat(501)),body.replace("reproduce","x".repeat(100001))))assertDefectRejected(f.project(),()->testCasePost(f.member(),root,null,bad),422,"VALIDATION_FAILED");
        assertDefectRejected(f.project(),()->defectHttp(f.member(),root,"POST",null,defectBody("Different"),key,f.member().csrfToken()),409,"IDEMPOTENCY_KEY_REUSED");
        assertDefectRejected(f.project(),()->testCasePost(f.viewer(),root,null,body),403,"FORBIDDEN");
        assertDefectRejected(f.project(),()->defectHttp(f.viewer(),item,"PATCH","\"1\"",body,UUID.randomUUID().toString(),f.viewer().csrfToken()),403,"FORBIDDEN");
        assertDefectRejected(f.project(),()->testCasePost(f.viewer(),item+"/transitions","\"1\"","{\"status\":\"RESOLVED\",\"reason\":\"fixed\"}"),403,"FORBIDDEN");
        assertDefectRejected(f.project(),()->get(f.outsider().flow().client(),item),404,"NOT_FOUND");
        assertDefectRejected(f.project(),()->defectHttp(f.outsider(),item,"PATCH","\"999\"",body,UUID.randomUUID().toString(),f.outsider().csrfToken()),404,"NOT_FOUND");
        assertDefectRejected(f.project(),()->get(newClientFixture().client(),item),401,"UNAUTHENTICATED");
        var edited=defectHttp(f.member(),item,"PATCH","\"1\"",defectBody("Edited"),UUID.randomUUID().toString(),f.member().csrfToken());assertEquals(200,edited.statusCode(),edited.body());
        assertDefectRejected(f.project(),()->defectHttp(f.member(),item,"PATCH","\"1\"",body,UUID.randomUUID().toString(),f.member().csrfToken()),412,"STALE_VERSION");
        assertDefectRejected(f.project(),()->testCasePost(f.member(),item+"/transitions","\"2\"","{\"status\":\"CLOSED\",\"reason\":\"skip\"}"),409,"INVALID_TRANSITION");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET roles='{PROJECT_VIEWER}',authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertEquals(200,get(f.member().flow().client(),item).statusCode());assertDefectRejected(f.project(),()->defectHttp(f.member(),item,"PATCH","\"2\"",body,UUID.randomUUID().toString(),f.member().csrfToken()),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertDefectRejected(f.project(),()->get(f.member().flow().client(),item),404,"NOT_FOUND");
        assertDefectRejected(f.project(),()->defectHttp(f.member(),root,"POST",null,body,key,f.member().csrfToken()),404,"NOT_FOUND");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?")){s.setObject(1,f.admin().principalId());assertEquals(1,s.executeUpdate());}
        assertDefectRejected(f.project(),()->get(f.admin().flow().client(),item),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?")){s.setObject(1,f.tenant());s.setObject(2,f.viewer().principalId());assertEquals(1,s.executeUpdate());}
        assertDefectRejected(f.project(),()->get(f.viewer().flow().client(),item),404,"NOT_FOUND");
    }
    @Test
    void realOidcDefectHttpFailedSourceRetestAndClosePreserveOriginalEvidence() throws Exception {
        var f=testCaseHttpFixture();String project="/api/v1/projects/"+f.project(),root=project+"/defects";
        var tc=testCasePost(f.member(),project+"/tests",null,testCaseBody("Defect source",null,true));assertEquals(201,tc.statusCode(),tc.body());
        String test=jsonField(tc.body(),"id"),revision=JSON.readTree(tc.body()).get("currentRevision").get("id").textValue();
        var set=testCasePost(f.member(),project+"/test-sets",null,"{\"name\":\"Defect set\",\"description\":\"\"}");assertEquals(201,set.statusCode(),set.body());
        var instance=testCasePost(f.member(),project+"/test-sets/"+jsonField(set.body(),"id")+"/instances",null,"{\"testCaseId\":\""+test+"\",\"testRevisionId\":\""+revision+"\"}");assertEquals(201,instance.statusCode(),instance.body());
        var run=testCasePost(f.member(),project+"/runs",null,"{\"instanceId\":\""+jsonField(instance.body(),"id")+"\"}");assertEquals(201,run.statusCode(),run.body());
        var node=JSON.readTree(run.body());String runId=node.get("run").get("id").textValue(),attempt=node.get("currentAttempt").get("id").textValue(),step=node.get("currentAttempt").get("steps").get(0).get("stepKey").textValue();
        completeDefectHttpAttempt(f.member(),project,runId,attempt,step,"FAIL");
        String source=defectBody("From failure");source=source.substring(0,source.length()-1)+",\"runId\":\""+runId+"\",\"attemptId\":\""+attempt+"\",\"stepKey\":\""+step+"\"}";final String fromBody=source;
        var created=testCasePost(f.member(),root+"/from-step",null,fromBody);assertEquals(201,created.statusCode(),created.body());String item=root+"/"+jsonField(created.body(),"id");
        var link=JSON.readTree(created.body()).get("links").get(0);assertEquals(test,link.get("testCaseId").textValue());assertEquals(revision,link.get("testRevisionId").textValue());assertEquals("observed FAIL",link.get("actualResult").textValue());
        assertDefectRejected(f.project(),()->testCasePost(f.member(),root+"/from-step",null,fromBody.replace(step,UUID.randomUUID().toString())),404,"NOT_FOUND");
        for(UUID foreign:List.of(f.otherProject(),f.otherTenantProject()))assertDefectRejected(f.project(),()->testCasePost(f.admin(),"/api/v1/projects/"+foreign+"/defects/from-step",null,fromBody),404,"NOT_FOUND");
        var resolved=testCasePost(f.member(),item+"/transitions","\"1\"","{\"status\":\"RESOLVED\",\"reason\":\"repair\"}");assertEquals(200,resolved.statusCode(),resolved.body());
        assertDefectRejected(f.project(),()->testCasePost(f.member(),item+"/transitions","\"2\"","{\"status\":\"CLOSED\",\"reason\":\"fake\"}"),409,"RETEST_PASS_REQUIRED");
        assertDefectRejected(f.project(),()->testCasePost(f.viewer(),item+"/retests","\"2\"","{}"),403,"FORBIDDEN");
        var retry=testCasePost(f.member(),item+"/retests","\"2\"","{}");assertEquals(200,retry.statusCode(),retry.body());String retryId=jsonField(retry.body(),"attemptId");
        completeDefectHttpAttempt(f.member(),project,runId,retryId,step,"PASS");
        var verification=get(f.member().flow().client(),item+"/verification");assertEquals(200,verification.statusCode());assertTrue(JSON.readTree(verification.body()).get("eligible").booleanValue());
        var closed=testCasePost(f.member(),item+"/transitions","\"2\"","{\"status\":\"CLOSED\",\"reason\":\"verified\"}");assertEquals(200,closed.statusCode(),closed.body());
        assertEquals("CLOSED",JSON.readTree(closed.body()).get("currentRevision").get("status").textValue());
        var original=get(f.viewer().flow().client(),project+"/runs/"+runId+"/attempts/"+attempt);assertEquals(200,original.statusCode());assertEquals("FAIL",JSON.readTree(original.body()).get("conclusion").textValue());
        assertEquals(200,get(f.viewer().flow().client(),item+"/retests").statusCode());assertEquals(3,JSON.readTree(get(f.viewer().flow().client(),item+"/revisions").body()).get("items").size());
    }
    @Test
    void realOidcReleaseHttpAdminDatesEtagsCsrfAndRoleBoundary() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project()+"/releases";
        String body=releaseBody("HTTP release"),key=UUID.randomUUID().toString();
        var created=defectHttp(f.admin(),root,"POST",null,body,key,f.admin().csrfToken());
        assertEquals(201,created.statusCode(),created.body());
        String item=root+"/"+jsonField(created.body(),"id");
        assertEquals(created.body(),defectHttp(f.admin(),root,"POST",null,body,key,f.admin().csrfToken()).body());
        assertEquals("\"1\"",get(f.admin().flow().client(),item).headers().firstValue("ETag").orElseThrow());
        assertEquals(f.admin().principalId().toString(),jsonField(created.body(),"createdBy"));
        assertEquals(200,get(f.member().flow().client(),item).statusCode());
        assertEquals(200,get(f.viewer().flow().client(),root).statusCode());
        for(var actor:List.of(f.member(),f.viewer())) {
            assertReleaseRejected(f.project(),()->testCasePost(actor,root,null,body),403,"FORBIDDEN");
            assertReleaseRejected(f.project(),()->defectHttp(actor,item,"PATCH","\"999\"","{\"name\":\"denied\"}",UUID.randomUUID().toString(),actor.csrfToken()),403,"FORBIDDEN");
            assertReleaseRejected(f.project(),()->testCasePost(actor,item+"/milestones/evaluate",null,"{}"),403,"FORBIDDEN");
        }
        assertReleaseRejected(f.project(),()->get(f.outsider().flow().client(),item),404,"NOT_FOUND");
        assertReleaseRejected(f.project(),()->get(newClientFixture().client(),item),401,"UNAUTHENTICATED");
        for(String csrf:new String[]{null,"wrong"})
            assertReleaseRejected(f.project(),()->defectHttp(f.admin(),root,"POST",null,body,UUID.randomUUID().toString(),csrf),403,"CSRF_REJECTED");
        for(String tag:new String[]{null,"","*"})
            assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH",tag,"{\"name\":\"edited\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),428,"PRECONDITION_REQUIRED");
        for(String tag:List.of("1","W/\"1\"","\"0\"","\"1\",\"2\"","\"999999999999999999999999999\""))
            assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH",tag,"{\"name\":\"edited\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),400,"INVALID_REQUEST");
        for(String bad:List.of(body.replace("\"HTTP release\"","7"),body.replace("\"2026-01-01\"","20260101"),body.substring(0,body.length()-1)+",\"createdBy\":\"forged\"}","null","[]"))
            assertReleaseRejected(f.project(),()->testCasePost(f.admin(),root,null,bad),400,"INVALID_REQUEST");
        for(String bad:List.of(body.replace("HTTP release",""),body.replace("HTTP release","x".repeat(201)),body.replace("2026-01-01","2026-02-30"),body.replace("2026-12-31","2025-12-31")))
            assertReleaseRejected(f.project(),()->testCasePost(f.admin(),root,null,bad),422,"VALIDATION_FAILED");
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),root,"POST",null,releaseBody("other"),key,f.admin().csrfToken()),409,"IDEMPOTENCY_KEY_REUSED");
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH","\"1\"","{\"status\":\"UNKNOWN\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),400,"INVALID_REQUEST");
        String cycleBody="{\"name\":\"cycle\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-05-31\"}";
        var cycle=testCasePost(f.admin(),item+"/cycles",null,cycleBody);assertEquals(201,cycle.statusCode(),cycle.body());
        String cycleItem=item+"/cycles/"+jsonField(cycle.body(),"id");
        assertEquals("\"1\"",get(f.viewer().flow().client(),cycleItem).headers().firstValue("ETag").orElseThrow());
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/cycles",null,cycleBody.replace("2026-05-31","2027-05-31")),422,"CYCLE_OUTSIDE_RELEASE");
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH","\"1\"","{\"endDate\":\"2026-04-01\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),422,"CYCLE_OUTSIDE_RELEASE");
        var updated=defectHttp(f.admin(),item,"PATCH","\"1\"","{\"name\":\"edited\",\"status\":\"ACTIVE\"}",UUID.randomUUID().toString(),f.admin().csrfToken());assertEquals(200,updated.statusCode(),updated.body());
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH","\"1\"","{\"name\":\"stale\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),412,"STALE_VERSION");
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),item,"PATCH","\"2\"","{\"status\":\"PLANNED\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),409,"INVALID_TRANSITION");
        var cycleUpdated=defectHttp(f.admin(),cycleItem,"PATCH","\"1\"","{\"name\":\"cycle revised\"}",UUID.randomUUID().toString(),f.admin().csrfToken());assertEquals(200,cycleUpdated.statusCode(),cycleUpdated.body());
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),cycleItem,"PATCH","\"1\"","{\"name\":\"old\"}",UUID.randomUUID().toString(),f.admin().csrfToken()),412,"STALE_VERSION");
        var archived=defectHttp(f.admin(),item,"PATCH","\"2\"","{\"status\":\"ARCHIVED\"}",UUID.randomUUID().toString(),f.admin().csrfToken());assertEquals(200,archived.statusCode(),archived.body());
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/cycles",null,cycleBody),409,"RELEASE_READ_ONLY");
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/milestones/evaluate",null,"{}"),409,"RELEASE_READ_ONLY");
        assertEquals(200,get(f.viewer().flow().client(),item).statusCode());
    }

    @Test
    void realOidcReleaseHttpTypedScopeSnapshotsAndOldSessionRevocation() throws Exception {
        var f=testCaseHttpFixture();String project="/api/v1/projects/"+f.project(),root=project+"/releases";
        String body=releaseBody("Scope release"),originalKey=UUID.randomUUID().toString();
        var release=defectHttp(f.admin(),root,"POST",null,body,originalKey,f.admin().csrfToken());assertEquals(201,release.statusCode(),release.body());
        String item=root+"/"+jsonField(release.body(),"id");
        var req=testCasePost(f.member(),project+"/requirements",null,"{\"title\":\"scoped requirement\",\"body\":\"initial\",\"priority\":\"MEDIUM\"}");assertEquals(201,req.statusCode(),req.body());
        var tc=testCasePost(f.member(),project+"/tests",null,testCaseBody("Scoped test",null,true));assertEquals(201,tc.statusCode(),tc.body());
        String reqId=jsonField(req.body(),"id"),testId=jsonField(tc.body(),"id"),revision=JSON.readTree(tc.body()).get("currentRevision").get("id").textValue();
        var trace=testCasePost(f.member(),project+"/trace-links",null,"{\"requirementId\":\""+reqId+"\",\"testCaseId\":\""+testId+"\"}");assertEquals(201,trace.statusCode(),trace.body());
        String requirementScope="{\"kind\":\"REQUIREMENT\",\"requirementId\":\""+reqId+"\"}",testScope="{\"kind\":\"TEST_CASE\",\"testCaseId\":\""+testId+"\"}";
        var rs=testCasePost(f.admin(),item+"/scope",null,requirementScope);assertEquals(201,rs.statusCode(),rs.body());
        var ts=testCasePost(f.admin(),item+"/scope",null,testScope);assertEquals(201,ts.statusCode(),ts.body());assertEquals(revision,jsonField(ts.body(),"testRevisionId"));
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,testScope),409,"DUPLICATE_SCOPE");
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,testScope.replace(testId,reqId)),404,"NOT_FOUND");
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,requirementScope.replace(reqId,testId)),404,"NOT_FOUND");
        for(String bad:List.of("{\"kind\":\"DEFECT\",\"testCaseId\":\""+testId+"\"}",testScope.substring(0,testScope.length()-1)+",\"testRevisionId\":\""+revision+"\"}","{\"kind\":\"TEST_CASE\",\"testCaseId\":123}"))
            assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,bad),400,"INVALID_REQUEST");
        for(UUID foreign:List.of(f.otherProject(),f.otherTenantProject())) {
            String foreignRoot="/api/v1/projects/"+foreign;
            var fr=testCasePost(f.admin(),foreignRoot+"/requirements",null,"{\"title\":\"foreign\",\"body\":\"private\",\"priority\":\"MEDIUM\"}");assertEquals(201,fr.statusCode(),fr.body());
            var ft=testCasePost(f.admin(),foreignRoot+"/tests",null,testCaseBody("Foreign",null,true));assertEquals(201,ft.statusCode(),ft.body());
            assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,requirementScope.replace(reqId,jsonField(fr.body(),"id"))),404,"NOT_FOUND");
            assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/scope",null,testScope.replace(testId,jsonField(ft.body(),"id"))),404,"NOT_FOUND");
        }
        var snapshot=testCasePost(f.admin(),item+"/milestones/evaluate",null,"{}");assertEquals(201,snapshot.statusCode(),snapshot.body());
        String frozen=snapshot.body(),snapshotItem=item+"/snapshots/"+jsonField(frozen,"id");
        assertEquals(1,JSON.readTree(frozen).get("requirementCount").intValue());assertEquals(1,JSON.readTree(frozen).get("coverage").get("numerator").intValue());
        assertReleaseRejected(f.project(),()->testCasePost(f.viewer(),item+"/scope",null,testScope),403,"FORBIDDEN");
        assertReleaseRejected(f.project(),()->defectHttp(f.member(),item+"/scope/"+jsonField(rs.body(),"id"),"DELETE",null,"",UUID.randomUUID().toString(),f.member().csrfToken()),403,"FORBIDDEN");
        assertReleaseRejected(f.project(),()->testCasePost(f.admin(),item+"/milestones/evaluate",null,"{\"evaluatedBy\":\"forged\"}"),400,"INVALID_REQUEST");
        var revised=testCasePost(f.member(),project+"/tests/"+testId+"/revisions","\"1\"",testCaseBody("New test revision",firstStepKey(tc.body()),false));assertEquals(200,revised.statusCode(),revised.body());
        var scopeAfter=get(f.admin().flow().client(),item+"/scope");assertEquals(200,scopeAfter.statusCode());
        assertTrue(scopeAfter.body().contains(revision),"scope retains frozen original revision");
        var removed=defectHttp(f.admin(),item+"/scope/"+jsonField(rs.body(),"id"),"DELETE",null,"",UUID.randomUUID().toString(),f.admin().csrfToken());assertEquals(200,removed.statusCode(),removed.body());
        assertEquals(JSON.readTree(frozen),JSON.readTree(get(f.viewer().flow().client(),snapshotItem).body()),"read returns frozen snapshot after source/scope change");
        var newer=testCasePost(f.admin(),item+"/milestones/evaluate",null,"{}");assertEquals(201,newer.statusCode(),newer.body());assertEquals(0,JSON.readTree(newer.body()).get("requirementCount").intValue());
        assertNotEquals(jsonField(frozen,"id"),jsonField(newer.body(),"id"));
        assertEquals(200,get(f.member().flow().client(),item+"/snapshots").statusCode());
        // Every boundary uses the original authenticated session; no intervening /me refresh.
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET roles='{PROJECT_MEMBER}',authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.admin().principalId());assertEquals(1,s.executeUpdate());}
        assertEquals(200,get(f.admin().flow().client(),snapshotItem).statusCode());
        assertReleaseRejected(f.project(),()->defectHttp(f.admin(),root,"POST",null,body,originalKey,f.admin().csrfToken()),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP,authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertReleaseRejected(f.project(),()->get(f.member().flow().client(),snapshotItem),404,"NOT_FOUND");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?")){s.setObject(1,f.viewer().principalId());assertEquals(1,s.executeUpdate());}
        assertReleaseRejected(f.project(),()->get(f.viewer().flow().client(),item),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?")){s.setObject(1,f.tenant());s.setObject(2,f.admin().principalId());assertEquals(1,s.executeUpdate());}
        assertReleaseRejected(f.project(),()->get(f.admin().flow().client(),item),404,"NOT_FOUND");
    }
    @Test
    void realOidcAssetHttpIntegrityCsrfRolesAndIdempotency() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project(),uploads=root+"/uploads";
        byte[] content="R11 harmless plain text receipt\n".getBytes(StandardCharsets.UTF_8);
        String body=assetUploadBody(content,"../../report\r\n\".txt",null),key=UUID.randomUUID().toString();
        assertAssetRejected(f.project(),()->defectHttp(f.member(),uploads,"POST",null,body,key,null),403,"CSRF_REJECTED");
        assertAssetRejected(f.project(),()->defectHttp(f.member(),uploads,"POST",null,body,key,"invalid-csrf"),403,"CSRF_REJECTED");
        assertAssetRejected(f.project(),()->testCasePost(f.viewer(),uploads,null,body),403,"FORBIDDEN");
        assertAssetRejected(f.project(),()->testCasePost(f.outsider(),uploads,null,body),404,"NOT_FOUND");
        for(String invalid:List.of("{}","null","[]",body.replace("\"size\":"+content.length,"\"size\":\""+content.length+"\""),body.replace("\"size\":"+content.length,"\"size\":26214401"),body.replace("text/plain","image/svg+xml"),body.replace("\"sha256\":\""+assetSha(content)+"\"","\"sha256\":\"wrong\""),body.substring(0,body.length()-1)+",\"clean\":true}",body.substring(0,body.length()-1)+",\"objectKey\":\"forged\"}"))
            assertAssetRejected(f.project(),()->testCasePost(f.member(),uploads,null,invalid),400,"INVALID_REQUEST");
        var created=defectHttp(f.member(),uploads,"POST",null,body,key,f.member().csrfToken());assertEquals(201,created.statusCode(),created.body());
        String item=uploads+"/"+jsonField(created.body(),"id");
        String name=jsonField(created.body(),"filename");assertFalse(name.contains("\r")||name.contains("\n")||name.contains("\"")||name.contains("/"));
        assertFalse(created.body().contains("bucket")||created.body().contains("quarantineKey")||created.body().contains("objectKey"));
        assertEquals(JSON.readTree(created.body()),JSON.readTree(defectHttp(f.member(),uploads,"POST",null,body,key,f.member().csrfToken()).body()));
        assertAssetRejected(f.project(),()->defectHttp(f.member(),uploads,"POST",null,body.replace("report","different"),key,f.member().csrfToken()),409,"IDEMPOTENCY_KEY_REUSED");
        assertAssetRejected(f.project(),()->get(f.admin().flow().client(),item),404,"NOT_FOUND");
        assertAssetRejected(f.project(),()->testCasePost(f.member(),item+"/complete",null,"{}"),409,"UPLOAD_NOT_READY");
        assertAssetRejected(f.project(),()->assetPut(f.member(),item,content,null),403,"CSRF_REJECTED");
        assertAssetRejected(f.project(),()->assetPut(f.viewer(),item,content,f.viewer().csrfToken()),403,"FORBIDDEN");
        assertAssetRejected(f.project(),()->assetPut(f.admin(),item,content,f.admin().csrfToken()),404,"NOT_FOUND");
        assertFalse(applicationContext.containsBean("multipartResolver"),"the real HTTP app must not parse multipart before its raw upload boundary");
        byte[] malformedMultipart=("--r11-owned-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.txt\"\r\n\r\nmissing closing boundary").getBytes(StandardCharsets.UTF_8);
        assertAssetRejected(f.project(),()->assetPut(f.member(),item,malformedMultipart,f.member().csrfToken(),"multipart/form-data; boundary=r11-owned-boundary"),422,"CONTENT_MISMATCH");
        var receipt=assetPut(f.member(),item,content,f.member().csrfToken());assertEquals(200,receipt.statusCode(),receipt.body());
        var parsed=JSON.readTree(receipt.body());assertEquals(content.length,parsed.get("size").longValue());assertEquals(assetSha(content),parsed.get("sha256").textValue());assertEquals("text/plain",parsed.get("mime").textValue());
        assertEquals(parsed,JSON.readTree(assetPut(f.member(),item,content,f.member().csrfToken()).body()));
        byte[] different=content.clone();different[0]='X';
        assertAssetRejected(f.project(),()->assetPut(f.member(),item,different,f.member().csrfToken()),409,"UPLOAD_CONTENT_CONFLICT");
        assertAssetRejected(f.project(),()->testCasePost(f.viewer(),item+"/complete",null,"{}"),403,"FORBIDDEN");
        assertAssetRejected(f.project(),()->testCasePost(f.member(),item+"/complete",null,"{\"clean\":true}"),400,"INVALID_REQUEST");
        String completeKey=UUID.randomUUID().toString();var complete=defectHttp(f.member(),item+"/complete","POST",null,"{}",completeKey,f.member().csrfToken());
        assertEquals(200,complete.statusCode(),complete.body());assertEquals("PUBLISHED",jsonField(complete.body(),"state"));
        assertEquals(JSON.readTree(complete.body()),JSON.readTree(defectHttp(f.member(),item+"/complete","POST",null,"{}",completeKey,f.member().csrfToken()).body()));
        assertEquals(parsed,JSON.readTree(assetPut(f.member(),item,content,f.member().csrfToken()).body()),"PUT receipt remains frozen after publication");
        String asset=root+"/assets/"+jsonField(complete.body(),"assetId"),download=asset+"/revisions/"+jsonField(complete.body(),"revisionId")+"/download";
        var downloaded=get(f.viewer().flow().client(),download);assertEquals(200,downloaded.statusCode());assertEquals(new String(content,StandardCharsets.UTF_8),downloaded.body());
        assertEquals("nosniff",downloaded.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));
        assertEquals("text/plain",downloaded.headers().firstValue("Content-Type").orElse(""));
        assertEquals(Integer.toString(content.length),downloaded.headers().firstValue("Content-Length").orElse(""));
        assertAssetRejected(f.project(),()->get(HttpClient.newHttpClient(),download),401,"UNAUTHENTICATED");
        assertAssetRejected(f.project(),()->get(f.outsider().flow().client(),download),404,"NOT_FOUND");
        // Real raw-body failures, not annotation-only validation; each leaves the isolated DB unchanged.
        for(String mismatch:List.of(assetUploadBody(content,"bad-hash.txt",null).replace(assetSha(content),"0".repeat(64)),assetUploadBody(content,"bad-size.txt",null).replace("\"size\":"+content.length,"\"size\":"+(content.length+1)),assetUploadBody(content,"bad-type.pdf",null).replace("text/plain","application/pdf"))) {
            var u=testCasePost(f.member(),uploads,null,mismatch);assertEquals(201,u.statusCode(),u.body());String target=uploads+"/"+jsonField(u.body(),"id");
            String declaredType=JSON.readTree(mismatch).get("contentType").asText();
            assertAssetRejected(f.project(),()->assetPut(f.member(),target,content,f.member().csrfToken(),declaredType),422,"CONTENT_MISMATCH");
        }
        byte[] html="<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        var badType=testCasePost(f.member(),uploads,null,assetUploadBody(html,"pretends-text.txt",null));assertEquals(201,badType.statusCode(),badType.body());
        assertAssetRejected(f.project(),()->assetPut(f.member(),uploads+"/"+jsonField(badType.body(),"id"),html,f.member().csrfToken()),415,"UNSUPPORTED_FILE_TYPE");
        var oversize=testCasePost(f.member(),uploads,null,assetUploadBody(content,"bounded.txt",null));assertEquals(201,oversize.statusCode());
        byte[] large=new byte[25*1024*1024+1];java.util.Arrays.fill(large,(byte)'a');
        assertAssetRejected(f.project(),()->assetPut(f.member(),uploads+"/"+jsonField(oversize.body(),"id"),large,f.member().csrfToken()),413,"FILE_TOO_LARGE");
        assertEquals(200,get(f.member().flow().client(),uploads+"?limit=2").statusCode());
        assertEquals(200,get(f.viewer().flow().client(),asset+"/revisions?limit=1").statusCode());
    }

    @Test
    void realOidcAssetHttpTypedLinksFrozenVersionsAndLiveDownloadRevocation() throws Exception {
        var f=testCaseHttpFixture();String root="/api/v1/projects/"+f.project();
        byte[] one="Original version one\n".getBytes(StandardCharsets.UTF_8),two="Changed version two\n".getBytes(StandardCharsets.UTF_8);
        var published=publishHttpAsset(f.member(),root,one,null);String assetId=jsonField(published,"assetId"),rev1=jsonField(published,"revisionId");
        String asset=root+"/assets/"+assetId,download=asset+"/revisions/"+rev1+"/download";
        var req=testCasePost(f.member(),root+"/requirements",null,"{\"title\":\"Attachment requirement\",\"body\":\"content\",\"priority\":\"MEDIUM\"}");assertEquals(201,req.statusCode(),req.body());
        var test=testCasePost(f.member(),root+"/tests",null,testCaseBody("Attachment test",null,true));assertEquals(201,test.statusCode(),test.body());
        var defect=testCasePost(f.member(),root+"/defects",null,defectBody("Attachment defect"));assertEquals(201,defect.statusCode(),defect.body());
        String requirementId=jsonField(req.body(),"id"),testId=jsonField(test.body(),"id"),defectId=jsonField(defect.body(),"id");
        var targets=Map.of("REQUIREMENT",Map.entry("requirementId",requirementId),"TEST_CASE",Map.entry("testCaseId",testId),"DEFECT",Map.entry("defectId",defectId));
        String originalLink=null;
        for(var target:targets.entrySet()) {
            String linkBody=assetLinkBody(target.getKey(),target.getValue().getKey(),target.getValue().getValue(),assetId,rev1),key=UUID.randomUUID().toString();
            assertAssetRejected(f.project(),()->defectHttp(f.member(),root+"/attachments","POST",null,linkBody,key,null),403,"CSRF_REJECTED");
            assertAssetRejected(f.project(),()->testCasePost(f.viewer(),root+"/attachments",null,linkBody),403,"FORBIDDEN");
            var link=defectHttp(f.member(),root+"/attachments","POST",null,linkBody,key,f.member().csrfToken());assertEquals(201,link.statusCode(),link.body());
            assertEquals(rev1,jsonField(link.body(),"assetRevisionId"));
            assertEquals(JSON.readTree(link.body()),JSON.readTree(defectHttp(f.member(),root+"/attachments","POST",null,linkBody,key,f.member().csrfToken()).body()));
            assertAssetRejected(f.project(),()->defectHttp(f.member(),root+"/attachments","POST",null,linkBody.replace(rev1,UUID.randomUUID().toString()),key,f.member().csrfToken()),409,"IDEMPOTENCY_KEY_REUSED");
            String list=root+"/attachments?kind="+target.getKey()+"&"+target.getValue().getKey()+"="+target.getValue().getValue()+"&limit=1";
            var read=get(f.viewer().flow().client(),list);assertEquals(200,read.statusCode(),read.body());assertEquals(1,JSON.readTree(read.body()).get("items").size());
            if(target.getKey().equals("REQUIREMENT"))originalLink=JSON.readTree(read.body()).get("items").get(0).toString();
            assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,linkBody.replace(target.getValue().getValue(),UUID.randomUUID().toString())),404,"NOT_FOUND");
        }
        for(UUID foreign:List.of(f.otherProject(),f.otherTenantProject())) {
            String foreignRoot="/api/v1/projects/"+foreign;
            var foreignAsset=publishHttpAsset(f.admin(),foreignRoot,one,null);
            assertNotEquals(assetId,jsonField(foreignAsset,"assetId"),"same hash never shares cross-scope identity");
            assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,assetLinkBody("REQUIREMENT","requirementId",requirementId,jsonField(foreignAsset,"assetId"),jsonField(foreignAsset,"revisionId"))),404,"NOT_FOUND");
            assertAssetRejected(f.project(),()->get(f.member().flow().client(),root+"/assets/"+jsonField(foreignAsset,"assetId")),404,"NOT_FOUND");
            assertAssetRejected(f.project(),()->get(f.admin().flow().client(),foreignRoot+"/assets/"+assetId+"/revisions/"+rev1+"/download"),404,"NOT_FOUND");
            var foreignReq=testCasePost(f.admin(),foreignRoot+"/requirements",null,"{\"title\":\"Foreign attachment target\",\"body\":\"private\",\"priority\":\"MEDIUM\"}");assertEquals(201,foreignReq.statusCode());
            assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,assetLinkBody("REQUIREMENT","requirementId",jsonField(foreignReq.body(),"id"),assetId,rev1)),404,"NOT_FOUND");
        }
        String validLink=assetLinkBody("REQUIREMENT","requirementId",requirementId,assetId,rev1);
        for(String bad:List.of(validLink.replace("REQUIREMENT","UNKNOWN"),validLink.substring(0,validLink.length()-1)+",\"target\":{}}",validLink.substring(0,validLink.length()-1)+",\"testCaseId\":\""+testId+"\"}"))
            assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,bad),400,"INVALID_REQUEST");
        assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,validLink.replace(requirementId,testId)),404,"NOT_FOUND");
        var version2=publishHttpAsset(f.member(),root,two,assetId);assertEquals(assetId,jsonField(version2,"assetId"));String rev2=jsonField(version2,"revisionId");assertNotEquals(rev1,rev2);
        var current=get(f.viewer().flow().client(),asset);assertEquals(200,current.statusCode());assertEquals(rev2,jsonField(current.body(),"currentRevisionId"));assertEquals(2,JSON.readTree(current.body()).get("currentRevision").get("revisionNumber").intValue());
        var original=get(f.viewer().flow().client(),root+"/attachments?kind=REQUIREMENT&requirementId="+requirementId);assertEquals(JSON.readTree(originalLink),JSON.readTree(original.body()).get("items").get(0));
        assertEquals(new String(one,StandardCharsets.UTF_8),get(f.viewer().flow().client(),download).body());
        assertEquals(new String(two,StandardCharsets.UTF_8),get(f.viewer().flow().client(),asset+"/revisions/"+rev2+"/download").body());
        assertAssetRejected(f.project(),()->testCasePost(f.viewer(),root+"/uploads",null,assetUploadBody(two,"next.txt",assetId)),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE requirement SET deleted_at=CURRENT_TIMESTAMP WHERE project_id=? AND id=?")){s.setObject(1,f.project());s.setObject(2,UUID.fromString(requirementId));assertEquals(1,s.executeUpdate());}
        assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/attachments",null,assetLinkBody("REQUIREMENT","requirementId",requirementId,assetId,rev2)),404,"NOT_FOUND");
        assertEquals(new String(one,StandardCharsets.UTF_8),get(f.viewer().flow().client(),download).body());
        // Same authenticated CookieManager throughout; no /me refresh before direct protected requests.
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET roles='{PROJECT_VIEWER}',authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertEquals(200,get(f.member().flow().client(),download).statusCode());
        assertAssetRejected(f.project(),()->testCasePost(f.member(),root+"/uploads",null,assetUploadBody(two,"denied.txt",assetId)),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP,authorization_version=authorization_version+1 WHERE project_id=? AND principal_id=?")){s.setObject(1,f.project());s.setObject(2,f.member().principalId());assertEquals(1,s.executeUpdate());}
        assertAssetRejected(f.project(),()->get(f.member().flow().client(),download),404,"NOT_FOUND");assertAssetRejected(f.project(),()->get(f.member().flow().client(),asset),404,"NOT_FOUND");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?")){s.setObject(1,f.viewer().principalId());assertEquals(1,s.executeUpdate());}
        assertAssetRejected(f.project(),()->get(f.viewer().flow().client(),download),403,"FORBIDDEN");assertAssetRejected(f.project(),()->get(f.viewer().flow().client(),asset),403,"FORBIDDEN");
        try(var c=ownerConnection();var s=c.prepareStatement("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?")){s.setObject(1,f.tenant());s.setObject(2,f.admin().principalId());assertEquals(1,s.executeUpdate());}
        assertAssetRejected(f.project(),()->get(f.admin().flow().client(),download),404,"NOT_FOUND");assertAssetRejected(f.project(),()->get(f.admin().flow().client(),asset),404,"NOT_FOUND");
    }
    private String assetUploadBody(byte[] content,String name,String assetId)throws Exception {
        var values=new LinkedHashMap<String,Object>();values.put("filename",name);values.put("size",content.length);values.put("sha256",assetSha(content));values.put("contentType","text/plain");if(assetId!=null)values.put("assetId",assetId);return JSON.writeValueAsString(values);
    }
    private String assetSha(byte[] content)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}
    private String assetLinkBody(String kind,String field,String target,String asset,String revision){return JSON.writeValueAsString(Map.of("kind",kind,field,target,"assetId",asset,"assetRevisionId",revision));}
    private HttpResponse<String> assetPut(HttpActor actor,String upload,byte[] content,String csrf)throws Exception {
        return assetPut(actor,upload,content,csrf,"text/plain");
    }
    private HttpResponse<String> assetPut(HttpActor actor,String upload,byte[] content,String csrf,String contentType)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+upload+"/content")).timeout(Duration.ofSeconds(30)).header("Content-Type",contentType);if(csrf!=null)b.header(actor.csrfHeader(),csrf);
        // Unknown-length streaming publisher proves that Content-Length is not the size boundary.
        return actor.flow().client().send(b.PUT(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(content))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String publishHttpAsset(HttpActor actor,String root,byte[] content,String asset)throws Exception {
        var u=testCasePost(actor,root+"/uploads",null,assetUploadBody(content,"evidence.txt",asset));assertEquals(201,u.statusCode(),u.body());String path=root+"/uploads/"+jsonField(u.body(),"id");
        var put=assetPut(actor,path,content,actor.csrfToken());assertEquals(200,put.statusCode(),put.body());var completed=testCasePost(actor,path+"/complete",null,"{}");assertEquals(200,completed.statusCode(),completed.body());assertEquals("PUBLISHED",jsonField(completed.body(),"state"));return completed.body();
    }
    private void assertAssetRejected(UUID project,HttpCall call,int status,String code)throws Exception {
        var before=assetRows(project);assertTestCaseRejected(call,status,code);assertEquals(before,assetRows(project),"HTTP refusal cannot mutate asset/session/revision/scan/link/audit/outbox/idempotency");
    }
    private Map<String,List<String>> assetRows(UUID project)throws Exception {
        var result=new java.util.TreeMap<String,List<String>>();try(var c=ownerConnection()){
            for(String table:List.of("upload_session","asset","asset_revision","scan_result","attachment_link","asset_outbox_event","audit_event","core_command_idempotency"))
                try(var s=c.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text")){s.setObject(1,project);try(var r=s.executeQuery()){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}}
        }return result;
    }
    private String releaseBody(String name) {return "{\"name\":\""+name+"\",\"description\":\"plan\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\"}";}
    private void assertReleaseRejected(UUID project,HttpCall call,int status,String code)throws Exception {
        var before=releaseRows(project);assertTestCaseRejected(call,status,code);assertEquals(before,releaseRows(project),"denial cannot mutate release/scope/snapshot/events/idempotency");
    }
    private Map<String,List<String>> releaseRows(UUID project)throws Exception {
        var result=new java.util.TreeMap<String,List<String>>();try(var c=ownerConnection()){
            for(String table:List.of("release_plan","release_cycle","release_scope","quality_snapshot","release_number_allocator","release_outbox_event","audit_event","core_command_idempotency"))
                try(var s=c.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text")){s.setObject(1,project);try(var r=s.executeQuery()){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}}
        }return result;
    }
    private void completeDefectHttpAttempt(HttpActor actor,String project,String run,String attempt,String step,String outcome)throws Exception{
        String path=project+"/runs/"+run+"/attempts/"+attempt;
        var saved=defectHttp(actor,path+"/steps/"+step,"PUT","\"1\"","{\"actualResult\":\"observed "+outcome+"\",\"conclusion\":\""+outcome+"\",\"expectedVersion\":1}",UUID.randomUUID().toString(),actor.csrfToken());assertEquals(200,saved.statusCode(),saved.body());
        long version=JSON.readTree(saved.body()).get("rowVersion").longValue();
        var finished=testCasePost(actor,path+"/finish","\""+version+"\"","{\"expectedVersion\":"+version+"}");assertEquals(200,finished.statusCode(),finished.body());
    }
    private String defectBody(String title){return "{\"title\":\""+title+"\",\"description\":\"reproduce\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}";}
    private HttpResponse<String> defectHttp(HttpActor actor,String path,String method,String tag,String body,String key,String csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+path)).timeout(REQUEST_TIMEOUT).header("Content-Type","application/json").header("Idempotency-Key",key);if(tag!=null)b.header("If-Match",tag);if(csrf!=null)b.header(actor.csrfHeader(),csrf);
        return actor.flow().client().send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertDefectRejected(UUID project,HttpCall call,int status,String code)throws Exception{var before=defectRows(project);assertTestCaseRejected(call,status,code);assertEquals(before,defectRows(project),"rejected request has no business/event/idempotency side effects");}
    private Map<String,List<String>> defectRows(UUID project)throws Exception{
        var result=new java.util.TreeMap<String,List<String>>();try(var c=ownerConnection()){
            for(String table:List.of("defect","defect_revision","defect_link","defect_retest","defect_number_allocator","defect_outbox_event","audit_event","core_command_idempotency","run_attempt","run_step","execution_event","execution_outbox_event","execution_idempotency"))try(var s=c.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text")){s.setObject(1,project);try(var r=s.executeQuery()){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}}
        }return result;
    }
    private HttpResponse<String> corePatch(HttpActor actor,String path,String tag,String body)throws Exception{
        return actor.flow().client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPort+path)).timeout(REQUEST_TIMEOUT).header("Content-Type","application/json").header(actor.csrfHeader(),actor.csrfToken()).header("If-Match",tag).header("Idempotency-Key",UUID.randomUUID().toString()).method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,List<String>> coreRows(UUID project)throws Exception{
        var result=new java.util.TreeMap<String,List<String>>();try(var c=ownerConnection()){
            for(String table:List.of("field_definition","field_definition_revision","trace_link","trace_link_revision","core_command_idempotency","core_outbox_event","audit_event"))try(var s=c.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text")){s.setObject(1,project);try(var r=s.executeQuery()){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}}
        }return result;
    }

    private TestCaseHttpFixture testCaseHttpFixture() throws Exception {
        String adminSubject = uniqueSubject("test-case-http-admin");
        String memberSubject = uniqueSubject("test-case-http-member");
        String viewerSubject = uniqueSubject("test-case-http-viewer");
        String outsiderSubject = uniqueSubject("test-case-http-outsider");
        HttpActor admin = actor(adminSubject);
        HttpActor member = actor(memberSubject);
        HttpActor viewer = actor(viewerSubject);
        HttpActor outsider = actor(outsiderSubject);
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        UUID otherDomain = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        UUID otherTenantProject = UUID.randomUUID();
        try (var connection = ownerConnection()) {
            insertTenantFixture(connection, tenant, "test-case-http-tenant-" + tenant, "Test case HTTP tenant");
            insertTenantFixture(connection, otherTenant, "test-case-http-other-tenant-" + otherTenant, "Other tenant");
            insertDomainFixture(connection, domain, tenant, "Test case HTTP domain");
            insertDomainFixture(connection, otherDomain, otherTenant, "Other tenant domain");
            insertTenantMemberFixture(connection, tenant, admin.principalId(), "TENANT_ADMIN");
            insertTenantMemberFixture(connection, tenant, member.principalId(), "MEMBER");
            insertTenantMemberFixture(connection, tenant, viewer.principalId(), "MEMBER");
            insertTenantMemberFixture(connection, tenant, outsider.principalId(), "TENANT_ADMIN");
            insertTenantMemberFixture(connection, otherTenant, admin.principalId(), "TENANT_ADMIN");
            insertProjectFixture(connection, project, tenant, domain, "test-case-http-" + project, "Test case HTTP project", admin.principalId());
            insertProjectFixture(connection, otherProject, tenant, domain, "test-case-http-other-" + otherProject, "Other project", admin.principalId());
            insertProjectFixture(connection, otherTenantProject, otherTenant, otherDomain, "test-case-http-cross-" + otherTenantProject, "Cross tenant project", admin.principalId());
            insertProjectMemberFixture(connection, tenant, project, admin.principalId(), "PROJECT_ADMIN");
            insertProjectMemberFixture(connection, tenant, project, member.principalId(), "PROJECT_MEMBER");
            insertProjectMemberFixture(connection, tenant, project, viewer.principalId(), "PROJECT_VIEWER");
            insertProjectMemberFixture(connection, tenant, otherProject, admin.principalId(), "PROJECT_ADMIN");
            insertProjectMemberFixture(connection, otherTenant, otherTenantProject, admin.principalId(), "PROJECT_ADMIN");
        }
        return new TestCaseHttpFixture(tenant, project, otherProject, otherTenantProject, admin, member, viewer, outsider);
    }

    private HttpActor actor(String subject) throws Exception {
        Flow flow = runAuthorization(Variant.VALID, subject);
        String csrf = get(flow.client(), "/api/v1/csrf").body();
        return new HttpActor(flow, principalId(subject), jsonField(csrf, "headerName"), jsonField(csrf, "token"));
    }

    private String testCasePath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/tests";
    }

    private String testCaseBody(String title, String stepKey, boolean create) {
        String step = "{\"" + (stepKey == null ? "" : "stepKey\":\"" + stepKey + "\",\"")
                + "ordinal\":1,\"action\":\"perform action\",\"expected\":\"expected result\"}";
        return "{\"" + (create ? "testType\":\"MANUAL\",\"" : "")
                + "title\":\"" + title + "\",\"description\":\"plain text\",\"preconditions\":\"ready\",\"steps\":[" + step + "]}";
    }

    private HttpResponse<String> testCasePost(HttpActor actor, String path, String ifMatch, String body) throws Exception {
        return testCasePost(actor, path, ifMatch, body, actor.csrfHeader(), actor.csrfToken());
    }

    private HttpResponse<String> testCasePost(HttpActor actor, String path, String ifMatch, String body,
            String csrfHeader, String csrfToken) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", "test-case-http-" + UUID.randomUUID());
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return actor.flow().client().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertTestCaseRejected(HttpCall action, int status, String code) {
        try {
            HttpResponse<String> response = action.send();
            assertEquals(status, response.statusCode(), response.body());
            assertEquals(code, jsonField(response.body(), "code"), response.body());
        } catch (Exception ex) {
            throw new AssertionError("HTTP test-case request failed", ex);
        }
    }

    @FunctionalInterface
    private interface HttpCall { HttpResponse<String> send() throws Exception; }

    private String firstStepKey(String body) {
        Matcher matcher = Pattern.compile("\\\"steps\\\"\\s*:\\s*\\[\\s*\\{[^}]*?\\\"stepKey\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(body);
        assertTrue(matcher.find(), "missing first step key");
        return matcher.group(1);
    }

    private TestCaseCounts testCaseCounts(UUID projectId) throws Exception {
        try (var connection = ownerConnection()) {
            return new TestCaseCounts(countRowsForProject(connection, "test_case", projectId),
                    countRowsForProject(connection, "test_revision", projectId),
                    countRowsForProject(connection, "test_step", projectId),
                    countRowsForProject(connection, "audit_event", projectId),
                    countRowsForProject(connection, "test_case_outbox_event", projectId),
                    countRowsForProject(connection, "test_case_idempotency", projectId));
        }
    }

    private static int countRowsForProject(java.sql.Connection connection, String table, UUID projectId)
            throws SQLException {
        if (!List.of("test_case", "test_revision", "test_step", "audit_event", "test_case_outbox_event",
                "test_case_idempotency").contains(table)) throw new IllegalArgumentException("unexpected testcase table");
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
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

    private java.sql.Connection ownerConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID principalId(String subject) throws Exception {
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement("SELECT id FROM principal WHERE issuer = ? AND subject = ?")) {
            statement.setString(1, IDP.issuer());
            statement.setString(2, subject);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "OIDC callback must persist principal " + subject);
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static void insertTenantFixture(java.sql.Connection connection, UUID id, String code, String name)
            throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, code);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static void insertDomainFixture(java.sql.Connection connection, UUID id, UUID tenantId, String name)
            throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static void insertTenantMemberFixture(java.sql.Connection connection, UUID tenantId, UUID principalId,
            String role) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY[?]::text[])")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, principalId);
            statement.setString(3, role);
            statement.executeUpdate();
        }
    }

    private static void insertProjectFixture(java.sql.Connection connection, UUID id, UUID tenantId, UUID domainId,
            String code, String name, UUID createdBy) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, domainId);
            statement.setString(4, code);
            statement.setString(5, name);
            statement.setObject(6, createdBy);
            statement.executeUpdate();
        }
    }

    private static void insertProjectMemberFixture(java.sql.Connection connection, UUID tenantId, UUID projectId,
            UUID principalId, String role) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ARRAY[?]::text[])")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, projectId);
            statement.setObject(3, principalId);
            statement.setString(4, role);
            statement.executeUpdate();
        }
    }

    private int ownerAuditCount(UUID projectId) throws Exception {
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_event WHERE project_id = ?")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private RequirementCounts requirementCounts(UUID projectId) throws Exception {
        try (var connection = ownerConnection()) {
            return new RequirementCounts(countRows(connection, "requirement", projectId),
                    countRows(connection, "requirement_revision", projectId),
                    countRows(connection, "audit_event", projectId), countRows(connection, "outbox_event", projectId),
                    countRows(connection, "requirement_idempotency", projectId));
        }
    }

    private static int countRows(java.sql.Connection connection, String table, UUID projectId) throws SQLException {
        if (!List.of("requirement", "requirement_revision", "audit_event", "outbox_event",
                "requirement_idempotency").contains(table)) {
            throw new IllegalArgumentException("unexpected requirement table");
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static void assertRequirementError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, jsonField(response.body(), "code"));
    }

    private HttpResponse<String> postRequirement(HttpClient client, String path, String csrfHeader, String csrfToken,
            String idempotencyKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey);
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchRequirement(HttpClient client, String path, String csrfHeader, String csrfToken,
            String ifMatch, String idempotencyKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey);
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return client.send(builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(HttpClient client, String path, String csrfHeader, String csrfToken,
            String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postExecution(HttpClient client, String path, String csrfHeader, String csrfToken,
            String idempotencyKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey);
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postExecutionVersion(HttpClient client, String path, String csrfHeader,
            String csrfToken, long version, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("If-Match", "\"" + version + "\"");
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":" + version + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putExecutionStep(HttpClient client, String path, String csrfHeader,
            String csrfToken, long version, String idempotencyKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("If-Match", "\"" + version + "\"");
        if (csrfHeader != null && csrfToken != null) builder.header(csrfHeader, csrfToken);
        return client.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(HttpClient client, String path, String csrfHeader, String csrfToken,
            String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken).PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(HttpClient client, String path, String csrfHeader, String csrfToken,
            String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken).method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteJson(HttpClient client, String path, String csrfHeader, String csrfToken,
            String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header(csrfHeader, csrfToken).method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
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

    private static String jsonText(String body, String... path) throws Exception {
        JsonNode node = jsonNode(body, path);
        assertTrue(node != null && node.isTextual(), "missing JSON text path " + String.join(".", path));
        return node.textValue();
    }

    private static long jsonLong(String body, String... path) throws Exception {
        JsonNode node = jsonNode(body, path);
        assertTrue(node != null && node.isIntegralNumber(), "missing JSON number path " + String.join(".", path));
        return node.longValue();
    }

    private static JsonNode jsonNode(String body, String... path) throws Exception {
        JsonNode node = JSON.readTree(body);
        for (String part : path) {
            if (node == null) return null;
            if (node.isArray()) {
                try {
                    node = node.get(Integer.parseInt(part));
                } catch (NumberFormatException ex) {
                    return null;
                }
            } else {
                node = node.get(part);
            }
        }
        return node;
    }

    private static long jsonNumber(String body, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)").matcher(body);
        assertTrue(matcher.find(), "missing JSON number field " + field);
        return Long.parseLong(matcher.group(1));
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

    private record HttpActor(Flow flow, UUID principalId, String csrfHeader, String csrfToken) { }

    private record TestCaseHttpFixture(UUID tenant, UUID project, UUID otherProject, UUID otherTenantProject,
            HttpActor admin, HttpActor member, HttpActor viewer, HttpActor outsider) { }

    private record TestCaseCounts(int cases, int revisions, int steps, int audit, int outbox, int idempotency) { }

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

    private record RequirementCounts(int requirements, int revisions, int auditEvents, int outboxEvents,
            int idempotency) { }

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
