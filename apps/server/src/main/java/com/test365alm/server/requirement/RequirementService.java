package com.test365alm.server.requirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * First M07 slice: a project-scoped requirement identity with append-only
 * revisions.  The service deliberately uses the restricted runtime data
 * source; migrations and fixtures remain the only owner operations.
 */
@Service
public class RequirementService {
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final String CREATE_ROUTE = "requirements:create";
    private static final String UPDATE_ROUTE = "requirements:update";
    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    public RequirementService(JdbcTemplate jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public RequirementView create(UUID actor, UUID projectId, CreateCommand command, String idempotencyKey) {
        lockProjectTransaction(projectId);
        ProjectService.ProjectView project = authorizedProject(actor, projectId, true);
        setContext(project.tenantId(), actor, projectId);
        validateIdempotencyKey(idempotencyKey);
        CreateCommand normalized = normalizeCreate(command);
        String hash = requestHash(normalized.title(), normalized.body(), normalized.priority());
        IdempotencyRecord replay = claimOrReplay(project.tenantId(), projectId, actor, CREATE_ROUTE,
                idempotencyKey, hash);
        if (replay != null) return replayView(actor, projectId, replay);

        UUID requirementId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        long displayNumber = allocateNumber(project.tenantId(), projectId);
        try {
            jdbc.update("""
                    INSERT INTO requirement (tenant_id, project_id, id, display_number, row_version, created_by)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, project.tenantId(), projectId, requirementId, displayNumber, actor);
            jdbc.update("""
                    INSERT INTO requirement_revision
                        (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
                    """, project.tenantId(), projectId, revisionId, requirementId,
                    normalized.title(), normalized.body(), normalized.priority(), actor);
            jdbc.update("""
                    UPDATE requirement SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND project_id = ? AND id = ?
                    """, revisionId, project.tenantId(), projectId, requirementId);
            String hashValue = contentHash(normalized.title(), normalized.body(), normalized.priority());
            appendAudit(project.tenantId(), projectId, actor, "requirement.created", requirementId, 1,
                    null, hashValue);
            appendOutbox(project.tenantId(), projectId, requirementId, revisionId, "requirement.created");
            RequirementView result = getAuthorized(actor, projectId, requirementId);
            saveIdempotency(project.tenantId(), projectId, actor, CREATE_ROUTE, idempotencyKey, result);
            return result;
        } catch (DataIntegrityViolationException ex) {
            // A unique project number or revision race is a safe conflict, not
            // a second partially-created business object.
            throw ProjectAccessException.conflict("REQUIREMENT_CONFLICT", "Requirement could not be created");
        }
    }

    @Transactional(readOnly = true)
    public RequirementPage list(UUID actor, UUID projectId, String query, Integer requestedLimit) {
        return list(actor, projectId, query, null, requestedLimit);
    }

    @Transactional(readOnly = true)
    public RequirementPage list(UUID actor, UUID projectId, String query, String cursor, Integer requestedLimit) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 200) throw ProjectAccessException.invalid("limit must be between 1 and 200");
        String cursorValue = cursor == null ? "" : cursor.trim();
        if (!cursorValue.isEmpty()) {
            try {
                if (Long.parseLong(cursorValue) < 1) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw ProjectAccessException.invalid("cursor must be a positive requirement number");
            }
        }
        setContext(project.tenantId(), actor, projectId);
        String q = query == null ? "" : query.trim();
        String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
        List<RequirementView> fetched = jdbc.query("""
                SELECT r.id, r.tenant_id, r.project_id, r.display_number, r.row_version,
                       r.created_at, r.created_by, rr.id AS revision_id, rr.revision_no,
                       rr.title, rr.body, rr.priority
                FROM requirement r JOIN requirement_revision rr
                  ON rr.tenant_id = r.tenant_id AND rr.project_id = r.project_id
                 AND rr.id = r.current_revision_id
                WHERE r.tenant_id = ? AND r.project_id = ? AND r.deleted_at IS NULL
                   AND r.display_number > COALESCE(NULLIF(?, '')::BIGINT, 0)
                  AND ( ? = '' OR rr.title ILIKE ? ESCAPE '\\'
                        OR CAST(r.display_number AS TEXT) LIKE ? ESCAPE '\\' )
                ORDER BY r.display_number
                LIMIT ?
                """, RequirementService::mapRequirement, project.tenantId(), projectId,
                cursorValue, q, like, like, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<RequirementView> items = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore ? Long.toString(items.get(items.size() - 1).displayNumber()) : null;
        return new RequirementPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public RequirementView getAuthorized(UUID actor, UUID projectId, UUID requirementId) {
        ProjectService.ProjectView project = authorizedProject(actor, projectId, false);
        setContext(project.tenantId(), actor, projectId);
        List<RequirementView> values = jdbc.query("""
                SELECT r.id, r.tenant_id, r.project_id, r.display_number, r.row_version,
                       r.created_at, r.created_by, rr.id AS revision_id, rr.revision_no,
                       rr.title, rr.body, rr.priority
                FROM requirement r JOIN requirement_revision rr
                  ON rr.tenant_id = r.tenant_id AND rr.project_id = r.project_id
                 AND rr.id = r.current_revision_id
                WHERE r.tenant_id = ? AND r.project_id = ? AND r.id = ? AND r.deleted_at IS NULL
                """, RequirementService::mapRequirement, project.tenantId(), projectId, requirementId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    @Transactional
    public RequirementView update(UUID actor, UUID projectId, UUID requirementId, UpdateCommand command,
            String ifMatch, String idempotencyKey) {
        lockProjectTransaction(projectId);
        ProjectService.ProjectView project = authorizedProject(actor, projectId, true);
        setContext(project.tenantId(), actor, projectId);
        validateIdempotencyKey(idempotencyKey);
        long expected = parseIfMatch(ifMatch);
        UpdateCommand patch = normalizePatch(command);
        // The optimistic version is part of the request contract: reusing an
        // idempotency key with the same body but a different If-Match must not
        // replay an update that was authorized against another revision.
        String hash = requestHash(requirementId.toString(), Long.toString(expected),
                patch.title(), patch.body(), patch.priority());
        IdempotencyRecord replay = claimOrReplay(project.tenantId(), projectId, actor, UPDATE_ROUTE + ":" + requirementId,
                idempotencyKey, hash);
        if (replay != null) return replayView(actor, projectId, replay);

        RequirementRow current = lockCurrent(project.tenantId(), projectId, requirementId);
        if (current == null) throw ProjectAccessException.notFound();
        if (current.rowVersion() != expected) {
            throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Requirement was changed by another request");
        }
        String title = patch.title() == null ? current.title() : requiredTitle(patch.title());
        String body = patch.body() == null ? current.body() : requiredBody(patch.body());
        String priority = patch.priority() == null ? current.priority() : normalizePriority(patch.priority());
        if (title.equals(current.title()) && body.equals(current.body()) && priority.equals(current.priority())) {
            RequirementView result = getAuthorized(actor, projectId, requirementId);
            saveIdempotency(project.tenantId(), projectId, actor,
                    UPDATE_ROUTE + ":" + requirementId, idempotencyKey, result);
            return result;
        }

        UUID revisionId = UUID.randomUUID();
        long nextRevision = current.revisionNo() + 1;
        // Insert the immutable revision before advancing the FK pointer.  The
        // current_revision_id constraint intentionally makes the reverse order
        // impossible, while the surrounding transaction rolls back both rows
        // if the pointer CAS or audit/outbox write fails.
        jdbc.update("""
                INSERT INTO requirement_revision
                    (tenant_id, project_id, id, requirement_id, revision_no, title, body, priority, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, project.tenantId(), projectId, revisionId, requirementId, nextRevision, title, body, priority, actor);
        int updated = jdbc.update("""
                UPDATE requirement SET row_version = row_version + 1,
                    current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND project_id = ? AND id = ? AND row_version = ?
                """, revisionId, project.tenantId(), projectId, requirementId, expected);
        if (updated != 1) {
            throw ProjectAccessException.preconditionFailed("STALE_VERSION", "Requirement was changed by another request");
        }
        String beforeHash = contentHash(current.title(), current.body(), current.priority());
        String afterHash = contentHash(title, body, priority);
        appendAudit(project.tenantId(), projectId, actor, "requirement.updated", requirementId, nextRevision,
                beforeHash, afterHash);
        appendOutbox(project.tenantId(), projectId, requirementId, revisionId, "requirement.updated");
        RequirementView result = getAuthorized(actor, projectId, requirementId);
        saveIdempotency(project.tenantId(), projectId, actor, UPDATE_ROUTE + ":" + requirementId,
                idempotencyKey, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(UUID actor, UUID projectId, UUID requirementId) {
        RequirementView ignored = getAuthorized(actor, projectId, requirementId);
        ProjectService.ProjectView project = projects.getProject(actor, projectId);
        setContext(project.tenantId(), actor, projectId);
        return jdbc.query("""
                SELECT rr.id, rr.requirement_id, rr.revision_no, rr.title, rr.body, rr.priority,
                       rr.created_at, rr.created_by
                FROM requirement_revision rr
                WHERE rr.tenant_id = ? AND rr.project_id = ? AND rr.requirement_id = ?
                ORDER BY rr.revision_no DESC
                """, RequirementService::mapRevision, project.tenantId(), projectId, requirementId);
    }

    @Transactional(readOnly = true)
    public RevisionView revision(UUID actor, UUID projectId, UUID requirementId, UUID revisionId) {
        RequirementView ignored = getAuthorized(actor, projectId, requirementId);
        ProjectService.ProjectView project = projects.getProject(actor, projectId);
        setContext(project.tenantId(), actor, projectId);
        List<RevisionView> values = jdbc.query("""
                SELECT rr.id, rr.requirement_id, rr.revision_no, rr.title, rr.body, rr.priority,
                       rr.created_at, rr.created_by
                FROM requirement_revision rr
                WHERE rr.tenant_id = ? AND rr.project_id = ? AND rr.requirement_id = ? AND rr.id = ?
                """, RequirementService::mapRevision, project.tenantId(), projectId, requirementId, revisionId);
        if (values.isEmpty()) throw ProjectAccessException.notFound();
        return values.get(0);
    }

    private ProjectService.ProjectView authorizedProject(UUID actor, UUID projectId, boolean write) {
        // ProjectService performs the same tenant/member/project checks used by
        // every existing project endpoint.  The write boundary additionally
        // excludes PROJECT_VIEWER while retaining member access.
        return write ? projects.requireRequirementWriteAccess(actor, projectId)
                : projects.requireRequirementReadAccess(actor, projectId);
    }

    private RequirementRow lockCurrent(UUID tenantId, UUID projectId, UUID requirementId) {
        List<RequirementRow> values = jdbc.query("""
                SELECT r.id, r.row_version, r.current_revision_id, rr.revision_no,
                       rr.title, rr.body, rr.priority
                FROM requirement r JOIN requirement_revision rr
                  ON rr.tenant_id = r.tenant_id AND rr.project_id = r.project_id
                 AND rr.id = r.current_revision_id
                WHERE r.tenant_id = ? AND r.project_id = ? AND r.id = ? AND r.deleted_at IS NULL
                FOR UPDATE OF r
                """, (rs, row) -> new RequirementRow(rs.getObject("id", UUID.class),
                        rs.getLong("row_version"), rs.getObject("current_revision_id", UUID.class),
                        rs.getLong("revision_no"), rs.getString("title"), rs.getString("body"),
                        rs.getString("priority")), tenantId, projectId, requirementId);
        return values.isEmpty() ? null : values.get(0);
    }

    private long allocateNumber(UUID tenantId, UUID projectId) {
        jdbc.update("""
                INSERT INTO requirement_number_allocator (tenant_id, project_id, next_number)
                VALUES (?, ?, 1) ON CONFLICT (tenant_id, project_id) DO NOTHING
                """, tenantId, projectId);
        Long value = jdbc.queryForObject("""
                UPDATE requirement_number_allocator SET next_number = next_number + 1
                WHERE tenant_id = ? AND project_id = ? RETURNING next_number - 1
                """, Long.class, tenantId, projectId);
        if (value == null) throw new IllegalStateException("Requirement number allocator did not return a value");
        return value;
    }

    private IdempotencyRecord claimOrReplay(UUID tenantId, UUID projectId, UUID principalId, String route,
            String key, String requestHash) {
        // Serialize claims for the exact intent.  PostgreSQL's unique-index
        // conflict wait is not sufficient for a read-after-conflict replay:
        // an in-flight row can otherwise be observed before its frozen result
        // is populated when two independent runtime connections race.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { /* lock acquired */ },
                tenantId + ":" + projectId + ":" + principalId + ":" + route + ":" + key);
        // Expiration is enforced at the claim boundary.  An expired key is a
        // new intent and cannot replay a response from a prior retention
        // window.  The delete is scoped to this exact actor and route.
        jdbc.update("""
                DELETE FROM requirement_idempotency
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ?
                  AND route = ? AND idempotency_key = ? AND expires_at <= CURRENT_TIMESTAMP
                """, tenantId, projectId, principalId, route, key);
        int inserted = jdbc.update("""
                INSERT INTO requirement_idempotency
                    (tenant_id, project_id, principal_id, route, idempotency_key, request_hash, replay_compatible)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (tenant_id, project_id, principal_id, route, idempotency_key) DO NOTHING
                """, tenantId, projectId, principalId, route, key, requestHash);
        if (inserted == 1) return null;
        List<IdempotencyRecord> existing = jdbc.query("""
                SELECT request_hash, requirement_id, revision_id, replay_compatible,
                       result_display_number,
                       result_row_version, result_revision_no, result_title, result_body,
                       result_priority, result_created_at, result_created_by
                FROM requirement_idempotency
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ?
                  AND route = ? AND idempotency_key = ? AND expires_at > CURRENT_TIMESTAMP
                """, (rs, row) -> new IdempotencyRecord(rs.getString("request_hash"),
                        rs.getObject("requirement_id", UUID.class), rs.getObject("revision_id", UUID.class),
                        rs.getBoolean("replay_compatible"),
                        snapshot(rs, projectId)),
                tenantId, projectId, principalId, route, key);
        if (existing.isEmpty()) throw ProjectAccessException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request is still being processed");
        IdempotencyRecord record = existing.get(0);
        if (!record.replayCompatible()) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_LEGACY_UNSUPPORTED",
                    "This idempotency record predates the current replay format");
        }
        if (!record.requestHash().equals(requestHash)) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used with a different request");
        }
        if (record.requirementId() == null) {
            throw ProjectAccessException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request is still being processed");
        }
        return record;
    }

    private void saveIdempotency(UUID tenantId, UUID projectId, UUID principalId, String route, String key,
            RequirementView result) {
        jdbc.update("""
                UPDATE requirement_idempotency SET requirement_id = ?, revision_id = ?,
                    replay_compatible = TRUE,
                    result_display_number = ?, result_row_version = ?, result_revision_no = ?,
                    result_title = ?, result_body = ?, result_priority = ?,
                    result_created_at = ?, result_created_by = ?
                WHERE tenant_id = ? AND project_id = ? AND principal_id = ? AND route = ? AND idempotency_key = ?
                """, result.id(), result.currentRevisionId(), result.displayNumber(), result.rowVersion(),
                result.revisionNumber(), result.title(), result.body(), result.priority(), result.createdAt(),
                result.createdBy(), tenantId, projectId, principalId, route, key);
    }

    private RequirementView replayView(UUID actor, UUID projectId, IdempotencyRecord replay) {
        if (replay.snapshot() != null) return replay.snapshot();
        // Current-format rows are expected to carry a frozen snapshot. Never
        // synthesize a response from the mutable current requirement row: that
        // could pair a historical idempotency result with a newer ETag/body.
        throw ProjectAccessException.conflict("IDEMPOTENCY_RESPONSE_UNAVAILABLE",
                "The idempotency response is not available for replay");
    }

    private static RequirementView snapshot(ResultSet rs, UUID projectId) throws SQLException {
        UUID id = rs.getObject("requirement_id", UUID.class);
        UUID revisionId = rs.getObject("revision_id", UUID.class);
        Long number = (Long) rs.getObject("result_display_number");
        Long version = (Long) rs.getObject("result_row_version");
        Long revisionNo = (Long) rs.getObject("result_revision_no");
        if (id == null || revisionId == null || number == null || version == null || revisionNo == null
                || rs.getString("result_title") == null || rs.getObject("result_created_at", OffsetDateTime.class) == null
                || rs.getObject("result_created_by", UUID.class) == null) return null;
        return new RequirementView(id, projectId, number, version,
                rs.getObject("result_created_at", OffsetDateTime.class),
                rs.getObject("result_created_by", UUID.class), revisionId, revisionNo,
                rs.getString("result_title"), rs.getString("result_body"), rs.getString("result_priority"));
    }

    private void appendAudit(UUID tenantId, UUID projectId, UUID actor, String action, UUID requirementId,
            long revision, String beforeHash, String afterHash) {
        jdbc.update("""
                INSERT INTO audit_event (tenant_id, project_id, actor_principal_id, action, object_type,
                    object_id, object_revision, before_hash, after_hash)
                VALUES (?, ?, ?, ?, 'requirement', ?, ?, ?, ?)
                """, tenantId, projectId, actor, action, requirementId, revision, beforeHash, afterHash);
    }

    private void appendOutbox(UUID tenantId, UUID projectId, UUID requirementId, UUID revisionId, String eventType) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (tenant_id, project_id, aggregate_id, requirement_id, revision_id, event_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, projectId, requirementId, requirementId, revisionId, eventType);
    }

    private void setContext(UUID tenantId, UUID actor, UUID projectId) {
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id', ?, true)", String.class, actor.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.project_id', ?, true)", String.class, projectId.toString());
    }

