package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import com.test365alm.server.testcase.TestCaseService;

/** Real PostgreSQL proof for the first M09 persisted execution slice. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ManualExecutionDatabaseIT {
    private static final String IMAGE = "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("m09_it").withUsername("m09_owner").withPassword("m09_owner_password").withInitScript("r03-test-role.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private ExecutionService executions;
    @Autowired private TestCaseService tests;

    @Test
    void createsImmutableManifestExecutesAndRerunsWithoutChangingSource() throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "M09 source", "description", "setup",
                        List.of(new TestCaseService.StepCommand(null, 1, "Open page", "Page is visible"))), "m09-create-001");
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "Smoke", "manual", "m09-set-001");
        assertEquals(set.id(), executions.createSet(f.member(), f.project(), "Smoke", "manual", "m09-set-001").id());
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), "m09-instance-001");
        ExecutionService.RunDetail first = executions.createRun(f.member(), f.project(), instance.id(), "m09-run-001");
        assertEquals(source.currentRevision().title(), first.manifest().title());
        assertEquals(source.currentRevision().steps().get(0).stepKey(), first.manifest().steps().get(0).stepKey());
        ExecutionService.AttemptView saved = executions.saveStep(f.member(), f.project(), first.run().id(), first.currentAttempt().id(),
                first.currentAttempt().steps().get(0).stepKey(), "visible", "PASS", 1, "m09-step-001");
        ExecutionService.AttemptView finished = executions.finish(f.member(), f.project(), first.run().id(), saved.id(), saved.rowVersion(), "m09-finish-001");
        assertEquals("PASS", finished.conclusion());
        ExecutionService.AttemptView retry = executions.rerun(f.member(), f.project(), first.run().id(), "m09-rerun-001");
        assertEquals(2, retry.attemptNo());
        ExecutionService.AttemptView retrySaved = executions.saveStep(f.member(), f.project(), first.run().id(), retry.id(),
                retry.steps().get(0).stepKey(), "visible again", "PASS", 1, "m09-step-002");
        assertEquals("PASS", executions.finish(f.member(), f.project(), first.run().id(), retry.id(), retrySaved.rowVersion(), "m09-finish-002").conclusion());
        assertEquals(3, executions.rerun(f.member(), f.project(), first.run().id(), "m09-rerun-003").attemptNo());
    }

    @Test
    void zeroStepRevisionCannotStartAManualRun() throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "Empty", "", "", List.of()), "m09-empty-001");
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "Empty", "", "m09-empty-set");
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), "m09-empty-instance");
        ProjectAccessException error = assertThrows(ProjectAccessException.class, () -> executions.createRun(f.member(), f.project(), instance.id(), "m09-empty-run"));
        assertEquals("NO_EXECUTABLE_STEPS", error.code());
    }

    /** K07: two independent transactions using one stale step version have one winner. */
    @Test
    void concurrentStepWritesRejectExactlyOneStaleVersion() throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "Concurrent", "", "",
                        List.of(new TestCaseService.StepCommand(null, 1, "Open", "Visible"))), "m09-k07-case");
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "K07", "", "m09-k07-set");
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), "m09-k07-instance");
        ExecutionService.RunDetail run = executions.createRun(f.member(), f.project(), instance.id(), "m09-k07-run");
        UUID step = run.currentAttempt().steps().get(0).stepKey();
        List<Outcome<ExecutionService.AttemptView>> outcomes = concurrently(
                () -> executions.saveStep(f.member(), f.project(), run.run().id(), run.currentAttempt().id(), step,
                        "first", "PASS", 1, "m09-k07-step-a"),
                () -> executions.saveStep(f.member(), f.project(), run.run().id(), run.currentAttempt().id(), step,
                        "second", "PASS", 1, "m09-k07-step-b"));

        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        Outcome<ExecutionService.AttemptView> rejected = outcomes.stream().filter(o -> !o.succeeded()).findFirst().orElseThrow();
        assertTrue(rejected.error() instanceof ProjectAccessException, rejected.toString());
        ProjectAccessException failure = (ProjectAccessException) rejected.error();
        assertEquals("STALE_VERSION", failure.code());
        assertEquals(412, failure.status().value());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=? AND event_type='STEP_RECORDED'", f.project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=? AND route LIKE 'step:%'", f.project()));
        assertEquals(2, ownerCount("SELECT row_version FROM run_step WHERE project_id=?", f.project()));
    }

    /** K08: concurrent run starts with one key return the same frozen run response. */
    @Test
    void concurrentSameKeyRunCreationReplaysTheFrozenResponse() throws Exception {
        PreparedRun prepared = preparedSource("m09-k08-same-run", "m09-k08-same-run-source");
        String key = "m09-k08-same-run-key";
        List<Outcome<ExecutionService.RunDetail>> outcomes = concurrently(
                () -> executions.createRun(prepared.fixture().member(), prepared.fixture().project(), prepared.instance().id(), key),
                () -> executions.createRun(prepared.fixture().member(), prepared.fixture().project(), prepared.instance().id(), key));
        assertEquals(2, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        List<ExecutionService.RunDetail> runs = outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
        UUID run = runs.get(0).run().id();
        assertEquals(run, runs.get(1).run().id());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_run WHERE project_id=?", prepared.fixture().project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_manifest WHERE project_id=?", prepared.fixture().project()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=? AND route='run:create'", prepared.fixture().project()));
    }

    /** K08: same-key step retries do not append a second result/event. */
    @Test
    void concurrentSameKeyStepSaveReplaysTheFrozenResponse() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-same-step", "m09-k08-same-step-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        String key = "m09-k08-same-step-key";
        List<Outcome<ExecutionService.AttemptView>> outcomes = concurrently(
                () -> executions.saveStep(actor, project, run, attempt, step, "same", "PASS", 1, key),
                () -> executions.saveStep(actor, project, run, attempt, step, "same", "PASS", 1, key));
        assertEquals(2, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        List<ExecutionService.AttemptView> attempts = outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
        assertEquals(attempts.get(0).rowVersion(), attempts.get(1).rowVersion());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=? AND event_type='STEP_RECORDED'", project));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=? AND route LIKE 'step:%'", project));
    }

    /** K08: reusing a step key for different content is rejected without a second result. */
    @Test
    void sameKeyDifferentStepContentIsRejectedWithoutSideEffects() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-reused-step", "m09-k08-reused-step-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        String key = "m09-k08-reused-step-key";
        executions.saveStep(actor, project, run, attempt, step, "original", "PASS", 1, key);
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project);
        ProjectAccessException reused = assertThrows(ProjectAccessException.class, () -> executions.saveStep(actor, project, run, attempt,
                step, "different", "PASS", 1, key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_step WHERE attempt_id=? AND actual_result='original'", attempt));
    }

    /** K07: a rerun race creates one active attempt; the loser receives the contract error. */
    @Test
    void concurrentRerunsAllowOnlyOneActiveAttempt() throws Exception {
        PreparedRun prepared = preparedRun("m09-k07-rerun", "m09-k07-rerun-source");
        ExecutionService.AttemptView saved = executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                prepared.run().run().id(), prepared.run().currentAttempt().id(),
                prepared.run().currentAttempt().steps().get(0).stepKey(), "done", "PASS", 1, "m09-k07-rerun-step");
        executions.finish(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(),
                saved.id(), saved.rowVersion(), "m09-k07-rerun-finish");

        List<Outcome<ExecutionService.AttemptView>> outcomes = concurrently(
                () -> executions.rerun(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(), "m09-k07-rerun-a"),
                () -> executions.rerun(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(), "m09-k07-rerun-b"));
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        ProjectAccessException rejected = (ProjectAccessException) outcomes.stream().filter(o -> !o.succeeded())
                .findFirst().orElseThrow().error();
        assertEquals("ACTIVE_ATTEMPT_EXISTS", rejected.code());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE run_id=?", prepared.run().run().id()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE run_id=? AND status <> 'FINISHED'", prepared.run().run().id()));
    }

    /** K07: a step write and completion sharing one attempt version have one committed winner. */
    @Test
    void concurrentStepWriteAndFinishHaveOneVersionedWinner() throws Exception {
        PreparedRun prepared = preparedRun("m09-k07-finish-race", "m09-k07-finish-race-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        // Finish requires every step to have a conclusion. Establish that valid
        // baseline first, then race a re-record against the terminal command.
        ExecutionService.AttemptView baseline = executions.saveStep(actor, project, run, attempt, step,
                "baseline", "PASS", 1, "m09-k07-finish-race-baseline");
        List<Outcome<ExecutionService.AttemptView>> outcomes = concurrently(
                () -> executions.saveStep(actor, project, run, attempt, step, "late write", "PASS", 2, "m09-k07-finish-race-step"),
                () -> executions.finish(actor, project, run, attempt, baseline.rowVersion(), "m09-k07-finish-race-finish"));
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        ProjectAccessException rejected = (ProjectAccessException) outcomes.stream().filter(o -> !o.succeeded())
                .findFirst().orElseThrow().error();
        assertEquals("STALE_VERSION", rejected.code());
        ExecutionService.AttemptView current = executions.attempt(actor, project, run, attempt);
        assertTrue("RUNNING".equals(current.status()) || "FINISHED".equals(current.status()));
        assertEquals(2, ownerCount("SELECT row_version FROM run_step WHERE attempt_id=?", attempt));
        assertTrue(ownerCount("SELECT COUNT(*) FROM execution_event WHERE run_id=? AND event_type IN ('STEP_RECORDED','RUN_FINISHED')", run) >= 2);
    }

    /** K07/K08: same idempotency key concurrently replays one frozen response. */
    @Test
    void concurrentSameKeyRerunReplaysTheFrozenResponse() throws Exception {
        PreparedRun prepared = preparedRun("m09-k07-same-key", "m09-k07-same-key-source");
        ExecutionService.AttemptView saved = executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                prepared.run().run().id(), prepared.run().currentAttempt().id(),
                prepared.run().currentAttempt().steps().get(0).stepKey(), "done", "PASS", 1, "m09-k07-same-key-step");
        executions.finish(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(),
                saved.id(), saved.rowVersion(), "m09-k07-same-key-finish");
        String key = "m09-k07-same-key-rerun";
        List<Outcome<ExecutionService.AttemptView>> outcomes = concurrently(
                () -> executions.rerun(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(), key),
                () -> executions.rerun(prepared.fixture().member(), prepared.fixture().project(), prepared.run().run().id(), key));
        assertEquals(2, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        UUID attemptId = outcomes.get(0).value().id();
        assertEquals(attemptId, outcomes.get(1).value().id());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE run_id=?", prepared.run().run().id()));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=? AND route LIKE 'rerun:%'", prepared.fixture().project()));
    }

    /** K08: permission failure in audit append rolls back run, manifest, events and claim. */
    @Test
    void auditInsertFailureRollsBackRunCreation() throws Exception {
        PreparedRun prepared = preparedSource("m09-k08-audit", "m09-k08-audit-source");
        int runs = ownerCount("SELECT COUNT(*) FROM execution_run WHERE project_id=?", prepared.fixture().project());
        int manifests = ownerCount("SELECT COUNT(*) FROM execution_manifest WHERE project_id=?", prepared.fixture().project());
        int manifestSteps = ownerCount("SELECT COUNT(*) FROM execution_manifest_step WHERE project_id=?", prepared.fixture().project());
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project());
        int outbox = ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project());
        int claims = ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project());
        revokeInsert("audit_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.createRun(prepared.fixture().member(), prepared.fixture().project(),
                    prepared.instance().id(), "m09-k08-audit-run"));
        } finally {
            grantInsert("audit_event");
        }
        assertPermissionFailure(failure, "audit_event");
        assertEquals(runs, ownerCount("SELECT COUNT(*) FROM execution_run WHERE project_id=?", prepared.fixture().project()));
        assertEquals(manifests, ownerCount("SELECT COUNT(*) FROM execution_manifest WHERE project_id=?", prepared.fixture().project()));
        assertEquals(manifestSteps, ownerCount("SELECT COUNT(*) FROM execution_manifest_step WHERE project_id=?", prepared.fixture().project()));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(outbox, ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(claims, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project()));
        assertEquals("RUNNING", executions.createRun(prepared.fixture().member(), prepared.fixture().project(),
                prepared.instance().id(), "m09-k08-audit-run-recovered").run().status());
    }

    /** K08: audit failure during a step write leaves the step and attempt untouched. */
    @Test
    void auditInsertFailureRollsBackStepSave() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-audit-step", "m09-k08-audit-step-source");
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project());
        int outbox = ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project());
        revokeInsert("audit_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                    prepared.run().run().id(), attempt, step, "must roll back", "PASS", 1, "m09-k08-audit-step-key"));
        } finally {
            grantInsert("audit_event");
        }
        assertPermissionFailure(failure, "audit_event");
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_step WHERE attempt_id=? AND actual_result='' AND conclusion='NOT_RUN'", attempt));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE id=? AND row_version=1", attempt));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(outbox, ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals("recovered", executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                prepared.run().run().id(), attempt, step, "recovered", "PASS", 1, "m09-k08-audit-step-recovered")
                .steps().get(0).actualResult());
    }

    /** K08: audit failure during finish leaves the running attempt and current result unchanged. */
    @Test
    void auditInsertFailureRollsBackFinish() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-audit-finish", "m09-k08-audit-finish-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        ExecutionService.AttemptView saved = executions.saveStep(actor, project, run, attempt,
                prepared.run().currentAttempt().steps().get(0).stepKey(), "done", "PASS", 1, "m09-k08-audit-finish-step");
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project);
        int outbox = ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", project);
        int claims = ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", project);
        revokeInsert("audit_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.finish(actor, project, run, attempt, saved.rowVersion(), "m09-k08-audit-finish-key"));
        } finally {
            grantInsert("audit_event");
        }
        assertPermissionFailure(failure, "audit_event");
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE id=? AND status='RUNNING' AND row_version=2", attempt));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_run WHERE id=? AND status='RUNNING'", run));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project));
        assertEquals(outbox, ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", project));
        assertEquals(claims, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", project));
        assertEquals("PASS", executions.finish(actor, project, run, attempt, saved.rowVersion(), "m09-k08-audit-finish-recovered").conclusion());
    }

    /** K08: Outbox failure during run creation rolls back the source snapshot and run. */
    @Test
    void outboxInsertFailureRollsBackRunCreation() throws Exception {
        PreparedRun prepared = preparedSource("m09-k08-outbox-run", "m09-k08-outbox-run-source");
        int runs = ownerCount("SELECT COUNT(*) FROM execution_run WHERE project_id=?", prepared.fixture().project());
        int manifests = ownerCount("SELECT COUNT(*) FROM execution_manifest WHERE project_id=?", prepared.fixture().project());
        int manifestSteps = ownerCount("SELECT COUNT(*) FROM execution_manifest_step WHERE project_id=?", prepared.fixture().project());
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project());
        int claims = ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project());
        revokeInsert("execution_outbox_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.createRun(prepared.fixture().member(), prepared.fixture().project(),
                    prepared.instance().id(), "m09-k08-outbox-run-key"));
        } finally {
            grantInsert("execution_outbox_event");
        }
        assertPermissionFailure(failure, "execution_outbox_event");
        assertEquals(runs, ownerCount("SELECT COUNT(*) FROM execution_run WHERE project_id=?", prepared.fixture().project()));
        assertEquals(manifests, ownerCount("SELECT COUNT(*) FROM execution_manifest WHERE project_id=?", prepared.fixture().project()));
        assertEquals(manifestSteps, ownerCount("SELECT COUNT(*) FROM execution_manifest_step WHERE project_id=?", prepared.fixture().project()));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(claims, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project()));
        assertEquals("RUNNING", executions.createRun(prepared.fixture().member(), prepared.fixture().project(),
                prepared.instance().id(), "m09-k08-outbox-run-recovered").run().status());
    }

    /** K08: Outbox failure during finish rolls back both terminal state updates. */
    @Test
    void outboxInsertFailureRollsBackFinish() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-outbox-finish", "m09-k08-outbox-finish-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        ExecutionService.AttemptView saved = executions.saveStep(actor, project, run, attempt,
                prepared.run().currentAttempt().steps().get(0).stepKey(), "done", "PASS", 1, "m09-k08-outbox-finish-step");
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project);
        int outbox = ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", project);
        int claims = ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", project);
        revokeInsert("execution_outbox_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.finish(actor, project, run, attempt, saved.rowVersion(), "m09-k08-outbox-finish-key"));
        } finally {
            grantInsert("execution_outbox_event");
        }
        assertPermissionFailure(failure, "execution_outbox_event");
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE id=? AND status='RUNNING' AND row_version=2", attempt));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_run WHERE id=? AND status='RUNNING'", run));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", project));
        assertEquals(outbox, ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", project));
        assertEquals(claims, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", project));
        assertEquals("PASS", executions.finish(actor, project, run, attempt, saved.rowVersion(), "m09-k08-outbox-finish-recovered").conclusion());
    }

    /** K08: Outbox failure after the step update rolls back the complete command. */
    @Test
    void outboxInsertFailureRollsBackStepAndAttemptVersion() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-outbox", "m09-k08-outbox-source");
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        int events = ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project());
        int outbox = ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project());
        int claims = ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project());
        revokeInsert("execution_outbox_event");
        Throwable failure;
        try {
            failure = assertThrows(Throwable.class, () -> executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                    prepared.run().run().id(), attempt, step, "must roll back", "PASS", 1, "m09-k08-outbox-step"));
        } finally {
            grantInsert("execution_outbox_event");
        }
        assertPermissionFailure(failure, "execution_outbox_event");
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_step WHERE attempt_id=? AND actual_result='' AND conclusion='NOT_RUN'", attempt));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE id=? AND row_version=1", attempt));
        assertEquals(events, ownerCount("SELECT COUNT(*) FROM execution_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(outbox, ownerCount("SELECT COUNT(*) FROM execution_outbox_event WHERE project_id=?", prepared.fixture().project()));
        assertEquals(claims, ownerCount("SELECT COUNT(*) FROM execution_idempotency WHERE project_id=?", prepared.fixture().project()));
        assertEquals("recovered", executions.saveStep(prepared.fixture().member(), prepared.fixture().project(),
                prepared.run().run().id(), attempt, step, "recovered", "PASS", 1, "m09-k08-outbox-step-recovered")
                .steps().get(0).actualResult());
    }

    /** K08: pause response remains PAUSED when the same key is replayed after resume. */
    @Test
    void pauseReplayReturnsTheOriginalFrozenResponseAfterResume() throws Exception {
        PreparedRun prepared = preparedRun("m09-k08-pause", "m09-k08-pause-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        ExecutionService.AttemptView saved = executions.saveStep(actor, project, run, attempt,
                prepared.run().currentAttempt().steps().get(0).stepKey(), "done", "PASS", 1, "m09-k08-pause-step");
        String pauseKey = "m09-k08-pause-key";
        ExecutionService.AttemptView paused = executions.pause(actor, project, run, attempt, saved.rowVersion(), pauseKey);
        ExecutionService.AttemptView running = executions.resume(actor, project, run, attempt, paused.rowVersion(), "m09-k08-resume-key");
        assertEquals("RUNNING", running.status());
        ExecutionService.AttemptView replay = executions.pause(actor, project, run, attempt, saved.rowVersion(), pauseKey);
        assertEquals(paused.id(), replay.id());
        assertEquals("PAUSED", replay.status());
        assertEquals(paused.rowVersion(), replay.rowVersion());
        assertEquals("RUNNING", executions.attempt(actor, project, run, attempt).status());
    }

    /** K02/K05: the restricted runtime cannot mutate a sealed manifest or a finished attempt. */
    @Test
    void runtimeCannotMutateSealedManifestOrFinishedAttempt() throws Exception {
        PreparedRun prepared = preparedRun("m09-k02-integrity", "m09-k02-integrity-source");
        UUID actor = prepared.fixture().member();
        UUID project = prepared.fixture().project();
        UUID run = prepared.run().run().id();
        UUID attempt = prepared.run().currentAttempt().id();
        UUID step = prepared.run().currentAttempt().steps().get(0).stepKey();
        ExecutionService.AttemptView saved = executions.saveStep(actor, project, run, attempt, step, "done", "PASS", 1, "m09-k02-step");
        executions.finish(actor, project, run, attempt, saved.rowVersion(), "m09-k02-finish");
        UUID manifest = prepared.run().manifest().id();

        try (Connection runtime = runtimeConnection(prepared.fixture())) {
            SQLException sealedInsert = assertThrows(SQLException.class, () -> execute(runtime,
                    "INSERT INTO execution_manifest_step (tenant_id, project_id, manifest_id, step_key, ordinal, action, expected) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    prepared.fixture().tenant(), project, manifest, UUID.randomUUID(), 2, "tampered", "tampered"));
            assertEquals("55006", sealedInsert.getSQLState());
            runtime.rollback();

            SQLException terminalStep = assertThrows(SQLException.class, () -> execute(runtime,
                    "UPDATE run_step SET actual_result=?, row_version=row_version+1 WHERE tenant_id=? AND project_id=? AND attempt_id=? AND step_key=?",
                    "tampered", prepared.fixture().tenant(), project, attempt, step));
            assertEquals("55006", terminalStep.getSQLState());
            runtime.rollback();

            SQLException terminalAttempt = assertThrows(SQLException.class, () -> execute(runtime,
                    "UPDATE run_attempt SET status='RUNNING', row_version=row_version+1 WHERE tenant_id=? AND project_id=? AND id=?",
                    prepared.fixture().tenant(), project, attempt));
            assertEquals("55006", terminalAttempt.getSQLState());
            runtime.rollback();
        }
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM execution_manifest_step WHERE manifest_id=?", manifest));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_step WHERE attempt_id=? AND actual_result='done'", attempt));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM run_attempt WHERE id=? AND status='FINISHED'", attempt));
    }

    private PreparedRun preparedSource(String setKey, String sourceKey) throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "M09 source", "description", "setup",
                        List.of(new TestCaseService.StepCommand(null, 1, "Open page", "Page is visible"))), sourceKey);
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "M09 set", "", setKey + "-set");
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), setKey + "-instance");
        return new PreparedRun(f, source, set, instance, null);
    }

    private PreparedRun preparedRun(String runKey, String sourceKey) throws Exception {
        PreparedRun source = preparedSource(runKey, sourceKey);
        ExecutionService.RunDetail run = executions.createRun(source.fixture().member(), source.fixture().project(),
                source.instance().id(), runKey + "-run");
        return new PreparedRun(source.fixture(), source.source(), source.set(), source.instance(), run);
    }

    private <T> List<Outcome<T>> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Outcome<T>> a = executor.submit(() -> callAfterBarrier(barrier, first));
            Future<Outcome<T>> b = executor.submit(() -> callAfterBarrier(barrier, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static <T> Outcome<T> callAfterBarrier(CyclicBarrier barrier, Callable<T> call) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return new Outcome<>(call.call(), null);
        } catch (Throwable failure) {
            return new Outcome<>(null, failure);
        }
    }

    private int ownerCount(String sql, Object... values) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("count query returned no row");
                return rows.getInt(1);
            }
        }
    }

    private static Connection runtimeConnection(Fixture fixture) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        setConfig(connection, "test365alm.tenant_id", fixture.tenant().toString());
        setConfig(connection, "test365alm.project_id", fixture.project().toString());
        setConfig(connection, "test365alm.principal_id", fixture.member().toString());
        setConfig(connection, "test365alm.execution_building", "false");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void setConfig(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeQuery().close();
        }
    }

    private static void revokeInsert(String table) throws Exception {
        if (!List.of("audit_event", "execution_outbox_event").contains(table)) throw new IllegalArgumentException(table);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = c.createStatement()) {
            statement.execute("REVOKE INSERT ON " + table + " FROM " + RUNTIME_USER);
        }
    }

    private static void grantInsert(String table) throws Exception {
        if (!List.of("audit_event", "execution_outbox_event").contains(table)) throw new IllegalArgumentException(table);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = c.createStatement()) {
            statement.execute("GRANT INSERT ON " + table + " TO " + RUNTIME_USER);
        }
    }

    private static void assertPermissionFailure(Throwable failure, String table) {
        Throwable current = failure;
        boolean permission = false;
        boolean tableMentioned = false;
        while (current != null) {
            if (current instanceof SQLException sql && "42501".equals(sql.getSQLState())) permission = true;
            String message = current.getMessage();
            if (message != null) {
                if (message.toLowerCase().contains("permission denied")) permission = true;
                if (message.contains(table)) tableMentioned = true;
            }
            current = current.getCause();
        }
        assertTrue(permission, "failure must come from the injected privilege denial: " + failure);
        assertTrue(tableMentioned, "failure must identify injected table " + table + ": " + failure);
    }

    private Fixture fixture() throws Exception {
        UUID tenant = UUID.randomUUID(), domain = UUID.randomUUID(), project = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(c, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)", member, "https://m09.example.invalid", member.toString(), "M09 member");
            execute(c, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", tenant, "m09-"+tenant.toString().substring(0, 8), "M09 tenant", member);
            execute(c, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, tenant, "Default");
            execute(c, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['MEMBER']::text[])", tenant, member);
            execute(c, "INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)", project, tenant, domain, "m09-"+project.toString().substring(0, 8), "M09 project", member);
            execute(c, "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ARRAY['PROJECT_MEMBER']::text[])", tenant, project, member);
        }
        return new Fixture(tenant, project, member);
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private record Fixture(UUID tenant, UUID project, UUID member) { }
    private record PreparedRun(Fixture fixture, TestCaseService.TestCaseView source,
            ExecutionService.TestSetView set, ExecutionService.InstanceView instance,
            ExecutionService.RunDetail run) { }
    private record Outcome<T>(T value, Throwable error) {
        boolean succeeded() { return error == null; }
    }
}

