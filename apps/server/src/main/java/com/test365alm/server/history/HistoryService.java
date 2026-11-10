package com.test365alm.server.history;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/**
 * 只读历史：用例、缺陷、运行的“谁、何时、做了什么、旧值 → 新值”。
 * audit_event 只存哈希，字段值从不可变修订（test_revision/defect_revision）或 execution_event 的 payload 还原。
 * 全部查询经受限 runtime 数据源，并由 RLS 再次限定在当前项目。
 */
@Service
public class HistoryService {
    public static final Set<String> TYPES = Set.of("test_case", "defect", "run");
    // 只有这些动作产生新修订；其他动作（如发起复测）不重复展示上一版差异。
    private static final Set<String> TEST_REVISING = Set.of("test_case.created", "test_case.revised");
    private static final Set<String> DEFECT_REVISING = Set.of("defect.created", "defect.revised", "defect.transitioned");

    private final JdbcTemplate jdbc;
    private final CoreCommands commands;

    public HistoryService(JdbcTemplate jdbc, CoreCommands commands) {
        this.jdbc = jdbc;
        this.commands = commands;
    }

    public record Actor(UUID id, String displayName) { }

    /** revision：用例/缺陷为修订号，运行为 attempt 序号；无法确定时为 null。 */
    public record HistoryEntry(UUID id, OffsetDateTime at, Actor actor, String action, Long revision,
            List<HistoryDiff.Change> changes) { }

    private record Row(UUID id, OffsetDateTime at, UUID actorId, String actorName, String action, Long revision,
            String payload, Integer stepOrdinal) { }

    record Key(Instant at, UUID id) { }

    /** 按时间倒序（最新在前）分页，游标为不透明字符串。 */
    @Transactional(readOnly = true)
    public CoreCommands.Page<HistoryEntry> history(UUID actor, UUID projectId, String objectType, UUID objectId,
            String cursor, Integer limit) {
        if (objectType == null || !TYPES.contains(objectType)) {
            throw ProjectAccessException.invalid("objectType must be test_case, defect or run");
        }
        int n = CoreCommands.limit(limit);
        Key after = decodeCursor(cursor);
        // 非成员在这里得到 404，不泄露项目或对象是否存在。
        var p = commands.scope(actor, projectId, false, false);
        requireObject(p, objectType, objectId);
        List<Row> rows = objectType.equals("run") ? runRows(p, objectId, after, n + 1) : auditRows(p, objectType, objectId, after, n + 1);
        boolean more = rows.size() > n;
        List<Row> page = more ? rows.subList(0, n) : rows;
        Map<Long, Snapshot> cache = new HashMap<>();
        List<HistoryEntry> items = new ArrayList<>(page.size());
        for (Row r : page) {
            List<HistoryDiff.Change> changes = switch (objectType) {
                case "test_case" -> TEST_REVISING.contains(r.action()) ? testCaseChanges(p, objectId, r.revision(), cache) : List.of();
                case "defect" -> DEFECT_REVISING.contains(r.action()) ? defectChanges(p, objectId, r.revision(), cache) : List.of();
                default -> HistoryDiff.run(r.action(), r.payload() == null ? null : CoreCommands.JSON.readTree(r.payload()), r.stepOrdinal());
            };
            String action = objectType.equals("run") ? r.action().toLowerCase(Locale.ROOT).replace('_', '.') : r.action();
            items.add(new HistoryEntry(r.id(), r.at(), new Actor(r.actorId(), r.actorName()), action, r.revision(), changes));
        }
        String next = more ? encodeCursor(page.get(n - 1)) : null;
        return new CoreCommands.Page<>(items, next);
    }

