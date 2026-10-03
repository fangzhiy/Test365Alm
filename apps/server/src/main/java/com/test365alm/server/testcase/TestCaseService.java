package com.test365alm.server.testcase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/**
 * First M08 slice: project-scoped MANUAL test cases with immutable revision
 * and step snapshots.  Runtime writes are always authorized before a
 * response, and all business rows, audit, outbox and idempotency state share
 * one transaction.
 */
@Service
public class TestCaseService {
    private static final String CREATE_ROUTE = "tests:create";
    private static final String REVISION_ROUTE = "tests:revision:";
    private static final int MAX_STEPS = 200;
    private static final Set<String> SUPPORTED_TYPES = Set.of("MANUAL");
    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    public TestCaseService(JdbcTemplate jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public TestCaseView create(UUID actor, UUID projectId, CreateCommand command, String idempotencyKey) {
        lockProjectTransaction(projectId);
        ProjectService.ProjectView project = authorizedProject(actor, projectId, true);
        setContext(project.tenantId(), actor, projectId);
        validateIdempotencyKey(idempotencyKey);
        CreateCommand normalized = normalizeCreate(command);
        String hash = requestHash(normalized.testType(), normalized.title(), normalized.description(),
                normalized.preconditions(), normalized.steps());
        IdempotencyRecord replay = claimOrReplay(project.tenantId(), projectId, actor, CREATE_ROUTE, idempotencyKey, hash);
        if (replay != null) return replayView(actor, projectId, replay);

        UUID testCaseId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        long displayNumber = allocateNumber(project.tenantId(), projectId);
        try {
            jdbc.update("""
                    INSERT INTO test_case
                        (tenant_id, project_id, id, display_number, test_type, row_version, created_by)
                    VALUES (?, ?, ?, ?, ?, 1, ?)
                    """, project.tenantId(), projectId, testCaseId, displayNumber,
                    normalized.testType(), actor);
            jdbc.update("""
                    INSERT INTO test_revision
                        (tenant_id, project_id, test_case_id, id, revision_no, title, description, preconditions, created_by, sealed_at)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, NULL)
                    """, project.tenantId(), projectId, testCaseId, revisionId, normalized.title(),
                    normalized.description(), normalized.preconditions(), actor);
            insertSteps(project.tenantId(), projectId, testCaseId, revisionId, normalized.steps(), Set.of(), false);
            sealRevision(project.tenantId(), projectId, testCaseId, revisionId);
            jdbc.update("""
                    UPDATE test_case SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND project_id = ? AND id = ?
                    """, revisionId, project.tenantId(), projectId, testCaseId);
            appendAudit(project.tenantId(), projectId, actor, "test_case.created", testCaseId, 1);
            appendOutbox(project.tenantId(), projectId, testCaseId, revisionId, "test_case.created");
            TestCaseView result = getAuthorized(actor, projectId, testCaseId);
            saveIdempotency(project.tenantId(), projectId, actor, CREATE_ROUTE, idempotencyKey, result);
            return result;
        } catch (DataIntegrityViolationException ex) {
            throw ProjectAccessException.conflict("TEST_CASE_CONFLICT", "Test case could not be created");
        }
    }

    @Transactional(readOnly = true)
    public TestCasePage list(UUID actor, UUID projectId, String query, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 200) throw ProjectAccessException.invalid("limit must be between 1 and 200");
        String cursorValue = cursor == null ? "" : cursor.trim();
        if (!cursorValue.isEmpty()) {
            try {
                if (Long.parseLong(cursorValue) < 1) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw ProjectAccessException.invalid("cursor must be a positive test case number");
            }
        }
        String q = query == null ? "" : query.trim();
        String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
        List<TestCaseSummary> fetched = jdbc.query("""
                SELECT t.id, t.project_id, t.display_number, t.test_type, t.row_version,
                       t.created_at, t.created_by, r.id AS revision_id, r.revision_no,
                       r.title, r.description, r.preconditions
                FROM test_case t JOIN test_revision r
                  ON r.tenant_id = t.tenant_id AND r.project_id = t.project_id
                 AND r.test_case_id = t.id AND r.id = t.current_revision_id
                WHERE t.tenant_id = ? AND t.project_id = ?
                  AND t.display_number > COALESCE(NULLIF(?, '')::BIGINT, 0)
                  AND (? = '' OR r.title ILIKE ? ESCAPE '\\'
                       OR CAST(t.display_number AS TEXT) LIKE ? ESCAPE '\\')
                ORDER BY t.display_number
                LIMIT ?
                """, TestCaseService::mapSummary, project.tenantId(), projectId,
                cursorValue, q, like, like, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<TestCaseSummary> items = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore ? Long.toString(items.get(items.size() - 1).displayNumber()) : null;
        return new TestCasePage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public TestCaseView getAuthorized(UUID actor, UUID projectId, UUID testCaseId) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        List<TestCaseRow> rows = jdbc.query("""
                SELECT t.id, t.project_id, t.display_number, t.test_type, t.row_version,
                       t.created_at, t.created_by, r.id AS revision_id, r.revision_no,
                       r.title, r.description, r.preconditions, r.created_at AS revision_created_at,
                       r.created_by AS revision_created_by
                FROM test_case t JOIN test_revision r
                  ON r.tenant_id = t.tenant_id AND r.project_id = t.project_id
                 AND r.test_case_id = t.id AND r.id = t.current_revision_id
                WHERE t.tenant_id = ? AND t.project_id = ? AND t.id = ?
                """, TestCaseService::mapCaseRow, project.tenantId(), projectId, testCaseId);
        if (rows.isEmpty()) throw ProjectAccessException.notFound();
        return toView(rows.get(0), steps(project.tenantId(), projectId, testCaseId, rows.get(0).revisionId()));
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(UUID actor, UUID projectId, UUID testCaseId) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        ensureCase(project.tenantId(), projectId, testCaseId);
        List<RevisionRecord> records = jdbc.query("""
                SELECT id, test_case_id, revision_no, title, description, preconditions,
                       created_at, created_by
                FROM test_revision
                WHERE tenant_id = ? AND project_id = ? AND test_case_id = ?
                ORDER BY revision_no DESC
                """, TestCaseService::mapRevisionRecord,
                project.tenantId(), projectId, testCaseId);
        return records.stream().map(record -> withSteps(record, project.tenantId(), projectId)).toList();
    }

    @Transactional(readOnly = true)
    public RevisionView revision(UUID actor, UUID projectId, UUID testCaseId, UUID revisionId) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        ensureCase(project.tenantId(), projectId, testCaseId);
        List<RevisionRecord> values = jdbc.query("""
                SELECT id, test_case_id, revision_no, title, description, preconditions,
                       created_at, created_by
                FROM test_revision
                WHERE tenant_id = ? AND project_id = ? AND test_case_id = ? AND id = ?
                """, TestCaseService::mapRevisionRecord,
                project.tenantId(), projectId, testCaseId, revisionId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return withSteps(values.get(0), project.tenantId(), projectId);
    }

    @Transactional
    public TestCaseView appendRevision(UUID actor, UUID projectId, UUID testCaseId,
            RevisionCommand command, String ifMatch, String idempotencyKey) {
        lockProjectTransaction(projectId);
        ProjectService.ProjectView project = authorizedProject(actor, projectId, true);
        setContext(project.tenantId(), actor, projectId);
        validateIdempotencyKey(idempotencyKey);
        long expected = parseIfMatch(ifMatch);
        RevisionCommand normalized = requireRevision(command);
        String hash = revisionRequestHash(testCaseId.toString(), Long.toString(expected), normalized.title(),
                normalized.description(), normalized.preconditions(), normalized.steps());
        String route = REVISION_ROUTE + testCaseId;
        IdempotencyRecord replay = claimOrReplay(project.tenantId(), projectId, actor, route, idempotencyKey, hash);
        if (replay != null) return replayView(actor, projectId, replay);

        TestCaseRow current = lockCurrent(project.tenantId(), projectId, testCaseId);
        if (current == null) throw ProjectAccessException.notFound();
        if (current.rowVersion() != expected) {
            throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Test case was changed by another request");
        }
        List<StepView> priorSteps = steps(project.tenantId(), projectId, testCaseId, current.revisionId());
        List<StepCommand> resolved = resolveSteps(normalized.steps(), priorSteps);
        if (sameRevision(current, normalized, resolved, priorSteps)) {
            TestCaseView result = toView(current, priorSteps);
            saveIdempotency(project.tenantId(), projectId, actor, route, idempotencyKey, result);
            return result;
        }
        UUID revisionId = UUID.randomUUID();
        long nextRevision = current.revisionNo() + 1;
        jdbc.update("""
                INSERT INTO test_revision
                    (tenant_id, project_id, test_case_id, id, revision_no, title, description, preconditions, created_by, sealed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, project.tenantId(), projectId, testCaseId, revisionId, nextRevision,
                normalized.title(), normalized.description(), normalized.preconditions(), actor);
        insertSteps(project.tenantId(), projectId, testCaseId, revisionId, resolved, keys(priorSteps), true);
        sealRevision(project.tenantId(), projectId, testCaseId, revisionId);
        int updated = jdbc.update("""
                UPDATE test_case SET row_version = row_version + 1,
                    current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND project_id = ? AND id = ? AND row_version = ?
                """, revisionId, project.tenantId(), projectId, testCaseId, expected);
        if (updated != 1) throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Test case was changed by another request");
        appendAudit(project.tenantId(), projectId, actor, "test_case.revised", testCaseId, nextRevision);
        appendOutbox(project.tenantId(), projectId, testCaseId, revisionId, "test_case.revised");
        TestCaseView result = getAuthorized(actor, projectId, testCaseId);
        saveIdempotency(project.tenantId(), projectId, actor, route, idempotencyKey, result);
        return result;
    }

    private boolean sameRevision(TestCaseRow current, RevisionCommand command, List<StepCommand> resolved,
            List<StepView> prior) {
        if (!current.title().equals(command.title()) || !current.description().equals(command.description())
                || !current.preconditions().equals(command.preconditions()) || resolved.size() != prior.size()) return false;
        for (int i = 0; i < prior.size(); i++) {
            StepView a = prior.get(i);
            StepCommand b = resolved.get(i);
            if (!a.stepKey().equals(b.stepKey()) || a.ordinal() != b.ordinal()
                    || !a.action().equals(b.action()) || !a.expected().equals(b.expected())) return false;
        }
        return true;
    }

    private List<StepCommand> resolveSteps(List<StepCommand> requested, List<StepView> prior) {
        Set<UUID> known = keys(prior);
        Set<UUID> seen = new HashSet<>();
        List<StepCommand> result = new ArrayList<>();
        for (StepCommand step : requested) {
            UUID key = step.stepKey() == null ? UUID.randomUUID() : step.stepKey();
            if (step.stepKey() != null && !known.contains(key)) {
                throw ProjectAccessException.invalid("step_key does not belong to this test case");
            }
            if (!seen.add(key)) throw ProjectAccessException.invalid("step_key must be unique within a revision");
            result.add(new StepCommand(key, step.ordinal(), step.action(), step.expected()));
        }
        return result;
    }

    private void insertSteps(UUID tenantId, UUID projectId, UUID testCaseId, UUID revisionId,
            List<StepCommand> values, Set<UUID> priorKeys, boolean update) {
        validateStepOrdinals(values);
        for (StepCommand step : values) {
            if (update && step.stepKey() == null) throw new IllegalStateException("resolved step key is required");
            UUID key = step.stepKey() == null ? UUID.randomUUID() : step.stepKey();
            jdbc.update("""
                    INSERT INTO test_step
                        (tenant_id, project_id, test_case_id, revision_id, step_key, ordinal, action, expected)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, projectId, testCaseId, revisionId, key, step.ordinal(), step.action(), step.expected());
        }
    }

