package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/** Real PostgreSQL proof for the first M08 identity/revision/step slice. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class TestCaseDatabaseIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("test_case_it")
            .withUsername("test_case_owner")
            .withPassword("test_case_owner_password")
            .withInitScript("r03-test-role.sql");

    @Autowired private TestCaseService tests;
    @Autowired private ProjectService projects;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Test
    void memberCreatesAndReadsInitialSteps() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView created = tests.create(f.member(), f.project(), create("Login"), key("create"));
        assertEquals(1, created.displayNumber());
        assertEquals(1, created.currentRevision().revisionNo());
        assertEquals(2, created.currentRevision().steps().size());
        assertEquals(created.currentRevision().steps(), tests.getAuthorized(f.member(), f.project(), created.id()).currentRevision().steps());
    }

    @Test
    void stepEditsCreateImmutableRevision() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("Original"), key("create"));
        List<TestCaseService.StepView> oldSteps = first.currentRevision().steps();
        TestCaseService.RevisionCommand edit = new TestCaseService.RevisionCommand("Edited", "description", "setup",
                List.of(new TestCaseService.StepCommand(oldSteps.get(1).stepKey(), 1, "Second first", "Second done"),
                        new TestCaseService.StepCommand(oldSteps.get(0).stepKey(), 2, "First second", "First done"),
                        new TestCaseService.StepCommand(null, 3, "Cleanup", "Clean")));
        TestCaseService.TestCaseView second = tests.appendRevision(f.member(), f.project(), first.id(), edit,
                etag(first), key("revision"));
        assertEquals(2, second.currentRevision().revisionNo());
        assertEquals(oldSteps.get(1).stepKey(), second.currentRevision().steps().get(0).stepKey());
        assertEquals(oldSteps.get(0).stepKey(), second.currentRevision().steps().get(1).stepKey());
        assertTrue(!second.currentRevision().steps().get(2).stepKey().equals(oldSteps.get(0).stepKey()));
        TestCaseService.RevisionView historical = tests.revision(f.member(), f.project(), first.id(), first.currentRevision().id());
        assertEquals(first.currentRevision(), historical, "Every historical field, author and timestamp must be immutable");

        // Remove the formerly first step, retain the other stable key and the
        // newly assigned key, and edit both text fields in a third snapshot.
        TestCaseService.TestCaseView third = tests.appendRevision(f.member(), f.project(), first.id(),
                new TestCaseService.RevisionCommand("Third", "changed description", "changed setup", List.of(
                        new TestCaseService.StepCommand(second.currentRevision().steps().get(2).stepKey(), 1, "Cleanup edited", "Still clean"),
                        new TestCaseService.StepCommand(oldSteps.get(1).stepKey(), 2, "Second edited", "Second expected edited"))),
                etag(second), key("remove"));
        assertEquals(3, third.currentRevision().revisionNo());
        assertEquals(List.of(second.currentRevision().steps().get(2).stepKey(), oldSteps.get(1).stepKey()),
                third.currentRevision().steps().stream().map(TestCaseService.StepView::stepKey).toList());
        assertEquals(first.currentRevision(), tests.revision(f.member(), f.project(), first.id(), first.currentRevision().id()));
        assertEquals(second.currentRevision(), tests.revision(f.member(), f.project(), first.id(), second.currentRevision().id()));

        Map<String, List<String>> beforeNoOp = snapshot(f.project());
        TestCaseService.RevisionCommand unchanged = revisionCommand(third);
        TestCaseService.TestCaseView noOp = tests.appendRevision(f.member(), f.project(), first.id(), unchanged,
                etag(third), key("no-op"));
        assertEquals(third, noOp);
        Map<String, List<String>> afterNoOp = snapshot(f.project());
        for (String table : List.of("test_case", "test_revision", "test_step", "audit_event", "test_case_outbox_event")) {
            assertEquals(beforeNoOp.get(table), afterNoOp.get(table), "No-op must not change " + table);
        }
        ProjectAccessException stale = assertThrows(ProjectAccessException.class, () -> tests.appendRevision(
                f.member(), f.project(), first.id(), unchanged, etag(second), key("stale-no-op")));
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.status());
        assertEquals("STALE_VERSION", stale.code());
        assertEquals(afterNoOp, snapshot(f.project()), "A stale no-op leaves no idempotency row or other effects");
    }

    @Test
    void savedRevisionStepsAreImmutableForRuntimeMember() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String createKey = key("immutable-create");
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("Immutable steps"), createKey);
        String revisionKey = key("immutable-revision");
        TestCaseService.TestCaseView second = tests.appendRevision(f.member(), f.project(), first.id(),
                new TestCaseService.RevisionCommand("Revision two", "", "", commands(first.currentRevision().steps())),
                etag(first), revisionKey);
        Map<String, List<String>> before = fullSnapshot(f.project());

        try (Connection connection = runtimeConnection()) {
            setContext(connection, f.tenant(), f.project(), f.member());
            assertSqlState("42501", () -> execute(connection, "INSERT INTO test_step"
                    + " (tenant_id, project_id, test_case_id, revision_id, step_key, ordinal, action, expected)"
                    + " VALUES (?, ?, ?, ?, ?, 3, 'late historical action', 'late historical result')",
                    f.tenant(), f.project(), first.id(), first.currentRevision().id(), UUID.randomUUID()));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO test_step"
                    + " (tenant_id, project_id, test_case_id, revision_id, step_key, ordinal, action, expected)"
                    + " VALUES (?, ?, ?, ?, ?, 3, 'late current action', 'late current result')",
                    f.tenant(), f.project(), second.id(), second.currentRevision().id(), UUID.randomUUID()));
        }

        assertEquals(before, fullSnapshot(f.project()),
                "rejected historical/current step inserts leave business, audit, outbox and idempotency rows unchanged");
        assertEquals(first, tests.create(f.member(), f.project(), create("Immutable steps"), createKey),
                "create idempotency replay keeps the original step collection");
        assertEquals(second, tests.appendRevision(f.member(), f.project(), second.id(),
                new TestCaseService.RevisionCommand("Revision two", "", "", commands(first.currentRevision().steps())),
                etag(first), revisionKey),
                "revision idempotency replay keeps the original step collection");
        assertEquals(first.currentRevision(), tests.revision(f.member(), f.project(), first.id(), first.currentRevision().id()));
    }

    @Test
    void crossScopeReferencesRejectedAndHistoryImmutable() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("First"), key("create"));
        TestCaseService.TestCaseView second = tests.create(f.member(), f.project(), create("Second"), key("create"));
        UUID sameTenantProject = anotherProject(f).id();
        projects.putMember(f.admin(), sameTenantProject, f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView crossProject = tests.create(f.member(), sameTenantProject, create("Cross project"), key("create"));
        Fixture otherTenant = fixture();
        projects.putMember(otherTenant.admin(), otherTenant.project(), otherTenant.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView crossTenant = tests.create(otherTenant.member(), otherTenant.project(), create("Cross tenant"), key("create"));
        Map<String, List<String>> before = snapshot(f.project());
        for (TestCaseService.TestCaseView foreign : List.of(second, crossProject, crossTenant)) {
            TestCaseService.RevisionCommand invalid = new TestCaseService.RevisionCommand("bad", "", "",
                    List.of(new TestCaseService.StepCommand(foreign.currentRevision().steps().get(0).stepKey(), 1, "spoof", "spoof")));
            ProjectAccessException error = assertThrows(ProjectAccessException.class,
                    () -> tests.appendRevision(f.member(), f.project(), first.id(), invalid, etag(first), key("bad")));
            assertEquals(HttpStatus.BAD_REQUEST, error.status());
            assertEquals("INVALID_REQUEST", error.code());
            ProjectAccessException hiddenRevision = assertThrows(ProjectAccessException.class,
                    () -> tests.revision(f.member(), f.project(), first.id(), foreign.currentRevision().id()));
            assertEquals(HttpStatus.NOT_FOUND, hiddenRevision.status());
            try (Connection connection = runtimeConnection()) {
                setContext(connection, f.tenant(), f.project(), f.member());
                assertSqlState("23503", () -> execute(connection,
                        "UPDATE test_case SET current_revision_id = ? WHERE id = ?", foreign.currentRevision().id(), first.id()));
                assertSqlState("23503", () -> execute(connection, "INSERT INTO test_step"
                        + " (tenant_id, project_id, test_case_id, revision_id, step_key, ordinal, action, expected)"
                        + " VALUES (?, ?, ?, ?, ?, 90, 'foreign', 'foreign')", f.tenant(), f.project(), first.id(),
                        foreign.currentRevision().id(), UUID.randomUUID()));
                assertSqlState("23503", () -> execute(connection, "INSERT INTO test_case_outbox_event"
                        + " (tenant_id, project_id, test_case_id, revision_id, event_type) VALUES (?, ?, ?, ?, 'test_case.revised')",
                        f.tenant(), f.project(), first.id(), foreign.currentRevision().id()));
            }
        }
        // A writer is still not a history owner. Cover all six denied DML
        // operations, not merely viewer policy filtering.
        try (Connection connection = runtimeConnection()) {
            setContext(connection, f.tenant(), f.project(), f.member());
            for (String table : List.of("test_revision", "test_step")) {
                String column = table.equals("test_revision") ? "title" : "action";
                assertSqlState("42501", () -> execute(connection, "UPDATE " + table + " SET " + column + " = 'tampered'"));
                assertSqlState("42501", () -> execute(connection, "DELETE FROM " + table));
                assertSqlState("42501", () -> execute(connection, "TRUNCATE " + table));
            }
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM test_case WHERE id = ?", crossProject.id()));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM test_case WHERE id = ?", crossTenant.id()));
            setContext(connection, f.tenant(), sameTenantProject, f.member());
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM test_case WHERE id = ?", first.id()));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_case WHERE id = ?", crossProject.id()));
        }
        assertEquals(before, snapshot(f.project()), "Rejected references and runtime history DML leave every row unchanged");
    }

    @Test
    void concurrentRevisionSavesOneWins() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("Concurrent"), key("create"));
        String keyA = key("revision-A");
        String keyB = key("revision-B");
        TestCaseService.TestCaseView winner = null;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<TestCaseService.TestCaseView> a = pool.submit(() -> {
                barrier.await();
                return revise(f, first, "A", keyA);
            });
            Future<TestCaseService.TestCaseView> b = pool.submit(() -> {
                barrier.await();
                return revise(f, first, "B", keyB);
            });
            int success = 0;
            int stale = 0;
            for (Future<TestCaseService.TestCaseView> future : List.of(a, b)) {
                try {
                    winner = future.get(30, TimeUnit.SECONDS);
                    assertEquals(2, winner.rowVersion());
                    success++;
                } catch (ExecutionException ex) {
                    ProjectAccessException error = assertInstanceOf(ProjectAccessException.class, ex.getCause());
                    assertEquals("STALE_VERSION", error.code());
                    assertEquals(HttpStatus.PRECONDITION_FAILED, error.status());
                    stale++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, stale);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, tests.revisions(f.member(), f.project(), first.id()).size());
        assertEquals(winner, tests.getAuthorized(f.member(), f.project(), first.id()));
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM test_step WHERE test_case_id = ?", first.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_case_outbox_event WHERE test_case_id = ?", first.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_type = 'test_case' AND object_id = ?", first.id()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_case_idempotency WHERE project_id = ? AND idempotency_key IN (?, ?)",
                f.project(), keyA, keyB));
        String winnerKey = winner.currentRevision().title().equals("Revision A") ? keyA : keyB;
        String loserKey = winnerKey.equals(keyA) ? keyB : keyA;
        assertEquals(0, ownerCount("SELECT COUNT(*) FROM test_case_idempotency WHERE project_id = ? AND idempotency_key = ?", f.project(), loserKey));
    }

    @Test
    void idempotentCreateAndRevisionReplay() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String key = key("same");
        TestCaseService.CreateCommand command = create("Replay");
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), command, key);
        TestCaseService.TestCaseView replay = tests.create(f.member(), f.project(), command, key);
        assertEquals(first.id(), replay.id());
        assertEquals(first.currentRevision().id(), replay.currentRevision().id());
        ProjectAccessException reused = assertThrows(ProjectAccessException.class,
                () -> tests.create(f.member(), f.project(), create("Different"), key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_case WHERE project_id = ?", f.project()));
        String revisionKey = key("revision");
        TestCaseService.RevisionCommand revisionCommand = new TestCaseService.RevisionCommand("Replay revision", "", "", commands(first.currentRevision().steps()));
        TestCaseService.TestCaseView firstRevision = tests.appendRevision(f.member(), f.project(), first.id(),
                revisionCommand, etag(first), revisionKey);
        TestCaseService.TestCaseView revisionReplay = tests.appendRevision(f.member(), f.project(), first.id(),
                revisionCommand, etag(first), revisionKey);
        assertEquals(firstRevision.currentRevision().id(), revisionReplay.currentRevision().id());
        ProjectAccessException revisionReused = assertThrows(ProjectAccessException.class, () -> tests.appendRevision(f.member(), f.project(), first.id(),
                new TestCaseService.RevisionCommand("Different revision", "", "", commands(first.currentRevision().steps())), etag(first), revisionKey));
        assertEquals("IDEMPOTENCY_KEY_REUSED", revisionReused.code());
        assertEquals(2, firstRevision.currentRevision().revisionNo());
        assertEquals(2, revisionReplay.currentRevision().revisionNo());
    }

    @Test
    void sameKeyConcurrentCreatesProduceOneCaseAndDifferentKeysKeepNumbersUnique() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String sameKey = key("concurrent");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<TestCaseService.TestCaseView> a = pool.submit(() -> { barrier.await(); return tests.create(f.member(), f.project(), create("Same"), sameKey); });
            Future<TestCaseService.TestCaseView> b = pool.submit(() -> { barrier.await(); return tests.create(f.member(), f.project(), create("Same"), sameKey); });
            TestCaseService.TestCaseView first = a.get();
            TestCaseService.TestCaseView second = b.get();
            assertEquals(first.id(), second.id());
            assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_case WHERE project_id = ?", f.project()));
        } finally {
            pool.shutdownNow();
        }
        Map<String, List<String>> beforeConflict = snapshot(f.project());
        ProjectAccessException conflict = assertThrows(ProjectAccessException.class,
                () -> tests.create(f.member(), f.project(), create("Different"), sameKey));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
        assertEquals(beforeConflict, snapshot(f.project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_revision WHERE project_id = ?", f.project()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_step WHERE project_id = ?", f.project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_case_outbox_event WHERE project_id = ?", f.project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM audit_event WHERE project_id = ? AND object_type = 'test_case'", f.project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM test_case_idempotency WHERE project_id = ?", f.project()));

        ExecutorService distinctPool = Executors.newFixedThreadPool(2);
        CyclicBarrier distinctBarrier = new CyclicBarrier(2);
        try {
            Future<TestCaseService.TestCaseView> one = distinctPool.submit(() -> {
                distinctBarrier.await(10, TimeUnit.SECONDS);
                return tests.create(f.member(), f.project(), create("One"), key("number"));
            });
            Future<TestCaseService.TestCaseView> two = distinctPool.submit(() -> {
                distinctBarrier.await(10, TimeUnit.SECONDS);
                return tests.create(f.member(), f.project(), create("Two"), key("number"));
            });
            assertEquals(Set.of(2L, 3L), Set.of(one.get(30, TimeUnit.SECONDS).displayNumber(), two.get(30, TimeUnit.SECONDS).displayNumber()));
        } finally {
            distinctPool.shutdownNow();
        }
        assertEquals(3, ownerCount("SELECT COUNT(DISTINCT display_number) FROM test_case WHERE project_id = ?", f.project()));
        assertEquals(3, ownerCount("SELECT COUNT(*) FROM test_case WHERE project_id = ?", f.project()));
    }

    @Test
    void sameKeyConcurrentRevisionRequestsReturnOneFrozenResult() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("Initial"), key("create"));
        String sharedKey = key("concurrent-revision");
        TestCaseService.RevisionCommand command = new TestCaseService.RevisionCommand("Concurrent append", "description", "setup",
                List.of(new TestCaseService.StepCommand(first.currentRevision().steps().get(1).stepKey(), 1, "Updated", "Updated result"),
                        new TestCaseService.StepCommand(null, 2, "Added", "Added result")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        TestCaseService.TestCaseView result;
        try {
            java.util.concurrent.Callable<TestCaseService.TestCaseView> append = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return tests.appendRevision(f.member(), f.project(), first.id(), command, etag(first), sharedKey);
            };
            Future<TestCaseService.TestCaseView> a = pool.submit(append);
            Future<TestCaseService.TestCaseView> b = pool.submit(append);
            result = a.get(30, TimeUnit.SECONDS);
            assertEquals(result, b.get(30, TimeUnit.SECONDS), "Both independent transactions return one frozen result including generated step keys");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, result.rowVersion());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_revision WHERE test_case_id = ?", first.id()));
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM test_step WHERE test_case_id = ?", first.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_case_outbox_event WHERE test_case_id = ?", first.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_type = 'test_case' AND object_id = ?", first.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_case_idempotency WHERE test_case_id = ?", first.id()));

        // Advance the current pointer, then prove a retry still returns the
        // original result and cannot pair revision 2 with rowVersion 3.
        tests.appendRevision(f.member(), f.project(), first.id(), new TestCaseService.RevisionCommand(
                "Later", "later description", "later setup", commands(result.currentRevision().steps())), etag(result), key("later"));
        Map<String, List<String>> beforeReplay = snapshot(f.project());
        assertEquals(result, tests.appendRevision(f.member(), f.project(), first.id(), command, etag(first), sharedKey));
        ProjectAccessException conflict = assertThrows(ProjectAccessException.class, () -> tests.appendRevision(
                f.member(), f.project(), first.id(), new TestCaseService.RevisionCommand("Changed intent", command.description(), command.preconditions(), command.steps()),
                etag(first), sharedKey));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
        assertEquals(beforeReplay, snapshot(f.project()));
    }

    @Test
    void viewerCannotModifyHistoryOrWriteOutboxThroughRuntimeRls() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        projects.putMember(f.admin(), f.project(), f.viewer(), List.of("PROJECT_VIEWER"), 0L, false);
        TestCaseService.TestCaseView created = tests.create(f.member(), f.project(), create("Protected"), key("create"));
        try (Connection connection = runtimeConnection()) {
            setContext(connection, f.tenant(), f.project(), f.viewer());
            assertSqlState("42501", () -> execute(connection, "UPDATE test_revision SET title = title WHERE id = ?", created.currentRevision().id()));
            assertSqlState("42501", () -> execute(connection, "DELETE FROM test_revision WHERE id = ?", created.currentRevision().id()));
            assertSqlState("42501", () -> execute(connection, "TRUNCATE test_step", new Object[0]));
            assertSqlState("42501", () -> execute(connection, "INSERT INTO test_case_outbox_event (tenant_id, project_id, test_case_id, revision_id, event_type) VALUES (?, ?, ?, ?, 'viewer')", f.tenant(), f.project(), created.id(), created.currentRevision().id()));
        }
        assertEquals(1, tests.revisions(f.member(), f.project(), created.id()).size());
    }

    @Test
    void auditAndOutboxFailuresRollBack() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        Map<String, List<String>> beforeCreateAudit = fullSnapshot(f.project());
        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.create(f.member(), f.project(), create("Audit failure"), key("audit")));
            assertEquals(beforeCreateAudit, fullSnapshot(f.project()), "Audit failure rolls back case, revision, steps, outbox and idempotency");
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }
        Map<String, List<String>> beforeCreateOutbox = fullSnapshot(f.project());
        ownerUpdate("REVOKE INSERT ON test_case_outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.create(f.member(), f.project(), create("Outbox failure"), key("outbox")));
            assertEquals(beforeCreateOutbox, fullSnapshot(f.project()), "Outbox failure rolls back case, revision, steps, audit and idempotency");
        } finally {
            ownerUpdate("GRANT INSERT ON test_case_outbox_event TO " + RUNTIME_USER);
        }

        TestCaseService.TestCaseView baseline = tests.create(f.member(), f.project(), create("Rollback edit"), key("baseline"));
        Map<String, List<String>> beforeEditAudit = fullSnapshot(f.project());
        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.appendRevision(f.member(), f.project(), baseline.id(),
                    new TestCaseService.RevisionCommand("Denied audit", "", "", commands(baseline.currentRevision().steps())),
                    etag(baseline), key("edit-audit")));
            assertEquals(beforeEditAudit, fullSnapshot(f.project()), "Edit audit failure rolls back current pointer, history, steps, outbox and idempotency");
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }
        Map<String, List<String>> beforeEditOutbox = fullSnapshot(f.project());
        ownerUpdate("REVOKE INSERT ON test_case_outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.appendRevision(f.member(), f.project(), baseline.id(),
                    new TestCaseService.RevisionCommand("Denied outbox", "", "", commands(baseline.currentRevision().steps())),
                    etag(baseline), key("edit-outbox")));
            assertEquals(beforeEditOutbox, fullSnapshot(f.project()), "Edit outbox failure rolls back current pointer, history, steps, audit and idempotency");
        } finally {
            ownerUpdate("GRANT INSERT ON test_case_outbox_event TO " + RUNTIME_USER);
        }
        TestCaseService.TestCaseView recovered = tests.appendRevision(f.member(), f.project(), baseline.id(),
                new TestCaseService.RevisionCommand("Recovered", "", "", commands(baseline.currentRevision().steps())),
                etag(baseline), key("edit-recovered"));
        assertEquals(2, recovered.currentRevision().revisionNo());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_revision WHERE test_case_id = ?", baseline.id()));
        assertEquals(4, ownerCount("SELECT COUNT(*) FROM test_step WHERE test_case_id = ?", baseline.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_type = 'test_case' AND object_id = ?", baseline.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_case_outbox_event WHERE test_case_id = ?", baseline.id()));
    }

    private TestCaseService.TestCaseView revise(Fixture f, TestCaseService.TestCaseView first, String suffix, String idempotencyKey) throws Exception {
        List<TestCaseService.StepView> steps = first.currentRevision().steps();
        return tests.appendRevision(f.member(), f.project(), first.id(), new TestCaseService.RevisionCommand(
                "Revision " + suffix, "", "", List.of(
                        new TestCaseService.StepCommand(steps.get(0).stepKey(), 1, suffix, "done"),
                        new TestCaseService.StepCommand(steps.get(1).stepKey(), 2, "Next", "done"))),
                etag(first), idempotencyKey);
    }

    private static TestCaseService.RevisionCommand revisionCommand(TestCaseService.TestCaseView view) {
        return new TestCaseService.RevisionCommand(view.currentRevision().title(), view.currentRevision().description(),
                view.currentRevision().preconditions(), commands(view.currentRevision().steps()));
    }

    private ProjectService.ProjectView anotherProject(Fixture f) throws SQLException {
        UUID domain = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, f.tenant(),
                    "Test Case Second Domain " + domain);
        }
        return projects.createProject(f.admin(), f.tenant(), domain, "TC2-" + UUID.randomUUID(), "Test Case Second Project");
    }

    private Map<String, List<String>> snapshot(UUID projectId) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : Map.of(
                "test_case", "SELECT row_to_json(t)::text FROM (SELECT * FROM test_case WHERE project_id = ?) t",
                "test_revision", "SELECT row_to_json(t)::text FROM (SELECT * FROM test_revision WHERE project_id = ?) t",
                "test_step", "SELECT row_to_json(t)::text FROM (SELECT * FROM test_step WHERE project_id = ?) t",
                "audit_event", "SELECT row_to_json(t)::text FROM (SELECT * FROM audit_event WHERE project_id = ?) t",
                "test_case_outbox_event", "SELECT row_to_json(t)::text FROM (SELECT * FROM test_case_outbox_event WHERE project_id = ?) t"
        ).entrySet()) {
            List<String> rows;
            try (Connection connection = ownerConnection(); var statement = connection.prepareStatement(entry.getValue())) {
                statement.setObject(1, projectId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    rows = new ArrayList<>();
                    while (resultSet.next()) rows.add(resultSet.getString(1));
                }
            }
            rows.sort(String::compareTo);
            result.put(entry.getKey(), rows);
        }
        return result;
    }

    private Map<String, List<String>> fullSnapshot(UUID projectId) throws SQLException {
        Map<String, List<String>> result = snapshot(projectId);
        try (Connection connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "SELECT row_to_json(t)::text FROM (SELECT * FROM test_case_idempotency WHERE project_id = ?) t")) {
            statement.setObject(1, projectId);
            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) rows.add(resultSet.getString(1));
            }
            rows.sort(String::compareTo);
            result.put("test_case_idempotency", rows);
        }
        return result;
    }

    private static List<TestCaseService.StepCommand> commands(List<TestCaseService.StepView> steps) {
        return steps.stream().map(step -> new TestCaseService.StepCommand(step.stepKey(), step.ordinal(), step.action(), step.expected())).toList();
    }

    private Fixture fixture() throws SQLException {
        UUID admin = principal("admin");
        UUID member = principal("member");
        UUID viewer = principal("viewer");
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", tenant,
                    "test-case-tenant-" + tenant, "Test Case Tenant", admin);
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, tenant,
                    "Test Case Domain");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    tenant, admin, "{TENANT_ADMIN}");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    tenant, member, "{MEMBER}");
            execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                    tenant, viewer, "{MEMBER}");
        }
        ProjectService.ProjectView project = projects.createProject(admin, tenant, domain,
                "TC-" + UUID.randomUUID(), "Test Case Project");
        return new Fixture(admin, member, viewer, tenant, project.id());
    }

    private UUID principal(String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                    id, "https://test-case-it.example/realm", kind + "-" + id, kind);
        }
        return id;
    }

    private int ownerCount(String sql, Object... args) throws SQLException {
        try (Connection connection = ownerConnection()) {
            JdbcTemplate owner = new JdbcTemplate(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                    new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
            return owner.queryForObject(sql, Integer.class, args);
        }
    }

    private long scalar(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void ownerUpdate(String sql) throws SQLException {
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
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

    private static void assertSqlState(String expected, SqlOperation operation) throws Exception {
        SQLException failure = assertThrows(SQLException.class, operation::run);
        assertEquals(expected, failure.getSQLState(), failure.getMessage());
    }

    @FunctionalInterface
    private interface SqlOperation { void run() throws Exception; }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static TestCaseService.CreateCommand create(String title) {
        return new TestCaseService.CreateCommand("MANUAL", title, "description", "precondition",
                List.of(new TestCaseService.StepCommand(null, 1, "Open", "Opened"),
                        new TestCaseService.StepCommand(null, 2, "Submit", "Submitted")));
    }

    private static String key(String prefix) { return prefix + "-" + UUID.randomUUID(); }
    private static String etag(TestCaseService.TestCaseView view) { return "\"" + view.rowVersion() + "\""; }

    private record Fixture(UUID admin, UUID member, UUID viewer, UUID tenant, UUID project) { }
}
