package com.test365alm.server.history;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** 纯函数字段对比：不访问数据库，便于单元测试。 */
public final class HistoryDiff {
    private HistoryDiff() { }

    /** 一个字段的前后值；before 为 null 表示新增，after 为 null 表示删除。 */
    public record Change(String field, String before, String after) { }

    /** 用例步骤快照；stepKey 在修订之间保持稳定，用来识别同一步骤。 */
    public record Step(UUID stepKey, int ordinal, String action, String expected) { }

    /**
     * 按 after 的字段顺序对比两组简单字段。before 为 null 表示首版（没有旧值）。
     * 空字符串与 null 视为相同，首版中的空字段不输出。
     */
    public static List<Change> fields(Map<String, String> before, Map<String, String> after) {
        List<Change> changes = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(after.keySet());
        if (before != null) names.addAll(before.keySet());
        for (String name : names) {
            String b = before == null ? null : blankToNull(before.get(name));
            String a = blankToNull(after.get(name));
            if (!Objects.equals(b, a)) changes.add(new Change(name, b, a));
        }
        return changes;
    }

    /**
     * 对比两版步骤：按 stepKey 匹配，输出新增、删除、动作/预期修改与调序。
     * 字段名形如 steps[3]、steps[3].action、steps[3].expected、steps[3].ordinal，序号取新版（删除时取旧版）。
     */
    public static List<Change> steps(List<Step> before, List<Step> after) {
        List<Change> changes = new ArrayList<>();
        Map<UUID, Step> old = new LinkedHashMap<>();
        for (Step s : before) old.put(s.stepKey(), s);
        Set<UUID> kept = new HashSet<>();
        List<Step> ordered = new ArrayList<>(after);
        ordered.sort(Comparator.comparingInt(Step::ordinal));
        for (Step s : ordered) {
            Step prior = old.get(s.stepKey());
            String base = "steps[" + s.ordinal() + "]";
            if (prior == null) {
                changes.add(new Change(base, null, describe(s)));
                continue;
            }
            kept.add(s.stepKey());
            if (prior.ordinal() != s.ordinal()) {
                changes.add(new Change(base + ".ordinal", Integer.toString(prior.ordinal()), Integer.toString(s.ordinal())));
            }
            if (!Objects.equals(prior.action(), s.action())) changes.add(new Change(base + ".action", prior.action(), s.action()));
            if (!Objects.equals(prior.expected(), s.expected())) changes.add(new Change(base + ".expected", prior.expected(), s.expected()));
        }
        List<Step> removed = new ArrayList<>(old.values());
        removed.removeIf(s -> kept.contains(s.stepKey()));
        removed.sort(Comparator.comparingInt(Step::ordinal));
        for (Step s : removed) changes.add(new Change("steps[" + s.ordinal() + "]", describe(s), null));
        return changes;
    }

    /**
     * 把一条 execution_event 的 payload 展开成前后值。stepOrdinal 是 STEP_RECORDED 对应步骤的序号，查不到时为 null。
     * 旧数据 payload 可能是空对象，此时只返回能确定的部分。
     */
    public static List<Change> run(String eventType, JsonNode payload, Integer stepOrdinal) {
        List<Change> changes = new ArrayList<>();
        switch (eventType) {
            case "RUN_STARTED" -> add(changes, "status", null, text(payload, "afterStatus"));
            case "STEP_RECORDED" -> {
                String base = "steps[" + (stepOrdinal == null ? "?" : stepOrdinal) + "]";
                add(changes, base + ".actualResult", text(payload, "beforeActualResult"), text(payload, "afterActualResult"));
                add(changes, base + ".conclusion", text(payload, "beforeConclusion"), text(payload, "afterConclusion"));
            }
            case "PAUSED", "RESUMED" -> add(changes, "status", text(payload, "beforeStatus"), text(payload, "afterStatus"));
            case "RUN_FINISHED" -> {
                add(changes, "status", text(payload, "beforeStatus"), text(payload, "afterStatus"));
                add(changes, "conclusion", null, text(payload, "conclusion"));
            }
            case "RUN_RETRIED" -> {
                String no = text(payload, "attemptNo");
                String prior = null;
                if (no != null) {
                    try { prior = Long.toString(Long.parseLong(no) - 1); } catch (NumberFormatException ignored) { prior = null; }
                }
                add(changes, "attempt", prior, no);
                add(changes, "status", "FINISHED", "RUNNING");
            }
            default -> { }
        }
        return changes;
    }

    private static void add(List<Change> changes, String field, String before, String after) {
        String b = blankToNull(before), a = blankToNull(after);
        if (!Objects.equals(b, a)) changes.add(new Change(field, b, a));
    }

    private static String text(JsonNode payload, String name) {
        JsonNode n = payload == null ? null : payload.get(name);
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        return n.isTextual() ? n.textValue() : n.toString();
    }

    /** 新增/删除整步时用一行文字表示内容。 */
    static String describe(Step s) {
        return s.action() + " → " + s.expected();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
