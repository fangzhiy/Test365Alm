package com.test365alm.server.history;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.history.HistoryDiff.Change;
import com.test365alm.server.history.HistoryDiff.Step;
import com.test365alm.server.project.ProjectAccessException;

class HistoryDiffTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test void firstVersionHasNoBeforeValuesAndSkipsEmptyFields() {
        var changes = HistoryDiff.fields(null, map("title", "登录", "description", "", "preconditions", null));
        assertEquals(List.of(new Change("title", null, "登录")), changes);
    }

    @Test void onlyChangedFieldsAreReportedInOrder() {
        var changes = HistoryDiff.fields(map("title", "A", "status", "OPEN", "reason", ""),
                map("title", "A", "status", "RESOLVED", "reason", "fixed"));
        assertEquals(List.of(new Change("status", "OPEN", "RESOLVED"), new Change("reason", null, "fixed")), changes);
    }

    @Test void stepsAddedRemovedEditedAndReordered() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        var before = List.of(new Step(a, 1, "打开", "页面"), new Step(b, 2, "输入", "成功"), new Step(c, 3, "删除我", "无"));
        // b 移到第 1 步且预期改变；a 移到第 2 步；c 被删除；d 新增
        var after = List.of(new Step(b, 1, "输入", "登录成功"), new Step(a, 2, "打开", "页面"), new Step(d, 3, "退出", "回到首页"));
        var changes = HistoryDiff.steps(before, after);
        assertEquals(List.of(
                new Change("steps[1].ordinal", "2", "1"),
                new Change("steps[1].expected", "成功", "登录成功"),
                new Change("steps[2].ordinal", "1", "2"),
                new Change("steps[3]", null, "退出 → 回到首页"),
                new Change("steps[3]", "删除我 → 无", null)), changes);
    }

    @Test void unchangedStepsProduceNothingAndFirstVersionAddsAll() {
        UUID a = UUID.randomUUID();
        var steps = List.of(new Step(a, 1, "x", "y"));
        assertTrue(HistoryDiff.steps(steps, steps).isEmpty());
        assertEquals(List.of(new Change("steps[1]", null, "x → y")), HistoryDiff.steps(List.of(), steps));
    }

    @Test void runEventsExpandPayloadBeforeAndAfter() {
        var step = CoreCommands.JSON.readTree("{\"beforeActualResult\":\"\",\"afterActualResult\":\"报错\",\"beforeConclusion\":\"NOT_RUN\",\"afterConclusion\":\"FAIL\"}");
        assertEquals(List.of(new Change("steps[2].actualResult", null, "报错"), new Change("steps[2].conclusion", "NOT_RUN", "FAIL")),
                HistoryDiff.run("STEP_RECORDED", step, 2));
        var finish = CoreCommands.JSON.readTree("{\"beforeStatus\":\"RUNNING\",\"afterStatus\":\"FINISHED\",\"conclusion\":\"PASS\"}");
        assertEquals(List.of(new Change("status", "RUNNING", "FINISHED"), new Change("conclusion", null, "PASS")),
                HistoryDiff.run("RUN_FINISHED", finish, null));
        assertEquals(List.of(new Change("status", null, "RUNNING")),
                HistoryDiff.run("RUN_STARTED", CoreCommands.JSON.readTree("{\"afterStatus\":\"RUNNING\"}"), null));
        assertEquals(List.of(new Change("attempt", "1", "2"), new Change("status", "FINISHED", "RUNNING")),
                HistoryDiff.run("RUN_RETRIED", CoreCommands.JSON.readTree("{\"attemptNo\":2}"), null));
        assertEquals(List.of(new Change("status", "RUNNING", "PAUSED")),
                HistoryDiff.run("PAUSED", CoreCommands.JSON.readTree("{\"beforeStatus\":\"RUNNING\",\"afterStatus\":\"PAUSED\"}"), null));
        // 旧数据的空 payload 不报错
        assertTrue(HistoryDiff.run("PAUSED", CoreCommands.JSON.readTree("{}"), null).isEmpty());
        assertTrue(HistoryDiff.run("UNKNOWN", null, null).isEmpty());
    }

    @Test void cursorRoundTripsAndRejectsGarbage() {
        Instant at = Instant.parse("2026-11-10T03:04:05.123456Z");
        UUID id = UUID.randomUUID();
        var key = HistoryService.decodeCursor(HistoryService.encodeCursor(at, id));
        assertEquals(at, key.at());
        assertEquals(id, key.id());
        assertNull(HistoryService.decodeCursor(null));
        for (String bad : List.of("not-base64!", "Zm9v", Base64.getUrlEncoder().encodeToString("x|y".getBytes())))
            assertThrows(ProjectAccessException.class, () -> HistoryService.decodeCursor(bad));
    }
}
