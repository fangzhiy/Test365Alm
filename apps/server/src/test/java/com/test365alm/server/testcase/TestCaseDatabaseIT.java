package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        assertEquals(oldSteps, historical.steps());
        assertEquals("Original", historical.title());
    }

    @Test
    void crossScopeReferencesRejectedAndHistoryImmutable() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("First"), key("create"));
        TestCaseService.TestCaseView second = tests.create(f.member(), f.project(), create("Second"), key("create"));
        UUID foreignStep = second.currentRevision().steps().get(0).stepKey();
        TestCaseService.RevisionCommand invalid = new TestCaseService.RevisionCommand("bad", "", "",
                List.of(new TestCaseService.StepCommand(foreignStep, 1, "spoof", "spoof")));
        ProjectAccessException error = assertThrows(ProjectAccessException.class,
                () -> tests.appendRevision(f.member(), f.project(), first.id(), invalid, etag(first), key("bad")));
        assertEquals("INVALID_REQUEST", error.code());
        assertEquals(1, tests.revisions(f.member(), f.project(), first.id()).size());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM test_step WHERE test_case_id = ?", first.id()));
    }

    @Test
    void concurrentRevisionSavesOneWins() throws Exception {
        Fixture f = fixture();
        projects.putMember(f.admin(), f.project(), f.member(), List.of("PROJECT_MEMBER"), 0L, false);
        TestCaseService.TestCaseView first = tests.create(f.member(), f.project(), create("Concurrent"), key("create"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<TestCaseService.TestCaseView> a = pool.submit(() -> {
                barrier.await();
                return revise(f, first, "A");
            });
            Future<TestCaseService.TestCaseView> b = pool.submit(() -> {
                barrier.await();
                return revise(f, first, "B");
            });
            int success = 0;
            int stale = 0;
            for (Future<TestCaseService.TestCaseView> future : List.of(a, b)) {
                try {
                    assertEquals(2, future.get().rowVersion());
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
        TestCaseService.TestCaseView one = tests.create(f.member(), f.project(), create("One"), key("number"));
        TestCaseService.TestCaseView two = tests.create(f.member(), f.project(), create("Two"), key("number"));
        assertEquals(Set.of(2L, 3L), Set.of(one.displayNumber(), two.displayNumber()));
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
        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.create(f.member(), f.project(), create("Audit failure"), key("audit")));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM test_case WHERE project_id = ?", f.project()));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM test_case_outbox_event WHERE project_id = ?", f.project()));
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }
        ownerUpdate("REVOKE INSERT ON test_case_outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.create(f.member(), f.project(), create("Outbox failure"), key("outbox")));
            assertEquals(0, ownerCount("SELECT COUNT(*) FROM test_case WHERE project_id = ?", f.project()));
        } finally {
            ownerUpdate("GRANT INSERT ON test_case_outbox_event TO " + RUNTIME_USER);
        }

        TestCaseService.TestCaseView baseline = tests.create(f.member(), f.project(), create("Rollback edit"), key("baseline"));
        ownerUpdate("REVOKE INSERT ON audit_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.appendRevision(f.member(), f.project(), baseline.id(),
                    new TestCaseService.RevisionCommand("Denied audit", "", "", commands(baseline.currentRevision().steps())),
                    etag(baseline), key("edit-audit")));
            assertEquals(1, tests.revisions(f.member(), f.project(), baseline.id()).size());
        } finally {
            ownerUpdate("GRANT INSERT ON audit_event TO " + RUNTIME_USER);
        }
        ownerUpdate("REVOKE INSERT ON test_case_outbox_event FROM " + RUNTIME_USER);
        try {
            assertThrows(DataAccessException.class, () -> tests.appendRevision(f.member(), f.project(), baseline.id(),
                    new TestCaseService.RevisionCommand("Denied outbox", "", "", commands(baseline.currentRevision().steps())),
                    etag(baseline), key("edit-outbox")));
            assertEquals(1, tests.revisions(f.member(), f.project(), baseline.id()).size());
        } finally {
            ownerUpdate("GRANT INSERT ON test_case_outbox_event TO " + RUNTIME_USER);
        }
    }

    private TestCaseService.TestCaseView revise(Fixture f, TestCaseService.TestCaseView first, String suffix) throws Exception {
        List<TestCaseService.StepView> steps = first.currentRevision().steps();
        return tests.appendRevision(f.member(), f.project(), first.id(), new TestCaseService.RevisionCommand(
                "Revision " + suffix, "", "", List.of(
                        new TestCaseService.StepCommand(steps.get(0).stepKey(), 1, suffix, "done"),
                        new TestCaseService.StepCommand(steps.get(1).stepKey(), 2, "Next", "done"))),
                etag(first), key("revision"));
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

