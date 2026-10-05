package com.test365alm.server.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/**
 * M09's first executable slice.  Execution rows are owned by this service and
 * never update a test-case or revision.  A manifest is written in the same
 * transaction as the run, its first attempt and the initial NOT_RUN rows.
 */
@Service
public class ExecutionService {
    /** Canonical snapshot encoding is length-prefixed and unambiguous. */
    static final String FORMAT_VERSION = "m09-manifest-2";
    static final String RULES_VERSION = "m09-manual-results-1";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    public ExecutionService(JdbcTemplate jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public TestSetView createSet(UUID actor, UUID projectId, String name, String description, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        String safeName = required(name, "name", 200);
        String safeDescription = optional(description, "description", 100_000);
        setContext(project, actor);
        String hash = hash("set", safeName, safeDescription);
        Claim replay = claim(project, actor, "set:create", key, hash);
        if (replay != null) return decode(replay.responseJson(), TestSetView.class);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_set (tenant_id, project_id, id, name, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, project.tenantId(), projectId, id, safeName, safeDescription, actor);
        appendEvent(project, actor, null, null, "TEST_SET_CREATED", "test_set", id, "{}");
        TestSetView result = set(actor, projectId, id);
        saveClaim(project, actor, "set:create", key, hash, id, "TEST_SET", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<TestSetView> listSets(UUID actor, UUID projectId) {
        return listSets(actor, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<TestSetView> listSets(UUID actor, UUID projectId, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        return jdbc.query("""
                SELECT id, project_id, name, description, row_version, created_at
                FROM test_set WHERE tenant_id = ? AND project_id = ? ORDER BY created_at, id LIMIT ?
                """, ExecutionService::mapSet, project.tenantId(), projectId, boundedLimit(requestedLimit));
    }

    /** Keyset page for callers that need to continue beyond the bounded list. */
    @Transactional(readOnly = true)
    public TestSetPage pageSets(UUID actor, UUID projectId, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        int limit = boundedLimit(requestedLimit);
        CursorValue after = cursorValue(cursor, "test-set");
        List<TestSetView> fetched = after == null
                ? jdbc.query("""
                    SELECT id, project_id, name, description, row_version, created_at
                    FROM test_set WHERE tenant_id=? AND project_id=?
                    ORDER BY created_at, id LIMIT ?
                    """, ExecutionService::mapSet, project.tenantId(), projectId, limit + 1)
                : jdbc.query("""
                    SELECT id, project_id, name, description, row_version, created_at
                    FROM test_set WHERE tenant_id=? AND project_id=?
                      AND (created_at, id) > (?, ?)
                    ORDER BY created_at, id LIMIT ?
                    """, ExecutionService::mapSet, project.tenantId(), projectId,
                    after.time(), after.id(), limit + 1);
        boolean more = fetched.size() > limit;
        List<TestSetView> items = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursorFor(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()) : null;
        return new TestSetPage(items, next);
    }

    @Transactional(readOnly = true)
    public TestSetDetail getSet(UUID actor, UUID projectId, UUID setId) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        TestSetView value = set(actor, projectId, setId);
        List<InstanceView> instances = jdbc.query("""
                SELECT i.id, i.project_id, i.test_set_id, i.test_case_id, i.test_revision_id,
                       r.revision_no, r.title, i.display_order, i.created_at
                FROM test_instance i JOIN test_revision r
                  ON r.tenant_id=i.tenant_id AND r.project_id=i.project_id
                 AND r.test_case_id=i.test_case_id AND r.id=i.test_revision_id
                WHERE i.tenant_id=? AND i.project_id=? AND i.test_set_id=?
                ORDER BY i.display_order, i.id
                """, ExecutionService::mapInstance, project.tenantId(), projectId, setId);
        return new TestSetDetail(value, instances);
    }

    @Transactional
    public InstanceView addInstance(UUID actor, UUID projectId, UUID setId, UUID testCaseId,
            UUID revisionId, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        String hash = hash("instance", setId.toString(), testCaseId.toString(), revisionId.toString());
        Claim replay = claim(project, actor, "instance:create:" + setId, key, hash);
        if (replay != null) return decode(replay.responseJson(), InstanceView.class);
        if (count("SELECT COUNT(*) FROM test_set WHERE tenant_id=? AND project_id=? AND id=?",
                project.tenantId(), projectId, setId) == 0) throw ProjectAccessException.notFound();
        List<SourceRevision> source = jdbc.query("""
                SELECT r.id, r.test_case_id, r.revision_no, r.title, r.description, r.preconditions, r.sealed_at
                FROM test_revision r JOIN test_case c ON c.tenant_id=r.tenant_id AND c.project_id=r.project_id AND c.id=r.test_case_id
                WHERE r.tenant_id=? AND r.project_id=? AND r.test_case_id=? AND r.id=?
                """, (rs, row) -> new SourceRevision(rs.getObject("id", UUID.class), rs.getObject("test_case_id", UUID.class),
                        rs.getLong("revision_no"), rs.getString("title"), rs.getString("description"),
                        rs.getString("preconditions"), rs.getObject("sealed_at", OffsetDateTime.class)),
                project.tenantId(), projectId, testCaseId, revisionId);
        if (source.isEmpty()) throw ProjectAccessException.notFound();
        SourceRevision revision = source.get(0);
        if (revision.sealedAt() == null) throw ProjectAccessException.conflict("REVISION_NOT_SEALED", "The test revision is not sealed");
        int order = jdbc.queryForObject("SELECT COALESCE(MAX(display_order),0)+1 FROM test_instance WHERE tenant_id=? AND project_id=? AND test_set_id=?",
                Integer.class, project.tenantId(), projectId, setId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO test_instance (tenant_id, project_id, id, test_set_id, test_case_id, test_revision_id, display_order, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, project.tenantId(), projectId, id, setId, testCaseId, revisionId, order, actor);
        } catch (DataIntegrityViolationException ex) {
            throw ProjectAccessException.conflict("INSTANCE_ALREADY_EXISTS", "This revision is already in the test set");
        }
        appendEvent(project, actor, null, null, "TEST_INSTANCE_ADDED", "test_instance", id, "{}");
        InstanceView result = instance(actor, projectId, id);
        saveClaim(project, actor, "instance:create:" + setId, key, hash, id, "TEST_INSTANCE", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<InstanceView> listInstances(UUID actor, UUID projectId, UUID setId) {
        return listInstances(actor, projectId, setId, null);
    }

    @Transactional(readOnly = true)
    public List<InstanceView> listInstances(UUID actor, UUID projectId, UUID setId, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        return jdbc.query("""
                SELECT i.id, i.project_id, i.test_set_id, i.test_case_id, i.test_revision_id, r.revision_no,
                       r.title, i.display_order, i.created_at
                FROM test_instance i JOIN test_revision r ON r.tenant_id=i.tenant_id AND r.project_id=i.project_id
                 AND r.test_case_id=i.test_case_id AND r.id=i.test_revision_id
                WHERE i.tenant_id=? AND i.project_id=? AND i.test_set_id=? ORDER BY i.display_order, i.id LIMIT ?
                """, ExecutionService::mapInstance, project.tenantId(), projectId, setId, boundedLimit(requestedLimit));
    }

    @Transactional(readOnly = true)
    public InstancePage pageInstances(UUID actor, UUID projectId, UUID setId, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        int limit = boundedLimit(requestedLimit);
        CursorValue after = cursorValueForInstance(cursor);
        String sql = """
                SELECT i.id, i.project_id, i.test_set_id, i.test_case_id, i.test_revision_id, r.revision_no,
                       r.title, i.display_order, i.created_at
                FROM test_instance i JOIN test_revision r ON r.tenant_id=i.tenant_id AND r.project_id=i.project_id
                 AND r.test_case_id=i.test_case_id AND r.id=i.test_revision_id
                WHERE i.tenant_id=? AND i.project_id=? AND i.test_set_id=?
                """;
        List<InstanceView> fetched;
        if (after == null) {
            fetched = jdbc.query(sql + " ORDER BY i.display_order, i.id LIMIT ?", ExecutionService::mapInstance,
                    project.tenantId(), projectId, setId, limit + 1);
        } else {
            fetched = jdbc.query(sql + " AND (i.display_order, i.id) > (?, ?) ORDER BY i.display_order, i.id LIMIT ?",
                    ExecutionService::mapInstance, project.tenantId(), projectId, setId,
                    after.order(), after.id(), limit + 1);
        }
        boolean more = fetched.size() > limit;
        List<InstanceView> items = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursorFor(items.get(items.size() - 1).displayOrder(), items.get(items.size() - 1).id()) : null;
        return new InstancePage(items, next);
    }

    @Transactional
    public RunDetail createRun(UUID actor, UUID projectId, UUID instanceId, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        setExecutionBuilding(true);
        String hash = hash("run", instanceId.toString());
        Claim replay = claim(project, actor, "run:create", key, hash);
        if (replay != null) return decode(replay.responseJson(), RunDetail.class);
        InstanceSnapshot instance = instanceSnapshot(project, projectId, instanceId);
        List<StepSnapshot> steps = sourceSteps(project, projectId, instance.testCaseId(), instance.revisionId());
        if (steps.isEmpty()) throw ProjectAccessException.conflict("NO_EXECUTABLE_STEPS", "A zero-step revision cannot be run");
        UUID manifestId = UUID.randomUUID();
        String snapshotHash = manifestHash(instance, steps);
        jdbc.update("""
                INSERT INTO execution_manifest (tenant_id, project_id, id, test_instance_id, source_test_case_id,
                    source_revision_id, source_revision_no, title, description, preconditions, format_version, rules_version, snapshot_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, project.tenantId(), projectId, manifestId, instance.id(), instance.testCaseId(), instance.revisionId(),
                instance.revisionNo(), instance.title(), instance.description(), instance.preconditions(), FORMAT_VERSION, RULES_VERSION, snapshotHash);
        for (StepSnapshot step : steps) {
            jdbc.update("""
                    INSERT INTO execution_manifest_step (tenant_id, project_id, manifest_id, step_key, ordinal, action, expected)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, project.tenantId(), projectId, manifestId, step.stepKey(), step.ordinal(), step.action(), step.expected());
        }
        // The trigger in V14 permits step INSERTs only while the manifest is
        // being assembled. Once complete, even the schema owner cannot append
        // or alter a historical snapshot.
        jdbc.update("UPDATE execution_manifest SET build_complete=TRUE WHERE tenant_id=? AND project_id=? AND id=?",
                project.tenantId(), projectId, manifestId);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO execution_run (tenant_id, project_id, id, test_instance_id, manifest_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, project.tenantId(), projectId, runId, instance.id(), manifestId, actor);
        UUID attemptId = createAttemptRows(project, actor, runId, manifestId, steps, 1);
        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("runId", runId);
        startPayload.put("attemptId", attemptId);
        startPayload.put("afterStatus", "RUNNING");
        startPayload.put("afterVersion", 1);
        startPayload.put("actor", actor);
        appendEvent(project, actor, runId, attemptId, "RUN_STARTED", null, null, serialize(startPayload));
        RunDetail result = run(actor, projectId, runId);
        saveClaim(project, actor, "run:create", key, hash, runId, "RUN", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns(UUID actor, UUID projectId) {
        return listRuns(actor, projectId, null);
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns(UUID actor, UUID projectId, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        return jdbc.query("""
                SELECT id, project_id, test_instance_id, manifest_id, status, row_version, created_at
                FROM execution_run WHERE tenant_id=? AND project_id=? ORDER BY created_at DESC, id LIMIT ?
                """, ExecutionService::mapRun, project.tenantId(), projectId, boundedLimit(requestedLimit));
    }

    @Transactional(readOnly = true)
    public RunPage pageRuns(UUID actor, UUID projectId, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        int limit = boundedLimit(requestedLimit);
        CursorValue before = cursorValue(cursor, "run");
        String sql = """
                SELECT id, project_id, test_instance_id, manifest_id, status, row_version, created_at
                FROM execution_run WHERE tenant_id=? AND project_id=?
                """;
        List<RunView> fetched;
        if (before == null) {
            fetched = jdbc.query(sql + " ORDER BY created_at DESC, id DESC LIMIT ?", ExecutionService::mapRun,
                    project.tenantId(), projectId, limit + 1);
        } else {
            fetched = jdbc.query(sql + " AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?",
                    ExecutionService::mapRun, project.tenantId(), projectId,
                    before.time(), before.id(), limit + 1);
        }
        boolean more = fetched.size() > limit;
        List<RunView> items = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursorFor(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()) : null;
        return new RunPage(items, next);
    }

    @Transactional(readOnly = true)
    public RunDetail run(UUID actor, UUID projectId, UUID runId) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        List<RunView> values = jdbc.query("""
                SELECT id, project_id, test_instance_id, manifest_id, status, row_version, created_at
                FROM execution_run WHERE tenant_id=? AND project_id=? AND id=?
                """, ExecutionService::mapRun, project.tenantId(), projectId, runId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        ManifestView manifest = manifest(project, projectId, values.get(0).manifestId());
        List<AttemptSummary> summaries = jdbc.query("""
                SELECT id, attempt_no, status, conclusion, row_version
                FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? ORDER BY attempt_no
                """, (rs, row) -> new AttemptSummary(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                        rs.getString("status"), rs.getString("conclusion"), rs.getLong("row_version")),
                project.tenantId(), projectId, runId);
        AttemptView current = summaries.isEmpty() ? null : attempt(actor, projectId, runId,
                summaries.get(summaries.size() - 1).id());
        return new RunDetail(values.get(0), manifest, current, summaries);
    }

    @Transactional(readOnly = true)
    public List<AttemptView> attempts(UUID actor, UUID projectId, UUID runId) {
        return attempts(actor, projectId, runId, null);
    }

    @Transactional(readOnly = true)
    public List<AttemptView> attempts(UUID actor, UUID projectId, UUID runId, Integer requestedLimit) {
        return pageAttempts(actor, projectId, runId, null, requestedLimit).items();
    }

    @Transactional(readOnly = true)
    public AttemptPage pageAttempts(UUID actor, UUID projectId, UUID runId, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        int limit = boundedLimit(requestedLimit);
        Integer after = attemptCursor(cursor);
        String sql = """
                SELECT id, run_id, attempt_no, status, conclusion, row_version, started_by, started_at, finished_at
                FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=?
                """;
        List<AttemptView> fetched;
        if (after == null) {
            fetched = jdbc.query(sql + " ORDER BY attempt_no LIMIT ?", (rs, row) -> mapAttempt(rs, steps(project, projectId, rs.getObject("id", UUID.class))),
                    project.tenantId(), projectId, runId, limit + 1);
        } else {
            fetched = jdbc.query(sql + " AND attempt_no > ? ORDER BY attempt_no LIMIT ?",
                    (rs, row) -> mapAttempt(rs, steps(project, projectId, rs.getObject("id", UUID.class))),
                    project.tenantId(), projectId, runId, after, limit + 1);
        }
        boolean more = fetched.size() > limit;
        List<AttemptView> items = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursorFor(items.get(items.size() - 1).attemptNo()) : null;
        return new AttemptPage(items, next);
    }

    @Transactional(readOnly = true)
    public RunSummary summary(UUID actor, UUID projectId) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        long total = countLong("SELECT COUNT(*) FROM test_instance WHERE tenant_id=? AND project_id=?", project.tenantId(), projectId);
        long unrun = countLong("""
                SELECT COUNT(*) FROM test_instance i WHERE i.tenant_id=? AND i.project_id=?
                  AND NOT EXISTS (SELECT 1 FROM execution_run r WHERE r.tenant_id=i.tenant_id AND r.project_id=i.project_id AND r.test_instance_id=i.id)
                """, project.tenantId(), projectId);
        long active = countLong("SELECT COUNT(*) FROM run_attempt WHERE tenant_id=? AND project_id=? AND status <> 'FINISHED'", project.tenantId(), projectId);
        Map<String, Object> latest = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE conclusion='PASS') AS pass,
                       COUNT(*) FILTER (WHERE conclusion='FAIL') AS fail,
                       COUNT(*) FILTER (WHERE conclusion='BLOCKED') AS blocked
                FROM (SELECT DISTINCT ON (r.test_instance_id) a.conclusion
                      FROM execution_run r JOIN run_attempt a ON a.tenant_id=r.tenant_id AND a.project_id=r.project_id AND a.run_id=r.id
                      WHERE r.tenant_id=? AND r.project_id=? AND a.status='FINISHED'
                      ORDER BY r.test_instance_id, a.finished_at DESC NULLS LAST, a.attempt_no DESC) latest
                """, project.tenantId(), projectId);
        return new RunSummary(total, unrun, active, ((Number) latest.get("pass")).longValue(),
                ((Number) latest.get("fail")).longValue(), ((Number) latest.get("blocked")).longValue());
    }

    @Transactional(readOnly = true)
    public AttemptView attempt(UUID actor, UUID projectId, UUID runId, UUID attemptId) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        List<AttemptView> values = jdbc.query("""
                SELECT a.id, a.run_id, a.attempt_no, a.status, a.conclusion, a.row_version, a.started_by, a.started_at, a.finished_at
                FROM run_attempt a WHERE a.tenant_id=? AND a.project_id=? AND a.run_id=? AND a.id=?
                """, (rs, row) -> mapAttempt(rs, steps(project, projectId, attemptId)), project.tenantId(), projectId, runId, attemptId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    @Transactional
    public AttemptView saveStep(UUID actor, UUID projectId, UUID runId, UUID attemptId, UUID stepKey,
            String actualResult, String conclusion, long expectedVersion, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        String normalizedConclusion = normalizeConclusion(conclusion);
        String actual = optional(actualResult, "actualResult", 100_000);
        if (("FAIL".equals(normalizedConclusion) || "BLOCKED".equals(normalizedConclusion)) && actual.isBlank()) {
            throw ProjectAccessException.invalid("actualResult is required for FAIL or BLOCKED");
        }
        String hash = hash("step", runId.toString(), attemptId.toString(), stepKey.toString(), actual, normalizedConclusion, Long.toString(expectedVersion));
        Claim replay = claim(project, actor, "step:" + attemptId + ":" + stepKey, key, hash);
        if (replay != null) return decode(replay.responseJson(), AttemptView.class);
        lockAttempt(project, projectId, runId, attemptId);
        StepState before = jdbc.query("SELECT actual_result, conclusion, row_version FROM run_step WHERE tenant_id=? AND project_id=? AND attempt_id=? AND step_key=?",
                (rs, row) -> new StepState(rs.getString("actual_result"), rs.getString("conclusion"), rs.getLong("row_version")),
                project.tenantId(), projectId, attemptId, stepKey).stream().findFirst()
                .orElseThrow(ProjectAccessException::notFound);
        int updated = jdbc.update("""
                UPDATE run_step SET actual_result=?, conclusion=?, row_version=row_version+1, updated_by=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND project_id=? AND attempt_id=? AND step_key=? AND row_version=?
                  AND EXISTS (SELECT 1 FROM run_attempt a WHERE a.id=run_step.attempt_id AND a.status='RUNNING')
                """, actual, normalizedConclusion, actor, project.tenantId(), projectId, attemptId, stepKey, expectedVersion);
        if (updated != 1) throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Step is no longer editable or version is stale");
        jdbc.update("UPDATE run_attempt SET row_version=row_version+1 WHERE tenant_id=? AND project_id=? AND id=?", project.tenantId(), projectId, attemptId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("attemptId", attemptId);
        payload.put("stepKey", stepKey);
        payload.put("beforeActualResult", before.actualResult());
        payload.put("afterActualResult", actual);
        payload.put("beforeConclusion", before.conclusion());
        payload.put("afterConclusion", normalizedConclusion);
        payload.put("beforeVersion", before.rowVersion());
        payload.put("afterVersion", before.rowVersion() + 1);
        payload.put("actor", actor);
        appendEvent(project, actor, runId, attemptId, "STEP_RECORDED", null, null, serialize(payload));
        AttemptView result = attempt(actor, projectId, runId, attemptId);
        saveClaim(project, actor, "step:" + attemptId + ":" + stepKey, key, hash, attemptId, "ATTEMPT", result);
        return result;
    }

    @Transactional
    public AttemptView pause(UUID actor, UUID projectId, UUID runId, UUID attemptId, long expectedVersion, String key) {
        return transition(actor, projectId, runId, attemptId, expectedVersion, key, "PAUSE", "PAUSED");
    }

    @Transactional
    public AttemptView resume(UUID actor, UUID projectId, UUID runId, UUID attemptId, long expectedVersion, String key) {
        return transition(actor, projectId, runId, attemptId, expectedVersion, key, "RESUME", "RUNNING");
    }

    private AttemptView transition(UUID actor, UUID projectId, UUID runId, UUID attemptId, long expectedVersion,
            String key, String event, String status) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        String hash = hash(event, runId.toString(), attemptId.toString(), Long.toString(expectedVersion));
        Claim replay = claim(project, actor, event.toLowerCase()+":"+attemptId, key, hash);
        if (replay != null) return decode(replay.responseJson(), AttemptView.class);
        lockAttempt(project, projectId, runId, attemptId);
        AttemptState before = jdbc.queryForObject("SELECT status, row_version FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? AND id=?",
                (rs, row) -> new AttemptState(rs.getString("status"), rs.getLong("row_version")),
                project.tenantId(), projectId, runId, attemptId);
        int updated = jdbc.update("""
                UPDATE run_attempt SET status=?, row_version=row_version+1
                WHERE tenant_id=? AND project_id=? AND run_id=? AND id=? AND status=? AND row_version=?
                """, status, project.tenantId(), projectId, runId, attemptId, "PAUSE".equals(event) ? "RUNNING" : "PAUSED", expectedVersion);
        if (updated != 1) throw ProjectAccessException.conflict("INVALID_ATTEMPT_STATE", "Attempt is not in the expected state");
        jdbc.update("UPDATE execution_run SET status=?, row_version=row_version+1, updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",
                status, project.tenantId(), projectId, runId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("attemptId", attemptId);
        payload.put("beforeStatus", before.status());
        payload.put("afterStatus", status);
        payload.put("beforeVersion", before.rowVersion());
        payload.put("afterVersion", before.rowVersion() + 1);
        payload.put("actor", actor);
        appendEvent(project, actor, runId, attemptId, event + "D", null, null, serialize(payload));
        AttemptView result = attempt(actor, projectId, runId, attemptId);
        saveClaim(project, actor, event.toLowerCase()+":"+attemptId, key, hash, attemptId, "ATTEMPT", result);
        return result;
    }

    @Transactional
    public AttemptView finish(UUID actor, UUID projectId, UUID runId, UUID attemptId, long expectedVersion, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        String hash = hash("finish", runId.toString(), attemptId.toString(), Long.toString(expectedVersion));
        Claim replay = claim(project, actor, "finish:" + attemptId, key, hash);
        if (replay != null) return decode(replay.responseJson(), AttemptView.class);
        lockAttempt(project, projectId, runId, attemptId);
        AttemptState state = jdbc.queryForObject("SELECT status, row_version FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? AND id=?",
                (rs, row) -> new AttemptState(rs.getString("status"), rs.getLong("row_version")), project.tenantId(), projectId, runId, attemptId);
        if (!"RUNNING".equals(state.status())) throw ProjectAccessException.conflict("INVALID_ATTEMPT_STATE", "Only a running attempt can be finished");
        if (state.rowVersion() != expectedVersion) throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Attempt version is stale");
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM run_step WHERE tenant_id=? AND project_id=? AND attempt_id=? AND conclusion='NOT_RUN'",
                Integer.class, project.tenantId(), projectId, attemptId);
        if (remaining != null && remaining > 0) throw ProjectAccessException.conflict("STEPS_REMAIN", "Every step must have a conclusion before finishing");
        String conclusion = jdbc.queryForObject("""
                SELECT CASE WHEN COUNT(*) FILTER (WHERE conclusion='FAIL') > 0 THEN 'FAIL'
                    WHEN COUNT(*) FILTER (WHERE conclusion='BLOCKED') > 0 THEN 'BLOCKED' ELSE 'PASS' END
                FROM run_step WHERE tenant_id=? AND project_id=? AND attempt_id=?
                """, String.class, project.tenantId(), projectId, attemptId);
        jdbc.update("UPDATE run_attempt SET status='FINISHED', conclusion=?, row_version=row_version+1, finished_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=? AND row_version=?",
                conclusion, project.tenantId(), projectId, attemptId, expectedVersion);
        jdbc.update("UPDATE execution_run SET status='FINISHED', row_version=row_version+1, updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",
                project.tenantId(), projectId, runId);
        Map<String, Object> finishPayload = new LinkedHashMap<>();
        finishPayload.put("runId", runId);
        finishPayload.put("attemptId", attemptId);
        finishPayload.put("beforeStatus", state.status());
        finishPayload.put("afterStatus", "FINISHED");
        finishPayload.put("beforeVersion", state.rowVersion());
        finishPayload.put("afterVersion", state.rowVersion() + 1);
        finishPayload.put("conclusion", conclusion);
        finishPayload.put("actor", actor);
        appendEvent(project, actor, runId, attemptId, "RUN_FINISHED", null, null, serialize(finishPayload));
        AttemptView result = attempt(actor, projectId, runId, attemptId);
        saveClaim(project, actor, "finish:" + attemptId, key, hash, attemptId, "ATTEMPT", result);
        return result;
    }

    @Transactional
    public AttemptView rerun(UUID actor, UUID projectId, UUID runId, String key) {
        ProjectService.ProjectView project = writeProject(actor, projectId);
        requireKey(key);
        setContext(project, actor);
        setExecutionBuilding(true);
        String hash = hash("rerun", runId.toString());
        Claim replay = claim(project, actor, "rerun:" + runId, key, hash);
        if (replay != null) return decode(replay.responseJson(), AttemptView.class);
        // JdbcTemplate invokes a RowMapper with the result set already positioned
        // on a row.  Do not call ResultSet.next() from a callback here: doing so
        // skips the only visible row and makes every legitimate rerun look like
        // a missing run under the runtime RLS policy.
        List<UUID> runs = jdbc.query("SELECT id FROM execution_run WHERE tenant_id=? AND project_id=? AND id=? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), project.tenantId(), projectId, runId);
        if (runs.isEmpty()) throw ProjectAccessException.notFound();
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? AND status <> 'FINISHED'",
                Integer.class, project.tenantId(), projectId, runId);
        if (active != null && active > 0) throw ProjectAccessException.conflict("ACTIVE_ATTEMPT_EXISTS", "A run already has an active attempt");
        int next = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=?",
                Integer.class, project.tenantId(), projectId, runId);
        UUID manifestId = jdbc.queryForObject("SELECT manifest_id FROM execution_run WHERE tenant_id=? AND project_id=? AND id=?",
                UUID.class, project.tenantId(), projectId, runId);
        List<StepSnapshot> steps = jdbc.query("SELECT step_key, ordinal, action, expected FROM execution_manifest_step WHERE tenant_id=? AND project_id=? AND manifest_id=? ORDER BY ordinal",
                (rs, row) -> new StepSnapshot(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"), rs.getString("action"), rs.getString("expected")),
                project.tenantId(), projectId, manifestId);
        UUID attemptId = createAttemptRows(project, actor, runId, manifestId, steps, next);
        jdbc.update("UPDATE execution_run SET status='RUNNING', row_version=row_version+1, updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",
                project.tenantId(), projectId, runId);
        Map<String, Object> rerunPayload = new LinkedHashMap<>();
        rerunPayload.put("runId", runId);
        rerunPayload.put("attemptId", attemptId);
        rerunPayload.put("attemptNo", next);
        rerunPayload.put("actor", actor);
        appendEvent(project, actor, runId, attemptId, "RUN_RETRIED", null, null, serialize(rerunPayload));
        AttemptView result = attempt(actor, projectId, runId, attemptId);
        saveClaim(project, actor, "rerun:" + runId, key, hash, attemptId, "ATTEMPT", result);
        return result;
    }

    private UUID createAttemptRows(ProjectService.ProjectView project, UUID actor, UUID runId, UUID manifestId,
            List<StepSnapshot> steps, int no) {
        setExecutionBuilding(true);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_attempt (tenant_id, project_id, id, run_id, manifest_id, attempt_no, started_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                project.tenantId(), project.id(), id, runId, manifestId, no, actor);
        for (StepSnapshot step : steps) {
                jdbc.update("""
                    INSERT INTO run_step (tenant_id, project_id, attempt_id, manifest_id, step_key, ordinal, action, expected)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, project.tenantId(), project.id(), id, manifestId, step.stepKey(), step.ordinal(), step.action(), step.expected());
        }
        return id;
    }

    private InstanceSnapshot instanceSnapshot(ProjectService.ProjectView project, UUID projectId, UUID id) {
        List<InstanceSnapshot> values = jdbc.query("""
                SELECT i.id, i.test_case_id, i.test_revision_id, r.revision_no, r.title, r.description, r.preconditions
                FROM test_instance i JOIN test_revision r ON r.tenant_id=i.tenant_id AND r.project_id=i.project_id
                 AND r.test_case_id=i.test_case_id AND r.id=i.test_revision_id
                WHERE i.tenant_id=? AND i.project_id=? AND i.id=?
                """, (rs, row) -> new InstanceSnapshot(rs.getObject("id", UUID.class), rs.getObject("test_case_id", UUID.class),
                        rs.getObject("test_revision_id", UUID.class), rs.getLong("revision_no"), rs.getString("title"),
                        rs.getString("description"), rs.getString("preconditions")), project.tenantId(), projectId, id);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    private List<StepSnapshot> sourceSteps(ProjectService.ProjectView project, UUID projectId, UUID testCaseId, UUID revisionId) {
        return jdbc.query("SELECT step_key, ordinal, action, expected FROM test_step WHERE tenant_id=? AND project_id=? AND test_case_id=? AND revision_id=? ORDER BY ordinal",
                (rs, row) -> new StepSnapshot(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"), rs.getString("action"), rs.getString("expected")),
                project.tenantId(), projectId, testCaseId, revisionId);
    }

    private ManifestView manifest(ProjectService.ProjectView project, UUID projectId, UUID manifestId) {
        List<ManifestView> values = jdbc.query("""
                SELECT id, test_instance_id, source_test_case_id, source_revision_id, source_revision_no, title,
                       description, preconditions, format_version, rules_version, snapshot_hash, created_at
                FROM execution_manifest WHERE tenant_id=? AND project_id=? AND id=?
                """, (rs, row) -> new ManifestView(rs.getObject("id", UUID.class), rs.getObject("test_instance_id", UUID.class),
                        rs.getObject("source_test_case_id", UUID.class), rs.getObject("source_revision_id", UUID.class), rs.getLong("source_revision_no"),
                        rs.getString("title"), rs.getString("description"), rs.getString("preconditions"), rs.getString("format_version"),
                        rs.getString("rules_version"), rs.getString("snapshot_hash"), rs.getObject("created_at", OffsetDateTime.class),
                        List.of()), project.tenantId(), projectId, manifestId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        ManifestView m = values.get(0);
        List<StepSnapshot> steps = jdbc.query("SELECT step_key, ordinal, action, expected FROM execution_manifest_step WHERE tenant_id=? AND project_id=? AND manifest_id=? ORDER BY ordinal",
                (rs, row) -> new StepSnapshot(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"), rs.getString("action"), rs.getString("expected")),
                project.tenantId(), projectId, manifestId);
        return m.withSteps(steps);
    }

    private List<RunStepView> steps(ProjectService.ProjectView project, UUID projectId, UUID attemptId) {
        return jdbc.query("""
                SELECT step_key, ordinal, action, expected, actual_result, conclusion, row_version, updated_by, updated_at
                FROM run_step WHERE tenant_id=? AND project_id=? AND attempt_id=? ORDER BY ordinal
                """, (rs, row) -> new RunStepView(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"), rs.getString("action"),
                        rs.getString("expected"), rs.getString("actual_result"), rs.getString("conclusion"), rs.getLong("row_version"),
                        rs.getObject("updated_by", UUID.class), rs.getObject("updated_at", OffsetDateTime.class)), project.tenantId(), projectId, attemptId);
    }

    private AttemptView mapAttempt(ResultSet rs, List<RunStepView> steps) throws SQLException {
        return new AttemptView(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getInt("attempt_no"),
                rs.getString("status"), rs.getString("conclusion"), rs.getLong("row_version"), rs.getObject("started_by", UUID.class),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class), steps);
    }

    private static TestSetView mapSet(ResultSet rs, int row) throws SQLException {
        return new TestSetView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class));
    }
    private static InstanceView mapInstance(ResultSet rs, int row) throws SQLException {
        return new InstanceView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("test_set_id", UUID.class), rs.getObject("test_case_id", UUID.class),
                rs.getObject("test_revision_id", UUID.class), rs.getLong("revision_no"), rs.getLong("revision_no"), rs.getString("title"), rs.getInt("display_order"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
    private static RunView mapRun(ResultSet rs, int row) throws SQLException {
        return new RunView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("test_instance_id", UUID.class),
                rs.getObject("manifest_id", UUID.class), rs.getString("status"), rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private TestSetView set(UUID actor, UUID projectId, UUID id) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        List<TestSetView> values = jdbc.query("SELECT id, project_id, name, description, row_version, created_at FROM test_set WHERE tenant_id=? AND project_id=? AND id=?",
                ExecutionService::mapSet, project.tenantId(), projectId, id);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    private InstanceView instance(UUID actor, UUID projectId, UUID id) {
        ProjectService.ProjectView project = readProject(actor, projectId);
        setContext(project, actor);
        List<InstanceView> values = jdbc.query("""
                SELECT i.id, i.project_id, i.test_set_id, i.test_case_id, i.test_revision_id, r.revision_no, r.title, i.display_order, i.created_at
                FROM test_instance i JOIN test_revision r ON r.tenant_id=i.tenant_id AND r.project_id=i.project_id AND r.test_case_id=i.test_case_id AND r.id=i.test_revision_id
                WHERE i.tenant_id=? AND i.project_id=? AND i.id=?
                """, ExecutionService::mapInstance, project.tenantId(), projectId, id);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    private void lockAttempt(ProjectService.ProjectView project, UUID projectId, UUID runId, UUID attemptId) {
        List<UUID> found = jdbc.query("SELECT id FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? AND id=? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), project.tenantId(), projectId, runId, attemptId);
        if (found.isEmpty()) throw ProjectAccessException.notFound();
    }

    private Claim claim(ProjectService.ProjectView project, UUID actor, String route, String key, String hash) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, project.tenantId()+":"+project.id()+":"+actor+":"+route+":"+key);
        jdbc.update("DELETE FROM execution_idempotency WHERE tenant_id=? AND project_id=? AND principal_id=? AND route=? AND idempotency_key=? AND expires_at<=CURRENT_TIMESTAMP",
                project.tenantId(), project.id(), actor, route, key);
        List<Claim> values = jdbc.query("SELECT request_hash, object_id, response_json::text AS response_json FROM execution_idempotency WHERE tenant_id=? AND project_id=? AND principal_id=? AND route=? AND idempotency_key=?",
                (rs, row) -> new Claim(rs.getString("request_hash"), rs.getObject("object_id", UUID.class), rs.getString("response_json")), project.tenantId(), project.id(), actor, route, key);
        if (values.isEmpty()) return null;
        if (!hash.equals(values.get(0).hash())) throw ProjectAccessException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used for a different request");
        if (values.get(0).responseJson() == null) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_RESPONSE_UNAVAILABLE",
                    "The original idempotent response is no longer available; use a new key for a new intent");
        }
        return values.get(0);
    }
    private void saveClaim(ProjectService.ProjectView project, UUID actor, String route, String key, String hash, UUID id, String type) {
        saveClaim(project, actor, route, key, hash, id, type, null);
    }
    private void saveClaim(ProjectService.ProjectView project, UUID actor, String route, String key, String hash,
            UUID id, String type, Object response) {
        String json = response == null ? null : serialize(response);
        jdbc.update("INSERT INTO execution_idempotency (tenant_id, project_id, principal_id, route, idempotency_key, request_hash, object_id, object_type, response_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                project.tenantId(), project.id(), actor, route, key, hash, id, type, json);
    }
    private void appendEvent(ProjectService.ProjectView project, UUID actor, UUID runId, UUID attemptId, String type) {
        appendEvent(project, actor, runId, attemptId, type, null, null, "{}");
    }
    private void appendEvent(ProjectService.ProjectView project, UUID actor, UUID runId, UUID attemptId, String type,
            String objectType, UUID objectId, String payload) {
        jdbc.update("INSERT INTO execution_event (tenant_id, project_id, run_id, attempt_id, event_type, actor_principal_id, payload, object_type, object_id) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                project.tenantId(), project.id(), runId, attemptId, type, actor, payload, objectType, objectId);
        jdbc.update("INSERT INTO execution_outbox_event (tenant_id, project_id, run_id, attempt_id, event_type, payload, object_type, object_id) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                project.tenantId(), project.id(), runId, attemptId, type, payload, objectType, objectId);
        jdbc.update("""
                INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id, object_revision)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, project.tenantId(), project.id(), actor, type.toLowerCase().replace('_', '.'), objectType == null ? "run" : objectType,
                objectType == null ? runId : objectId);
    }

    private static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (RuntimeException ex) { throw new IllegalStateException("Could not persist idempotent response", ex); }
    }
    private static <T> T decode(String value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (RuntimeException ex) { throw new IllegalStateException("Could not restore idempotent response", ex); }
    }
    private ProjectService.ProjectView readProject(UUID actor, UUID id) { return projects.requireTestReadAccess(actor, id); }
    private ProjectService.ProjectView writeProject(UUID actor, UUID id) { return projects.requireTestWriteAccess(actor, id); }
    private void setContext(ProjectService.ProjectView project, UUID actor) {
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id', ?, true)", String.class, project.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id', ?, true)", String.class, actor.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.project_id', ?, true)", String.class, project.id().toString());
    }
    private void setExecutionBuilding(boolean enabled) {
        jdbc.queryForObject("SELECT set_config('test365alm.execution_building', ?, true)", String.class, Boolean.toString(enabled));
    }
    private int count(String sql, Object... args) { Integer value = jdbc.queryForObject(sql, Integer.class, args); return value == null ? 0 : value; }
    private long countLong(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }
    private static int boundedLimit(Integer value) {
        int limit = value == null ? 50 : value;
        if (limit < 1 || limit > 200) throw ProjectAccessException.invalid("limit must be between 1 and 200");
        return limit;
    }
    static String normalizeConclusion(String value) {
        String result = value == null ? "" : value.trim().toUpperCase();
        if (!List.of("NOT_RUN", "PASS", "FAIL", "BLOCKED").contains(result)) throw ProjectAccessException.invalid("Unsupported step conclusion");
        return result;
    }
    static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw ProjectAccessException.invalid(field+" must contain 1 to "+max+" characters");
        return value.trim();
    }
    static String optional(String value, String field, int max) {
        String result = value == null ? "" : value;
        if (result.length() > max) throw ProjectAccessException.invalid(field+" is too long");
        return result;
    }
    static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 128) throw ProjectAccessException.preconditionRequired("Idempotency-Key is required");
    }

    private static String cursorFor(OffsetDateTime time, UUID id) {
        return encodeCursor(time.toString() + "|" + id);
    }
    private static String cursorFor(int order) {
        return encodeCursor(Integer.toString(order));
    }
    private static String cursorFor(int order, UUID id) {
        return encodeCursor(order + "|" + id);
    }
    private static String encodeCursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static CursorValue cursorValue(String raw, String kind) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) throw new IllegalArgumentException();
            OffsetDateTime time = OffsetDateTime.parse(decoded.substring(0, separator));
            UUID id = UUID.fromString(decoded.substring(separator + 1));
            return new CursorValue(time, id, 0);
        } catch (RuntimeException ex) {
            throw ProjectAccessException.invalid("cursor is invalid for " + kind);
        }
    }
    private static CursorValue cursorValueForInstance(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) throw new IllegalArgumentException();
            int order = Integer.parseInt(decoded.substring(0, separator));
            UUID id = UUID.fromString(decoded.substring(separator + 1));
            if (order < 1) throw new IllegalArgumentException();
            return new CursorValue(null, id, order);
        } catch (RuntimeException ex) {
            throw ProjectAccessException.invalid("cursor is invalid for test-instance");
        }
    }
    private static Integer attemptCursor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int value = Integer.parseInt(decoded);
            if (value < 1) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ex) {
            throw ProjectAccessException.invalid("cursor is invalid for attempt");
        }
    }
    static String hash(String... values) {
        try {
            StringBuilder value = new StringBuilder();
            for (String part : values) value.append(part == null ? "N;" : "V"+part.length()+":"+part+";");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String manifestHash(InstanceSnapshot instance, List<StepSnapshot> steps) {
        List<String> fields = new ArrayList<>();
        fields.add(FORMAT_VERSION);
        fields.add(RULES_VERSION);
        fields.add(instance.testCaseId().toString());
        fields.add(instance.revisionId().toString());
        fields.add(Long.toString(instance.revisionNo()));
        fields.add(instance.title());
        fields.add(instance.description());
        fields.add(instance.preconditions());
        fields.add(Integer.toString(steps.size()));
        for (StepSnapshot step : steps) {
            fields.add(step.stepKey().toString());
            fields.add(Integer.toString(step.ordinal()));
            fields.add(step.action());
            fields.add(step.expected());
        }
        return hash(fields.toArray(String[]::new));
    }

    public record TestSetView(UUID id, UUID projectId, String name, String description, long rowVersion, OffsetDateTime createdAt) { }
    public record TestSetPage(List<TestSetView> items, String nextCursor) { }
    public record TestSetDetail(TestSetView testSet, List<InstanceView> instances) { }
    public record InstanceView(UUID id, UUID projectId, UUID testSetId, UUID testCaseId, UUID testRevisionId,
            long revisionNo, long revisionNumber, String title, int displayOrder, OffsetDateTime createdAt) { }
    public record InstancePage(List<InstanceView> items, String nextCursor) { }
    public record RunView(UUID id, UUID projectId, UUID testInstanceId, UUID manifestId, String status, long rowVersion, OffsetDateTime createdAt) { }
    public record RunPage(List<RunView> items, String nextCursor) { }
    public record ManifestView(UUID id, UUID testInstanceId, UUID sourceTestCaseId, UUID sourceRevisionId, long sourceRevisionNo,
            String title, String description, String preconditions, String formatVersion, String rulesVersion, String snapshotHash,
            OffsetDateTime createdAt, List<StepSnapshot> steps) {
        ManifestView withSteps(List<StepSnapshot> value) { return new ManifestView(id, testInstanceId, sourceTestCaseId, sourceRevisionId, sourceRevisionNo,
                title, description, preconditions, formatVersion, rulesVersion, snapshotHash, createdAt, value); }
    }
    public record RunDetail(RunView run, ManifestView manifest, AttemptView currentAttempt, List<AttemptSummary> attempts) { }
    public record AttemptSummary(UUID id, int attemptNo, String status, String conclusion, long rowVersion) { }
    public record AttemptView(UUID id, UUID runId, int attemptNo, String status, String conclusion, long rowVersion, UUID startedBy,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, List<RunStepView> steps) {
        AttemptSummary summary() { return new AttemptSummary(id, attemptNo, status, conclusion, rowVersion); }
    }
    public record AttemptPage(List<AttemptView> items, String nextCursor) { }
    public record RunSummary(long totalInstances, long unrunInstances, long activeAttempts,
            long latestCompletedPass, long latestCompletedFail, long latestCompletedBlocked) { }
    public record RunStepView(UUID stepKey, int ordinal, String action, String expected, String actualResult, String conclusion,
            long rowVersion, UUID updatedBy, OffsetDateTime updatedAt) { }
    public record StepSnapshot(UUID stepKey, int ordinal, String action, String expected) { }
    private record SourceRevision(UUID id, UUID testCaseId, long revisionNo, String title, String description, String preconditions, OffsetDateTime sealedAt) { }
    private record InstanceSnapshot(UUID id, UUID testCaseId, UUID revisionId, long revisionNo, String title, String description, String preconditions) { }
    private record Claim(String hash, UUID id, String responseJson) { }
    private record StepState(String actualResult, String conclusion, long rowVersion) { }
    private record AttemptState(String status, long rowVersion) { }
    private record CursorValue(OffsetDateTime time, UUID id, int order) { }
}