    private void requireObject(ProjectService.ProjectView p, String type, UUID id) {
        String table = switch (type) {
            case "test_case" -> "test_case";
            case "defect" -> "defect";
            default -> "execution_run";
        };
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND project_id=? AND id=?",
                Integer.class, p.tenantId(), p.id(), id);
        if (count == null || count == 0) throw ProjectAccessException.notFound();
    }

    private List<Row> auditRows(ProjectService.ProjectView p, String type, UUID id, Key after, int max) {
        String keyset = after == null ? "" : " AND (e.at, e.id) < (?::timestamptz, ?)";
        List<Object> args = new ArrayList<>(List.of(p.tenantId(), p.id(), type, id));
        if (after != null) { args.add(after.at().toString()); args.add(after.id()); }
        args.add(max);
        return jdbc.query("""
                SELECT e.id, e.at, e.actor_principal_id, pr.display_name, e.action, e.object_revision
                FROM audit_event e LEFT JOIN principal pr ON pr.id = e.actor_principal_id
                WHERE e.tenant_id=? AND e.project_id=? AND e.object_type=? AND e.object_id=?""" + keyset + """

                ORDER BY e.at DESC, e.id DESC LIMIT ?
                """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("at", OffsetDateTime.class),
                        rs.getObject("actor_principal_id", UUID.class), rs.getString("display_name"), rs.getString("action"),
                        rs.getObject("object_revision", Long.class), null, null), args.toArray());
    }

    private List<Row> runRows(ProjectService.ProjectView p, UUID runId, Key after, int max) {
        String keyset = after == null ? "" : " AND (e.occurred_at, e.id) < (?::timestamptz, ?)";
        List<Object> args = new ArrayList<>(List.of(p.tenantId(), p.id(), runId));
        if (after != null) { args.add(after.at().toString()); args.add(after.id()); }
        args.add(max);
        // STEP_RECORDED 只带 stepKey，序号从同一 attempt 的 run_step 取。
        return jdbc.query("""
                SELECT e.id, e.occurred_at, e.actor_principal_id, pr.display_name, e.event_type, e.payload::text AS payload,
                       a.attempt_no, s.ordinal
                FROM execution_event e
                LEFT JOIN principal pr ON pr.id = e.actor_principal_id
                LEFT JOIN run_attempt a ON a.tenant_id=e.tenant_id AND a.project_id=e.project_id AND a.id=e.attempt_id
                LEFT JOIN run_step s ON s.tenant_id=e.tenant_id AND s.project_id=e.project_id AND s.attempt_id=e.attempt_id
                     AND e.event_type='STEP_RECORDED' AND s.step_key::text = e.payload->>'stepKey'
                WHERE e.tenant_id=? AND e.project_id=? AND e.run_id=?""" + keyset + """

                ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?
                """, (rs, i) -> {
                    Integer attemptNo = rs.getObject("attempt_no", Integer.class);
                    return new Row(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                            rs.getObject("actor_principal_id", UUID.class), rs.getString("display_name"), rs.getString("event_type"),
                            attemptNo == null ? null : attemptNo.longValue(), rs.getString("payload"),
                            rs.getObject("ordinal", Integer.class));
                }, args.toArray());
    }

    /** 某一修订的字段值与步骤；不存在时为 null。 */
    private record Snapshot(Map<String, String> fields, List<HistoryDiff.Step> steps) { }

    private List<HistoryDiff.Change> testCaseChanges(ProjectService.ProjectView p, UUID id, Long revision, Map<Long, Snapshot> cache) {
        if (revision == null) return List.of();
        Snapshot cur = cache.computeIfAbsent(revision, r -> testRevision(p, id, r));
        if (cur == null) return List.of();
        Snapshot prev = revision > 1 ? cache.computeIfAbsent(revision - 1, r -> testRevision(p, id, r)) : null;
        List<HistoryDiff.Change> changes = new ArrayList<>(HistoryDiff.fields(prev == null ? null : prev.fields(), cur.fields()));
        changes.addAll(HistoryDiff.steps(prev == null ? List.of() : prev.steps(), cur.steps()));
        return changes;
    }

    private Snapshot testRevision(ProjectService.ProjectView p, UUID id, long no) {
        var rows = jdbc.query("""
                SELECT id, title, description, preconditions FROM test_revision
                WHERE tenant_id=? AND project_id=? AND test_case_id=? AND revision_no=?
                """, (rs, i) -> {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("title", rs.getString("title"));
                    f.put("description", rs.getString("description"));
                    f.put("preconditions", rs.getString("preconditions"));
                    return Map.entry(rs.getObject("id", UUID.class), f);
                }, p.tenantId(), p.id(), id, no);
        if (rows.isEmpty()) return null;
        List<HistoryDiff.Step> steps = jdbc.query("""
                SELECT step_key, ordinal, action, expected FROM test_step
                WHERE tenant_id=? AND project_id=? AND test_case_id=? AND revision_id=? ORDER BY ordinal
                """, (rs, i) -> new HistoryDiff.Step(rs.getObject("step_key", UUID.class), rs.getInt("ordinal"),
                        rs.getString("action"), rs.getString("expected")), p.tenantId(), p.id(), id, rows.get(0).getKey());
        return new Snapshot(rows.get(0).getValue(), steps);
    }

    private List<HistoryDiff.Change> defectChanges(ProjectService.ProjectView p, UUID id, Long revision, Map<Long, Snapshot> cache) {
        if (revision == null) return List.of();
        Snapshot cur = cache.computeIfAbsent(revision, r -> defectRevision(p, id, r));
        if (cur == null) return List.of();
        Snapshot prev = revision > 1 ? cache.computeIfAbsent(revision - 1, r -> defectRevision(p, id, r)) : null;
        return HistoryDiff.fields(prev == null ? null : prev.fields(), cur.fields());
    }

    private Snapshot defectRevision(ProjectService.ProjectView p, UUID id, long no) {
        var rows = jdbc.query("""
                SELECT title, description, severity, priority, status, reason FROM defect_revision
                WHERE tenant_id=? AND project_id=? AND defect_id=? AND revision_no=?
                """, (rs, i) -> {
                    Map<String, String> f = new LinkedHashMap<>();
                    for (String c : List.of("title", "description", "severity", "priority", "status", "reason")) f.put(c, rs.getString(c));
                    return f;
                }, p.tenantId(), p.id(), id, no);
        return rows.isEmpty() ? null : new Snapshot(rows.get(0), List.of());
    }

    static String encodeCursor(Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeCursor(Row r) {
        return encodeCursor(r.at().toInstant(), r.id());
    }

    static Key decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) throw new IllegalArgumentException();
            return new Key(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw ProjectAccessException.invalid("Invalid history cursor");
        }
    }
}