    private void lockProjectTransaction(UUID projectId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", rs -> { /* lock acquired */ },
                projectId.toString());
    }

    private static CreateCommand normalizeCreate(CreateCommand command) {
        if (command == null) throw ProjectAccessException.invalid("request body is required");
        return new CreateCommand(requiredTitle(command.title()), requiredBody(command.body()), normalizePriority(command.priority()));
    }

    private static UpdateCommand normalizePatch(UpdateCommand command) {
        if (command == null || (command.title() == null && command.body() == null && command.priority() == null)) {
            throw ProjectAccessException.invalid("at least one supported field is required");
        }
        if (command.title() != null) requiredTitle(command.title());
        if (command.body() != null) requiredBody(command.body());
        if (command.priority() != null) normalizePriority(command.priority());
        return command;
    }

    private static String requiredTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() > 500) {
            throw ProjectAccessException.invalid("title must contain 1 to 500 characters");
        }
        return title.trim();
    }

    private static String requiredBody(String body) {
        String value = body == null ? "" : body;
        if (value.length() > 100_000) throw ProjectAccessException.invalid("body is too long");
        return value;
    }

    private static String normalizePriority(String priority) {
        String value = priority == null || priority.isBlank() ? "MEDIUM" : priority.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(value)) throw ProjectAccessException.invalid("priority must be LOW, MEDIUM, HIGH or CRITICAL");
        return value;
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) throw ProjectAccessException.preconditionRequired("Idempotency-Key is required");
        if (key.length() < 8 || key.length() > 128) throw ProjectAccessException.invalid("Idempotency-Key must contain 8 to 128 characters");
    }

    static long parseIfMatch(String value) {
        if (value == null || value.isBlank() || "*".equals(value.trim())) {
            throw ProjectAccessException.preconditionRequired("If-Match is required and cannot be '*'");
        }
        String normalized = value.trim();
        // RFC 9110 permits a list of entity-tags, but this endpoint's
        // optimistic-lock contract intentionally accepts exactly one strong
        // positive numeric tag.  Reject bare numbers, weak tags and lists so
        // clients cannot accidentally bypass a version check.
        if (!normalized.matches("\\\"[1-9][0-9]*\\\"")) {
            throw ProjectAccessException.invalid("If-Match must be one strong quoted entity tag");
        }
        normalized = normalized.substring(1, normalized.length() - 1);
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw ProjectAccessException.invalid("If-Match must contain a positive entity version");
        }
    }

    private static String requestHash(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) canonical.append("N;");
            else canonical.append("V").append(part.length()).append(':').append(part).append(';');
        }
        return sha256(canonical.toString());
    }

    private static String contentHash(String title, String body, String priority) {
        return requestHash(title, body, priority);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static RequirementView mapRequirement(ResultSet rs, int row) throws SQLException {
        return new RequirementView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getLong("display_number"), rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getLong("revision_no"), rs.getString("title"), rs.getString("body"), rs.getString("priority"));
    }

    private static RevisionView mapRevision(ResultSet rs, int row) throws SQLException {
        return new RevisionView(rs.getObject("id", UUID.class), rs.getObject("requirement_id", UUID.class),
                rs.getLong("revision_no"), rs.getString("title"), rs.getString("body"), rs.getString("priority"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class));
    }

    public record CreateCommand(String title, String body, String priority) { }
    public record UpdateCommand(String title, String body, String priority) { }
    public record RequirementPage(List<RequirementView> items, String nextCursor) { }
    public record RequirementView(UUID id, UUID projectId, long displayNumber, long rowVersion,
            OffsetDateTime createdAt, UUID createdBy, UUID currentRevisionId, long revisionNumber,
            String title, String body, String priority) { }
    public record RevisionView(UUID id, UUID requirementId, long revisionNumber, String title, String body,
            String priority, OffsetDateTime createdAt, UUID createdBy) { }
    private record RequirementRow(UUID id, long rowVersion, UUID revisionId, long revisionNo,
            String title, String body, String priority) { }
    private record IdempotencyRecord(String requestHash, UUID requirementId, UUID revisionId,
            boolean replayCompatible, RequirementView snapshot) { }
}
