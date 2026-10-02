package com.test365alm.server.requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL proof for requirement revisions, CAS and idempotency. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class RequirementDatabaseIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("requirement_it")
            .withUsername("requirement_owner")
            .withPassword("requirement_owner_password")
            .withInitScript("r03-test-role.sql");

    @Autowired
    private RequirementService requirements;
    @Autowired
    private ProjectService projects;
    @Autowired
    private JdbcTemplate runtimeJdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Test
    void memberCanCreateEditAndReadImmutableHistory() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);

        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("First title", "first body", "HIGH"), "create-key-" + UUID.randomUUID());
        assertEquals(1, created.displayNumber());
        assertEquals(1, created.revisionNumber());
        assertEquals("HIGH", created.priority());

        RequirementService.RequirementView updated = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Second title", null, null),
                etag(created), "update-key-" + UUID.randomUUID());
        assertEquals(2, updated.revisionNumber());
        assertEquals("first body", updated.body());
        assertEquals("Second title", updated.title());
        assertEquals(2, requirements.revisions(fixture.member(), fixture.project(), created.id()).size());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
    }

    @Test
    void viewerReadsButCannotWriteAndStaleVersionDoesNotCreateRevision() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        projects.putMember(fixture.admin(), fixture.project(), fixture.viewer(), List.of("PROJECT_VIEWER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("A title", "A body", null), "create-key-" + UUID.randomUUID());

        assertEquals(1, requirements.list(fixture.viewer(), fixture.project(), null, null).items().size());
        assertThrows(ProjectAccessException.class, () -> requirements.create(fixture.viewer(), fixture.project(),
                new RequirementService.CreateCommand("Denied", "", null), "create-key-" + UUID.randomUUID()));
        RequirementService.RequirementView updated = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("B title", null, null), etag(created),
                "update-key-" + UUID.randomUUID());
        assertEquals(2, updated.revisionNumber());
        int revisions = ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id());
        assertThrows(ProjectAccessException.class, () -> requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Lost title", null, null), etag(created),
                "update-key-" + UUID.randomUUID()));
        assertEquals(revisions, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
    }

    @Test
    void sameCreateKeyIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String key = "create-key-" + UUID.randomUUID();
        RequirementService.CreateCommand command = new RequirementService.CreateCommand("Same", "Body", "MEDIUM");
        RequirementService.RequirementView first = requirements.create(fixture.member(), fixture.project(), command, key);
        RequirementService.RequirementView replay = requirements.create(fixture.member(), fixture.project(), command, key);
        assertEquals(first.id(), replay.id());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
        ProjectAccessException conflict = assertThrows(ProjectAccessException.class, () -> requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Different", "Body", "MEDIUM"), key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
    }

    @Test
    void concurrentCreatesWithSameKeyProduceOneFrozenResult() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String key = "concurrent-create-" + UUID.randomUUID();
        RequirementService.CreateCommand command = new RequirementService.CreateCommand("Concurrent create", "body", "HIGH");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<RequirementService.RequirementView> first = pool.submit(() -> {
                start.await();
                return requirements.create(fixture.member(), fixture.project(), command, key);
            });
            Future<RequirementService.RequirementView> second = pool.submit(() -> {
                start.await();
                return requirements.create(fixture.member(), fixture.project(), command, key);
            });
            RequirementService.RequirementView firstResult = first.get();
            RequirementService.RequirementView secondResult = second.get();
            assertEquals(firstResult.id(), secondResult.id());
            assertEquals(firstResult.currentRevisionId(), secondResult.currentRevisionId());
            assertEquals(firstResult.displayNumber(), secondResult.displayNumber());
            assertEquals(firstResult.rowVersion(), secondResult.rowVersion());
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()),
                    "project=" + fixture.project());
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE project_id = ?", fixture.project()));
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ? AND action LIKE 'requirement.%'",
                    fixture.project()));
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
            assertEquals(2, ownerCount("SELECT next_number FROM requirement_number_allocator WHERE project_id = ?", fixture.project()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreatesWithDifferentKeysAllocateUniqueNumbers() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<RequirementService.RequirementView> first = pool.submit(() -> {
                start.await();
                return requirements.create(fixture.member(), fixture.project(),
                        new RequirementService.CreateCommand("Number A", "body", "HIGH"),
                        "number-a-" + UUID.randomUUID());
            });
            Future<RequirementService.RequirementView> second = pool.submit(() -> {
                start.await();
                return requirements.create(fixture.member(), fixture.project(),
                        new RequirementService.CreateCommand("Number B", "body", "HIGH"),
                        "number-b-" + UUID.randomUUID());
            });
            Set<Long> displayNumbers = Set.of(first.get().displayNumber(), second.get().displayNumber());
            assertEquals(Set.of(1L, 2L), displayNumbers);
            assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()),
                    "project=" + fixture.project());
            assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
            assertEquals(2, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE project_id = ?", fixture.project()));
            assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ? AND action LIKE 'requirement.%'",
                    fixture.project()));
            assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
            assertEquals(3, ownerCount("SELECT next_number FROM requirement_number_allocator WHERE project_id = ?", fixture.project()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotencyReplayIsFrozenAndExpiryStartsANewIntent() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String createKey = "frozen-key-" + UUID.randomUUID();
        RequirementService.CreateCommand command = new RequirementService.CreateCommand("Frozen", "body", "MEDIUM");
        RequirementService.RequirementView first = requirements.create(fixture.member(), fixture.project(), command, createKey);
        RequirementService.RequirementView firstUpdate = requirements.update(fixture.member(), fixture.project(), first.id(),
                new RequirementService.UpdateCommand("first update", null, null), etag(first), "update-key-" + UUID.randomUUID());
        requirements.update(fixture.member(), fixture.project(), first.id(),
                new RequirementService.UpdateCommand("later update", null, null), etag(firstUpdate), "update-key-" + UUID.randomUUID());
        RequirementService.RequirementView replay = requirements.create(fixture.member(), fixture.project(), command, createKey);
        assertEquals(first.id(), replay.id());
        assertEquals(first.revisionNumber(), replay.revisionNumber());
        assertEquals(first.title(), replay.title());
        assertEquals("later update", requirements.getAuthorized(fixture.member(), fixture.project(), first.id()).title());

        ownerUpdate("UPDATE requirement_idempotency SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE project_id = ? AND idempotency_key = ?", fixture.project(), createKey);
        RequirementService.RequirementView afterExpiry = requirements.create(fixture.member(), fixture.project(), command, createKey);
        assertNotEquals(first.id(), afterExpiry.id(), "an expired key must not replay the prior response");

        RequirementService.RequirementView noOp = requirements.update(fixture.member(), fixture.project(), first.id(),
                new RequirementService.UpdateCommand("later update", null, null),
                etag(requirements.getAuthorized(fixture.member(), fixture.project(), first.id())),
                "noop-key-" + UUID.randomUUID());
        assertEquals(3, noOp.revisionNumber(), "a no-op patch must retain the current revision");
        assertEquals(3, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", first.id()));
        assertEquals(3, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", first.id()));

        String revokedKey = "revoke-key-" + UUID.randomUUID();
        requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Revoked", "body", "MEDIUM"), revokedKey);
        projects.revokeMember(fixture.admin(), fixture.project(), fixture.member());
        assertThrows(ProjectAccessException.class, () -> requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Revoked", "body", "MEDIUM"), revokedKey));
    }

    @Test
    void migratedV7ReplayIsRejectedWithoutLeakingHistoryAndRejectionSurvivesRevocation() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.CreateCommand original = new RequirementService.CreateCommand(
                "Legacy title", "Legacy body", "HIGH");
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(), original,
                "legacy-create-" + UUID.randomUUID());
        requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Current title", "Current body", "CRITICAL"),
                etag(created), "legacy-update-" + UUID.randomUUID());
        String key = "legacy-replay-" + UUID.randomUUID();
        ownerUpdate("INSERT INTO requirement_idempotency"
                + " (tenant_id, project_id, principal_id, route, idempotency_key, request_hash, requirement_id, revision_id)"
                + " VALUES (?, ?, ?, 'requirements:create', ?, ?, ?, ?)", fixture.tenant(), fixture.project(), fixture.member(),
                key, legacyCreateHash(original.title(), original.body(), original.priority()), created.id(),
                created.currentRevisionId());
        int requirementsBefore = ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project());
        int revisionsBefore = ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project());
        int auditBefore = ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", fixture.project());
        int outboxBefore = ownerCount("SELECT COUNT(*) FROM outbox_event WHERE project_id = ?", fixture.project());
        int idempotencyBefore = ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project());

        ProjectAccessException legacy = assertThrows(ProjectAccessException.class,
                () -> requirements.create(fixture.member(), fixture.project(), original, key));
        assertEquals("IDEMPOTENCY_LEGACY_UNSUPPORTED", legacy.code());
        assertEquals(HttpStatus.CONFLICT, legacy.status());
        assertEquals(requirementsBefore, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
        assertEquals(revisionsBefore, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
        assertEquals(auditBefore, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ?", fixture.project()));
        assertEquals(outboxBefore, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE project_id = ?", fixture.project()));
        assertEquals(idempotencyBefore, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));

        projects.revokeMember(fixture.admin(), fixture.project(), fixture.member());
        ProjectAccessException revoked = assertThrows(ProjectAccessException.class,
                () -> requirements.create(fixture.member(), fixture.project(), original, key));
        assertEquals(HttpStatus.FORBIDDEN, revoked.status());
        assertEquals(requirementsBefore, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
        assertEquals(revisionsBefore, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
        assertEquals(idempotencyBefore, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
    }

    @Test
    void runtimeViewerCannotWriteRequirementSliceAndRevisionIsImmutable() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        projects.putMember(fixture.admin(), fixture.project(), fixture.viewer(), List.of("PROJECT_VIEWER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Protected", "body", "MEDIUM"), "create-key-" + UUID.randomUUID());
        assertRuntimeViewerDenied(fixture, created);
    }

    @Test
    void compositeScopeForeignKeysRejectCrossRequirementReferencesWithSqlState() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView first = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("A", "body", "MEDIUM"), "create-key-" + UUID.randomUUID());
        RequirementService.RequirementView second = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("B", "body", "MEDIUM"), "create-key-" + UUID.randomUUID());
        assertSqlState("23503", () -> ownerUpdate("UPDATE requirement SET current_revision_id = ? WHERE id = ?",
                second.currentRevisionId(), first.id()));
        assertSqlState("23503", () -> ownerUpdate("INSERT INTO outbox_event"
                + " (tenant_id, project_id, aggregate_id, requirement_id, revision_id, event_type)"
                + " SELECT tenant_id, project_id, ?, ?, ?, 'requirement.updated' FROM requirement WHERE id = ?",
                first.id(), first.id(), second.currentRevisionId(), first.id()));

        ProjectService.ProjectView otherProject = projects.createProject(fixture.admin(), fixture.tenant(), fixture.domain(),
                "REQ-" + UUID.randomUUID(), "Second Requirement Project");
        projects.putMember(fixture.admin(), otherProject.id(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView otherProjectRequirement = requirements.create(fixture.member(), otherProject.id(),
                new RequirementService.CreateCommand("Other project", "body", "MEDIUM"), "create-key-" + UUID.randomUUID());
        assertSqlState("23503", () -> ownerUpdate("UPDATE requirement SET current_revision_id = ? WHERE id = ?",
                otherProjectRequirement.currentRevisionId(), first.id()));

        UUID otherTenant = UUID.randomUUID();
        UUID otherDomain = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", otherTenant,
                    "req-cross-tenant-" + otherTenant, "Cross Tenant", fixture.admin());
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", otherDomain, otherTenant,
                    "Cross Tenant Domain");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    otherTenant, fixture.admin(), "{TENANT_ADMIN}");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    otherTenant, fixture.member(), "{MEMBER}");
        }
        ProjectService.ProjectView otherTenantProject = projects.createProject(fixture.admin(), otherTenant, otherDomain,
                "REQ-" + UUID.randomUUID(), "Cross Tenant Project");
        projects.putMember(fixture.admin(), otherTenantProject.id(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView otherTenantRequirement = requirements.create(fixture.member(), otherTenantProject.id(),
                new RequirementService.CreateCommand("Other tenant", "body", "MEDIUM"), "create-key-" + UUID.randomUUID());
        assertSqlState("23503", () -> ownerUpdate("UPDATE requirement SET current_revision_id = ? WHERE id = ?",
                otherTenantRequirement.currentRevisionId(), first.id()));
    }

    @Test
    void patchHashDistinguishesOmittedFieldFromLiteralNullText() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Hash", "old", "MEDIUM"), "create-key-" + UUID.randomUUID());
        String key = "patch-hash-" + UUID.randomUUID();
        RequirementService.UpdateCommand omitted = new RequirementService.UpdateCommand(null, "new body", null);
        String originalEtag = etag(created);
        RequirementService.RequirementView first = requirements.update(fixture.member(), fixture.project(), created.id(), omitted,
                originalEtag, key);
        RequirementService.RequirementView replay = requirements.update(fixture.member(), fixture.project(), created.id(), omitted,
                originalEtag, key);
        assertEquals(first.id(), replay.id());
        assertEquals(first.rowVersion(), replay.rowVersion());
        ProjectAccessException failure = assertThrows(ProjectAccessException.class, () -> requirements.update(fixture.member(), fixture.project(),
                created.id(), new RequirementService.UpdateCommand("<null>", "new body", null), originalEtag, key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code());
        assertEquals(HttpStatus.CONFLICT, failure.status());
        RequirementService.RequirementView current = requirements.getAuthorized(fixture.member(), fixture.project(), created.id());
        assertEquals(first.id(), current.id());
        assertEquals(first.rowVersion(), current.rowVersion());
        assertEquals(first.currentRevisionId(), current.currentRevisionId());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
    }

    @Test
    void auditAndOutboxFailuresRollBackTheWholeRequirementTransaction() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);

        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(Exception.class, () -> requirements.create(fixture.member(), fixture.project(),
                    new RequirementService.CreateCommand("audit rollback", "body", "MEDIUM"),
                    "audit-failure-" + UUID.randomUUID()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_number_allocator WHERE project_id = ?", fixture.project()));
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }

        ownerUpdate("REVOKE INSERT ON outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(Exception.class, () -> requirements.create(fixture.member(), fixture.project(),
                    new RequirementService.CreateCommand("outbox rollback", "body", "MEDIUM"),
                    "outbox-failure-" + UUID.randomUUID()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ? AND action = 'requirement.created'",
                    fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM requirement_number_allocator WHERE project_id = ?", fixture.project()));
        } finally {
            ownerUpdate("GRANT INSERT ON outbox_event TO " + RUNTIME_USER);
        }
    }

    @Test
    void auditAndOutboxUpdateFailuresRollBackExistingRequirementState() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Before", "body", "MEDIUM"), "update-rollback-create-" + UUID.randomUUID());
        RequirementService.RequirementView baseline = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Baseline", null, null), etag(created),
                "update-rollback-baseline-" + UUID.randomUUID());
        int baselineRevisions = ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id());
        int baselineOutbox = ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id());
        int baselineAudit = ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id());
        int baselineIdempotency = ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project());

        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> requirements.update(fixture.member(), fixture.project(), created.id(),
                    new RequirementService.UpdateCommand("Audit denied", null, null), etag(baseline),
                    "update-rollback-audit-" + UUID.randomUUID()));
            RequirementService.RequirementView afterFailure = requirements.getAuthorized(fixture.member(), fixture.project(), created.id());
            assertEquals(baseline.rowVersion(), afterFailure.rowVersion());
            assertEquals(baseline.currentRevisionId(), afterFailure.currentRevisionId());
            assertEquals(baseline.title(), afterFailure.title());
            assertEquals(baselineRevisions, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
            assertEquals(baselineOutbox, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
            assertEquals(baselineAudit, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
            assertEquals(baselineIdempotency, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }
        RequirementService.RequirementView afterAuditRestore = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Audit restored", null, null), etag(baseline),
                "update-rollback-audit-success-" + UUID.randomUUID());
        assertEquals(3, afterAuditRestore.rowVersion());

        ownerUpdate("REVOKE INSERT ON outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> requirements.update(fixture.member(), fixture.project(), created.id(),
                    new RequirementService.UpdateCommand("Outbox denied", null, null), etag(afterAuditRestore),
                    "update-rollback-outbox-" + UUID.randomUUID()));
            RequirementService.RequirementView afterFailure = requirements.getAuthorized(fixture.member(), fixture.project(), created.id());
            assertEquals(afterAuditRestore.rowVersion(), afterFailure.rowVersion());
            assertEquals(afterAuditRestore.currentRevisionId(), afterFailure.currentRevisionId());
            assertEquals(afterAuditRestore.title(), afterFailure.title());
            assertEquals(3, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
            assertEquals(3, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
            assertEquals(3, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
        } finally {
            ownerUpdate("GRANT INSERT ON outbox_event TO " + RUNTIME_USER);
        }
        RequirementService.RequirementView afterOutboxRestore = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Outbox restored", null, null), etag(afterAuditRestore),
                "update-rollback-outbox-success-" + UUID.randomUUID());
        assertEquals(4, afterOutboxRestore.rowVersion());
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
    }

    @Test
    void concurrentUpdatesWithTheSameEtagProduceOneRevisionAndOnePreconditionFailure() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Concurrent", "body", "MEDIUM"),
                "create-key-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            List<Future<RequirementService.RequirementView>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return requirements.update(fixture.member(), fixture.project(), created.id(),
                        new RequirementService.UpdateCommand("winner A", null, null), etag(created),
                        "concurrent-a-" + UUID.randomUUID());
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return requirements.update(fixture.member(), fixture.project(), created.id(),
                        new RequirementService.UpdateCommand("winner B", null, null), etag(created),
                        "concurrent-b-" + UUID.randomUUID());
            }));
            int successes = 0;
            int failures = 0;
            RequirementService.RequirementView winner = null;
            for (Future<RequirementService.RequirementView> future : futures) {
                try {
                    winner = future.get();
                    assertEquals(2, winner.revisionNumber());
                    assertEquals(2, winner.rowVersion());
                    successes++;
                } catch (ExecutionException ex) {
                    assertTrue(ex.getCause() instanceof ProjectAccessException, ex.toString());
                    ProjectAccessException failure = (ProjectAccessException) ex.getCause();
                    assertEquals("STALE_VERSION", failure.code(), "project=" + fixture.project());
                    assertEquals(HttpStatus.PRECONDITION_FAILED, failure.status());
                    failures++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
            assertTrue(winner != null && Set.of("winner A", "winner B").contains(winner.title()));
        } finally {
            pool.shutdownNow();
        }
        RequirementService.RequirementView current = requirements.getAuthorized(fixture.member(), fixture.project(), created.id());
        assertEquals(2, current.rowVersion());
        assertTrue(Set.of("winner A", "winner B").contains(current.title()));
        assertEquals(2, requirements.revisions(fixture.member(), fixture.project(), created.id()).size());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_idempotency WHERE project_id = ?", fixture.project()));
    }

    private void assertRuntimeViewerDenied(Fixture fixture, RequirementService.RequirementView created) throws Exception {
        try (Connection connection = runtimeConnection()) {
            setContext(connection, fixture.tenant(), fixture.project(), fixture.viewer());
            assertSqlState("42501", () -> execute(connection, "INSERT INTO requirement"
                    + " (tenant_id, project_id, id, display_number, row_version, created_by) VALUES (?, ?, ?, 999999, 1, ?)",
                    fixture.tenant(), fixture.project(), UUID.randomUUID(), fixture.viewer()));
            assertRlsWriteDenied(() -> executeCount(connection, "UPDATE requirement SET current_revision_id = current_revision_id"
                    + " WHERE tenant_id = ? AND project_id = ? AND id = ?", fixture.tenant(), fixture.project(), created.id()));
            assertRlsWriteDenied(() -> executeCount(connection, "UPDATE requirement_number_allocator SET next_number = next_number + 1"
                    + " WHERE tenant_id = ? AND project_id = ?", fixture.tenant(), fixture.project()));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO requirement_revision"
                    + " (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)"
                    + " VALUES (?, ?, ?, ?, 99, 'viewer', '', 'MEDIUM', ?)", fixture.tenant(), fixture.project(), UUID.randomUUID(),
                    created.id(), fixture.viewer()));
            assertSqlState("42501", () -> execute(connection, "UPDATE requirement_revision SET title = title"
                    + " WHERE tenant_id = ? AND project_id = ? AND id = ?", fixture.tenant(), fixture.project(), created.currentRevisionId()));
            assertSqlState("42501", () -> execute(connection, "DELETE FROM requirement_revision"
                    + " WHERE tenant_id = ? AND project_id = ? AND id = ?", fixture.tenant(), fixture.project(), created.currentRevisionId()));
            assertSqlState("42501", () -> execute(connection, "TRUNCATE requirement_revision"));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO outbox_event"
                    + " (tenant_id, project_id, aggregate_id, requirement_id, revision_id, event_type)"
                    + " VALUES (?, ?, ?, ?, ?, 'requirement.updated')", fixture.tenant(), fixture.project(), created.id(),
                    created.id(), created.currentRevisionId()));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO requirement_idempotency"
                    + " (tenant_id, project_id, principal_id, route, idempotency_key, request_hash)"
                    + " VALUES (?, ?, ?, 'requirements:create', 'viewer-key-123', repeat('0', 64))",
                    fixture.tenant(), fixture.project(), fixture.viewer()));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO audit_event"
                    + " (tenant_id, project_id, actor_principal_id, action, object_type, object_id, object_revision)"
                    + " VALUES (?, ?, ?, 'requirement.created', 'requirement', ?, 1)", fixture.tenant(), fixture.project(),
                    fixture.viewer(), created.id()));
        }
    }

    private void assertSqlState(String expected, SqlOperation operation) throws Exception {
        SQLException failure = assertThrows(SQLException.class, operation::run);
        assertEquals(expected, failure.getSQLState(), failure.getMessage());
    }

    /**
     * PostgreSQL RLS filters UPDATE/DELETE target rows and legitimately returns
     * an update count of zero, while INSERT and privilege failures surface
     * SQLSTATE 42501.  Both outcomes prove that the viewer did not write;
     * accepting a zero count avoids mistaking silent RLS filtering for a
     * successful mutation.
     */
    private void assertRlsWriteDenied(SqlCountOperation operation) throws Exception {
        try {
            assertEquals(0, operation.run(), "RLS must filter every viewer UPDATE");
        } catch (SQLException failure) {
            assertEquals("42501", failure.getSQLState(), failure.getMessage());
        }
    }

    private void setContext(Connection connection, UUID tenant, UUID project, UUID principal) throws SQLException {
        setConfig(connection, "test365alm.tenant_id", tenant.toString());
        setConfig(connection, "test365alm.project_id", project.toString());
        setConfig(connection, "test365alm.principal_id", principal.toString());
    }

    private static void setConfig(Connection connection, String key, String value) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeQuery().close();
        }
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
    }

    private void ownerUpdate(String sql, Object... values) throws SQLException {
        try (Connection connection = ownerConnection()) {
            execute(connection, sql, values);
        }
    }

    @FunctionalInterface
    private interface SqlOperation { void run() throws Exception; }

    @FunctionalInterface
    private interface SqlCountOperation { int run() throws Exception; }

    private Fixture fixture() throws SQLException {
        UUID admin = principal("admin");
        UUID member = principal("member");
        UUID viewer = principal("viewer");
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", tenant,
                    "req-tenant-" + tenant, "Requirement Tenant", admin);
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, tenant,
                    "Requirement Domain");
            for (UUID principal : List.of(admin, member, viewer)) {
                execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                        tenant, principal, principal.equals(admin) ? "{TENANT_ADMIN}" : "{MEMBER}");
            }
        }
        ProjectService.ProjectView project = projects.createProject(admin, tenant, domain,
                "REQ-" + UUID.randomUUID(), "Requirement Project");
        return new Fixture(admin, member, viewer, tenant, domain, project.id());
    }

    private UUID principal(String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                    id, "https://requirement-it.example/realm", kind + "-" + id, kind);
        }
        return id;
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        executeCount(connection, sql, values);
    }

    private static int executeCount(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private int ownerCount(String sql, Object... args) {
        try (Connection ignored = ownerConnection()) {
            return new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                    .queryForObject(sql, Integer.class, args);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String etag(RequirementService.RequirementView view) {
        return "\"" + view.rowVersion() + "\"";
    }

    private static String legacyCreateHash(String title, String body, String priority) {
        String canonical = String.join("\u0000", title, body, priority) + "\u0000";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(UUID admin, UUID member, UUID viewer, UUID tenant, UUID domain, UUID project) { }
}