    private static void validateStepOrdinals(List<StepCommand> values) {
        if (values == null) throw ProjectAccessException.invalid("steps is required");
        if (values.size() > MAX_STEPS) throw ProjectAccessException.invalid("steps cannot exceed " + MAX_STEPS);
        Set<Integer> ordinals = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            StepCommand step = values.get(i);
            if (step == null || step.ordinal() != i + 1 || !ordinals.add(step.ordinal())) {
                throw ProjectAccessException.invalid("step ordinals must be contiguous and unique starting at 1");
            }
            requiredStepText(step.action(), "action", 10_000);
            requiredStepText(step.expected(), "expected", 10_000);
        }
    }

    private List<StepView> steps(UUID tenantId, UUID projectId, UUID testCaseId, UUID revisionId) {
        return jdbc.query("""
                SELECT step_key, ordinal, action, expected
                FROM test_step
                WHERE tenant_id = ? AND project_id = ? AND test_case_id = ? AND revision_id = ?
                ORDER BY ordinal
                """, (rs, row) -> new StepView(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"),
                        rs.getString("action"), rs.getString("expected")), tenantId, projectId, testCaseId, revisionId);
    }

    private void ensureCase(UUID tenantId, UUID projectId, UUID testCaseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND project_id = ? AND id = ?",
                Integer.class, tenantId, projectId, testCaseId);
        if (count == null || count == 0) throw ProjectAccessException.notFound();
    }

    private TestCaseRow lockCurrent(UUID tenantId, UUID projectId, UUID testCaseId) {
        List<TestCaseRow> rows = jdbc.query("""
                SELECT t.id, t.project_id, t.display_number, t.test_type, t.row_version,
                       t.created_at, t.created_by, r.id AS revision_id, r.revision_no,
                       r.title, r.description, r.preconditions, r.created_at AS revision_created_at,
                       r.created_by AS revision_created_by
                FROM test_case t JOIN test_revision r
                  ON r.tenant_id = t.tenant_id AND r.project_id = t.project_id
                 AND r.test_case_id = t.id AND r.id = t.current_revision_id
                WHERE t.tenant_id = ? AND t.project_id = ? AND t.id = ?
                FOR UPDATE OF t
                """, TestCaseService::mapCaseRow, tenantId, projectId, testCaseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long allocateNumber(UUID tenantId, UUID projectId) {
        jdbc.update("""
                INSERT INTO test_case_number_allocator (tenant_id, project_id, next_number)
                VALUES (?, ?, 1) ON CONFLICT (tenant_id, project_id) DO NOTHING
                """, tenantId, projectId);
        Long value = jdbc.queryForObject("""
                UPDATE test_case_number_allocator SET next_number = next_number + 1
                WHERE tenant_id = ? AND project_id = ? RETURNING next_number - 1
                """, Long.class, tenantId, projectId);
        if (value == null) throw new IllegalStateException("Test case number allocator did not return a value");
        return value;
    }

    private IdempotencyRecord claimOrReplay(UUID tenantId, UUID projectId, UUID principalId,
            String route, String key, String requestHash) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                tenantId + ":" + projectId + ":" + principalId + ":" + route + ":" + key);
        jdbc.update("""
                DELETE FROM test_case_idempotency
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ? AND route = ?
                  AND idempotency_key = ? AND expires_at <= CURRENT_TIMESTAMP
                """, tenantId, projectId, principalId, route, key);
        int inserted = jdbc.update("""
                INSERT INTO test_case_idempotency
                    (tenant_id, project_id, principal_id, route, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, principal_id, route, idempotency_key) DO NOTHING
                """, tenantId, projectId, principalId, route, key, requestHash);
        if (inserted == 1) return null;
        List<IdempotencyRecord> values = jdbc.query("""
                SELECT request_hash, test_case_id, revision_id, result_display_number,
                       result_row_version, result_revision_no
                FROM test_case_idempotency
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ? AND route = ?
                  AND idempotency_key = ? AND expires_at > CURRENT_TIMESTAMP
                """, (rs, row) -> new IdempotencyRecord(rs.getString("request_hash"),
                        rs.getObject("test_case_id", UUID.class), rs.getObject("revision_id", UUID.class),
                        (Long) rs.getObject("result_display_number"), (Long) rs.getObject("result_row_version"),
                        (Long) rs.getObject("result_revision_no")), tenantId, projectId, principalId, route, key);
        if (values.isEmpty()) throw ProjectAccessException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request is still being processed");
        IdempotencyRecord record = values.get(0);
        if (!record.requestHash().equals(requestHash)) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used with a different request");
        }
        if (record.testCaseId() == null || record.revisionId() == null) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request is still being processed");
        }
        return record;
    }

    private void saveIdempotency(UUID tenantId, UUID projectId, UUID principalId, String route,
            String key, TestCaseView result) {
        jdbc.update("""
                UPDATE test_case_idempotency
                SET test_case_id = ?, revision_id = ?, result_display_number = ?,
                    result_row_version = ?, result_revision_no = ?
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ? AND route = ? AND idempotency_key = ?
                """, result.id(), result.currentRevision().id(), result.displayNumber(), result.rowVersion(),
                result.currentRevision().revisionNo(), tenantId, projectId, principalId, route, key);
    }

    private TestCaseView replayView(UUID actor, UUID projectId, IdempotencyRecord record) {
        // Authorization is rechecked by the caller before this method.  The
        // immutable revision is read by ID, never synthesized from the mutable
        // current row, so a replay cannot pair old intent with a new ETag/body.
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        List<TestCaseRow> rows = jdbc.query("""
                SELECT t.id, t.project_id, t.display_number, t.test_type, t.row_version,
                       t.created_at, t.created_by, r.id AS revision_id, r.revision_no,
                       r.title, r.description, r.preconditions, r.created_at AS revision_created_at,
                       r.created_by AS revision_created_by
                FROM test_case t JOIN test_revision r
                  ON r.tenant_id = t.tenant_id AND r.project_id = t.project_id
                 AND r.test_case_id = t.id AND r.id = ?
                WHERE t.tenant_id = ? AND t.project_id = ? AND t.id = ?
                """, TestCaseService::mapCaseRow, record.revisionId(), project.tenantId(), projectId, record.testCaseId());
        if (rows.isEmpty()) throw ProjectAccessException.conflict("IDEMPOTENCY_RESPONSE_UNAVAILABLE", "The idempotency response is unavailable");
        TestCaseRow row = rows.get(0);
        long originalVersion = record.resultRowVersion() == null ? row.rowVersion() : record.resultRowVersion();
        return new TestCaseView(row.id(), row.projectId(), row.displayNumber(), row.testType(), originalVersion,
                row.createdAt(), row.createdBy(), toRevision(row, steps(project.tenantId(), projectId, row.id(), row.revisionId())));
    }

    private ProjectService.ProjectView authorizedProject(UUID actor, UUID projectId, boolean write) {
        return write ? projects.requireTestWriteAccess(actor, projectId)
                : projects.requireTestReadAccess(actor, projectId);
    }

    private static CreateCommand normalizeCreate(CreateCommand command) {
        if (command == null) throw ProjectAccessException.invalid("request body is required");
        String type = command.testType() == null || command.testType().isBlank() ? "MANUAL" : command.testType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) throw ProjectAccessException.invalid("Only MANUAL test cases are supported");
        return new CreateCommand(type, requiredText(command.title(), "title", 500),
                optionalText(command.description(), "description", 100_000),
                optionalText(command.preconditions(), "preconditions", 100_000), normalizeSteps(command.steps()));
    }

    private static RevisionCommand requireRevision(RevisionCommand command) {
        if (command == null) throw ProjectAccessException.invalid("request body is required");
        RevisionCommand normalized = new RevisionCommand(requiredText(command.title(), "title", 500),
                optionalText(command.description(), "description", 100_000),
                optionalText(command.preconditions(), "preconditions", 100_000), normalizeSteps(command.steps()));
        return normalized;
    }

    private static List<StepCommand> normalizeSteps(List<StepCommand> values) {
        if (values == null) throw ProjectAccessException.invalid("steps is required");
        if (values.size() > MAX_STEPS) throw ProjectAccessException.invalid("steps cannot exceed " + MAX_STEPS);
        List<StepCommand> normalized = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            StepCommand step = values.get(index);
            if (step == null) throw ProjectAccessException.invalid("steps must contain objects");
            if (step.ordinal() != null && step.ordinal() != index + 1) {
                throw ProjectAccessException.invalid("step ordinals must follow the submitted order");
            }
            String action = requiredStepText(step.action(), "action", 10_000);
            String expected = requiredStepText(step.expected(), "expected", 10_000);
            normalized.add(new StepCommand(step.stepKey(), index + 1, action, expected));
        }
        return List.copyOf(normalized);
    }

    private static String requiredText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw ProjectAccessException.invalid(field + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }

    private static String optionalText(String value, String field, int max) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > max) throw ProjectAccessException.invalid(field + " is too long");
        return normalized;
    }

    private static String requiredStepText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw ProjectAccessException.invalid(field + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) throw ProjectAccessException.preconditionRequired("Idempotency-Key is required");
        if (key.length() < 8 || key.length() > 128) throw ProjectAccessException.invalid("Idempotency-Key must contain 8 to 128 characters");
    }

    static long parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw ProjectAccessException.preconditionRequired("If-Match must be one strong quoted entity tag");
        }
        if (!value.trim().matches("\\\"[1-9][0-9]*\\\"")) {
            throw ProjectAccessException.invalid("If-Match must be one strong quoted entity tag");
        }
        try {
            return Long.parseLong(value.trim().substring(1, value.trim().length() - 1));
        } catch (NumberFormatException ex) {
            throw ProjectAccessException.invalid("If-Match must contain a positive entity version");
        }
    }

    private static String requestHash(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) canonical.append(part == null ? "N;" : "V" + part.length() + ":" + part + ";");
        return sha256(canonical.toString());
    }

    private static String requestHash(String type, String title, String description, String preconditions,
            List<StepCommand> steps) {
        List<String> parts = new ArrayList<>(List.of(type, title, description, preconditions));
        for (StepCommand step : steps) {
            parts.add(step.stepKey() == null ? null : step.stepKey().toString());
            parts.add(Integer.toString(step.ordinal()));
            parts.add(step.action());
            parts.add(step.expected());
        }
        return requestHash(parts.toArray(String[]::new));
    }

    private static String revisionRequestHash(String testCaseId, String expectedVersion, String title,
            String description, String preconditions, List<StepCommand> steps) {
        List<String> parts = new ArrayList<>(List.of(testCaseId, expectedVersion, title, description, preconditions));
        for (StepCommand step : steps) {
            parts.add(step.stepKey() == null ? null : step.stepKey().toString());
            parts.add(Integer.toString(step.ordinal()));
            parts.add(step.action());
            parts.add(step.expected());
        }
        return requestHash(parts.toArray(String[]::new));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private void appendAudit(UUID tenantId, UUID projectId, UUID actor, String action, UUID testCaseId, long revision) {
        jdbc.update("""
                INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type, object_id, object_revision)
                VALUES (?, ?, ?, ?, 'test_case', ?, ?)
                """, tenantId, projectId, actor, action, testCaseId, revision);
    }

    private void appendOutbox(UUID tenantId, UUID projectId, UUID testCaseId, UUID revisionId, String eventType) {
        jdbc.update("""
                INSERT INTO test_case_outbox_event (tenant_id, project_id, test_case_id, revision_id, event_type)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, projectId, testCaseId, revisionId, eventType);
    }

    private void sealRevision(UUID tenantId, UUID projectId, UUID testCaseId, UUID revisionId) {
        int updated = jdbc.update("""
                UPDATE test_revision SET sealed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND project_id = ? AND test_case_id = ? AND id = ? AND sealed_at IS NULL
                """, tenantId, projectId, testCaseId, revisionId);
        if (updated != 1) throw new IllegalStateException("Test revision could not be sealed");
    }

    private void setContext(UUID tenantId, UUID actor, UUID projectId) {
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id', ?, true)", String.class, actor.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.project_id', ?, true)", String.class, projectId.toString());
    }

    private void lockProjectTransaction(UUID projectId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", rs -> { }, projectId.toString());
    }

    private static Set<UUID> keys(List<StepView> values) {
        Set<UUID> result = new HashSet<>();
        for (StepView value : values) result.add(value.stepKey());
        return result;
    }

    private static TestCaseSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new TestCaseSummary(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getLong("display_number"), rs.getString("test_type"), rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getLong("revision_no"), rs.getString("title"),
                rs.getString("description"), rs.getString("preconditions"));
    }

    private static TestCaseRow mapCaseRow(ResultSet rs, int row) throws SQLException {
        return new TestCaseRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getLong("display_number"), rs.getString("test_type"), rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getLong("revision_no"), rs.getString("title"),
                rs.getString("description"), rs.getString("preconditions"),
                rs.getObject("revision_created_at", OffsetDateTime.class), rs.getObject("revision_created_by", UUID.class));
    }

    private static RevisionRecord mapRevisionRecord(ResultSet rs, int row) throws SQLException {
        return new RevisionRecord(rs.getObject("id", UUID.class), rs.getObject("test_case_id", UUID.class),
                rs.getLong("revision_no"), rs.getString("title"),
                rs.getString("description"), rs.getString("preconditions"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class));
    }

    private RevisionView withSteps(RevisionRecord record, UUID tenantId, UUID projectId) {
        return new RevisionView(record.id(), record.testCaseId(), record.revisionNo(), record.title(),
                record.description(), record.preconditions(), record.createdAt(), record.createdBy(),
                steps(tenantId, projectId, record.testCaseId(), record.id()));
    }

    private static TestCaseView toView(TestCaseRow row, List<StepView> steps) {
        return new TestCaseView(row.id(), row.projectId(), row.displayNumber(), row.testType(), row.rowVersion(),
                row.createdAt(), row.createdBy(), toRevision(row, steps));
    }

    private static RevisionView toRevision(TestCaseRow row, List<StepView> steps) {
        return new RevisionView(row.revisionId(), row.id(), row.revisionNo(), row.title(), row.description(),
                row.preconditions(), row.revisionCreatedAt(), row.revisionCreatedBy(), steps);
    }

    public record CreateCommand(String testType, String title, String description, String preconditions,
            List<StepCommand> steps) { }
    public record RevisionCommand(String title, String description, String preconditions, List<StepCommand> steps) { }
    public record StepCommand(UUID stepKey, Integer ordinal, String action, String expected) { }
    public record TestCasePage(List<TestCaseSummary> items, String nextCursor) { }
    public record TestCaseSummary(UUID id, UUID projectId, long displayNumber, String testType, long rowVersion,
            OffsetDateTime createdAt, UUID createdBy, UUID currentRevisionId, long revisionNo, String title,
            String description, String preconditions) { }
    public record TestCaseView(UUID id, UUID projectId, long displayNumber, String testType, long rowVersion,
            OffsetDateTime createdAt, UUID createdBy, RevisionView currentRevision) { }
    public record RevisionView(UUID id, UUID testCaseId, long revisionNo, String title, String description,
            String preconditions, OffsetDateTime createdAt, UUID createdBy, List<StepView> steps) { }
    public record StepView(UUID stepKey, int ordinal, String action, String expected) { }
    private record TestCaseRow(UUID id, UUID projectId, long displayNumber, String testType, long rowVersion,
            OffsetDateTime createdAt, UUID createdBy, UUID revisionId, long revisionNo, String title,
            String description, String preconditions, OffsetDateTime revisionCreatedAt, UUID revisionCreatedBy) { }
    private record IdempotencyRecord(String requestHash, UUID testCaseId, UUID revisionId,
            Long resultDisplayNumber, Long resultRowVersion, Long resultRevisionNo) { }
    private record RevisionRecord(UUID id, UUID testCaseId, long revisionNo, String title, String description,
            String preconditions, OffsetDateTime createdAt, UUID createdBy) { }
}
