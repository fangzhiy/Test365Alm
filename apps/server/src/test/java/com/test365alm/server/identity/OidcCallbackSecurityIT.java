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
