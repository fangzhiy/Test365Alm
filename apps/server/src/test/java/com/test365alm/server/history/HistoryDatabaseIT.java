package com.test365alm.server.history;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.defect.DefectService;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.history.HistoryDiff.Change;
import com.test365alm.server.project.*;
import com.test365alm.server.testcase.TestCaseService;

/** 真实 PostgreSQL + 受限 runtime 数据源：历史前后值还原、权限与分页。 */
@ActiveProfiles("integration") @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class HistoryDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("history_owned").withUsername("history_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");

    @DynamicPropertySource static void ds(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "test365alm_runtime");
        r.add("spring.datasource.password", () -> "r03_isolated_test_role_only");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }

    @Autowired HistoryService history;
    @Autowired ProjectService projects;
    @Autowired TestCaseService tests;
    @Autowired DefectService defects;
    @Autowired ExecutionService execution;

    @Test void testCaseCreateAndReviseShowFieldAndStepChanges() {
        var f = fixture();
        var first = tests.create(f.member, f.project, new TestCaseService.CreateCommand("MANUAL", "登录", "", "已注册",
                List.of(new TestCaseService.StepCommand(null, 1, "打开", "登录页"), new TestCaseService.StepCommand(null, 2, "提交", "成功"))), key());
        var s = first.currentRevision().steps();
        tests.appendRevision(f.member, f.project, first.id(), new TestCaseService.RevisionCommand("登录（改）", "", "已注册",
                List.of(new TestCaseService.StepCommand(s.get(1).stepKey(), 1, "提交", "跳转首页"),
                        new TestCaseService.StepCommand(null, 2, "退出", "回到登录页"))), "\"" + first.rowVersion() + "\"", key());

        var page = history.history(f.viewer, f.project, "test_case", first.id(), null, null);
        assertEquals(2, page.items().size());
        assertNull(page.nextCursor());
        var revised = page.items().get(0);
        assertEquals("test_case.revised", revised.action());
        assertEquals(2L, revised.revision());
        assertEquals(f.member, revised.actor().id());
        assertEquals("History member", revised.actor().displayName());
        assertEquals(List.of(new Change("title", "登录", "登录（改）"),
                new Change("steps[1].ordinal", "2", "1"), new Change("steps[1].expected", "成功", "跳转首页"),
                new Change("steps[2]", null, "退出 → 回到登录页"), new Change("steps[1]", "打开 → 登录页", null)), revised.changes());
        var created = page.items().get(1);
        assertEquals("test_case.created", created.action());
        assertEquals(List.of(new Change("title", null, "登录"), new Change("preconditions", null, "已注册"),
                new Change("steps[1]", null, "打开 → 登录页"), new Change("steps[2]", null, "提交 → 成功")), created.changes());
    }

    @Test void defectTransitionsShowStatusBeforeAndAfter() {
        var f = fixture();
        var d = defects.create(f.member, f.project, content("崩溃"), null, key());
        d = defects.transition(f.member, f.project, d.id(), "IN_PROGRESS", "排查中", "\"1\"", key());
        defects.transition(f.member, f.project, d.id(), "RESOLVED", "已修复", "\"2\"", key());

        var items = history.history(f.viewer, f.project, "defect", d.id(), null, null).items();
        assertEquals(List.of("defect.transitioned", "defect.transitioned", "defect.created"), items.stream().map(HistoryService.HistoryEntry::action).toList());
        assertEquals(List.of(new Change("status", "IN_PROGRESS", "RESOLVED"), new Change("reason", "排查中", "已修复")), items.get(0).changes());
        assertEquals(List.of(new Change("status", "OPEN", "IN_PROGRESS"), new Change("reason", null, "排查中")), items.get(1).changes());
        assertTrue(items.get(2).changes().contains(new Change("status", null, "OPEN")));
        assertTrue(items.get(2).changes().contains(new Change("title", null, "崩溃")));
    }

    @Test void runStepRecordPauseResumeFinishAndRerun() {
        var f = fixture();
        var tc = tests.create(f.member, f.project, new TestCaseService.CreateCommand("MANUAL", "运行", "", "",
                List.of(new TestCaseService.StepCommand(null, 1, "做", "好"))), key());
        var set = execution.createSet(f.member, f.project, "集", "", key());
        var instance = execution.addInstance(f.member, f.project, set.id(), tc.id(), tc.currentRevision().id(), key());
        var run = execution.createRun(f.member, f.project, instance.id(), key());
        UUID runId = run.run().id(), attemptId = run.currentAttempt().id();
        var step = run.currentAttempt().steps().get(0);
        var a = execution.saveStep(f.member, f.project, runId, attemptId, step.stepKey(), "坏了", "FAIL", step.rowVersion(), key());
        a = execution.pause(f.member, f.project, runId, attemptId, a.rowVersion(), key());
        a = execution.resume(f.member, f.project, runId, attemptId, a.rowVersion(), key());
        execution.finish(f.member, f.project, runId, attemptId, a.rowVersion(), key());
        execution.rerun(f.member, f.project, runId, key());

        var items = history.history(f.viewer, f.project, "run", runId, null, null).items();
        assertEquals(List.of("run.retried", "run.finished", "resumed", "paused", "step.recorded", "run.started"),
                items.stream().map(HistoryService.HistoryEntry::action).toList());
        assertEquals(List.of(new Change("attempt", "1", "2"), new Change("status", "FINISHED", "RUNNING")), items.get(0).changes());
        assertEquals(2L, items.get(0).revision());
        assertEquals(List.of(new Change("status", "RUNNING", "FINISHED"), new Change("conclusion", null, "FAIL")), items.get(1).changes());
        assertEquals(List.of(new Change("status", "PAUSED", "RUNNING")), items.get(2).changes());
        assertEquals(List.of(new Change("status", "RUNNING", "PAUSED")), items.get(3).changes());
        assertEquals(List.of(new Change("steps[1].actualResult", null, "坏了"), new Change("steps[1].conclusion", "NOT_RUN", "FAIL")), items.get(4).changes());
        assertEquals(1L, items.get(4).revision());
        assertEquals(List.of(new Change("status", null, "RUNNING")), items.get(5).changes());
        assertEquals("History member", items.get(5).actor().displayName());
    }

    @Test void outsidersForeignProjectsAndUnknownTypesAreRejected() {
        var f = fixture();
        var other = fixture();
        var d = defects.create(f.member, f.project, content("隐私"), null, key());
        // 非成员：404，不泄露对象是否存在
        assertEquals("NOT_FOUND", assertThrows(ProjectAccessException.class,
                () -> history.history(other.member, f.project, "defect", d.id(), null, null)).code());
        // 用别的项目的路径查：对象不在该项目 → 404
        assertEquals("NOT_FOUND", assertThrows(ProjectAccessException.class,
                () -> history.history(other.member, other.project, "defect", d.id(), null, null)).code());
        // 类型错配：缺陷 id 当用例查 → 404
        assertEquals("NOT_FOUND", assertThrows(ProjectAccessException.class,
                () -> history.history(f.member, f.project, "test_case", d.id(), null, null)).code());
        assertEquals(400, assertThrows(ProjectAccessException.class,
                () -> history.history(f.member, f.project, "requirement", d.id(), null, null)).status().value());
        assertEquals(400, assertThrows(ProjectAccessException.class,
                () -> history.history(f.member, f.project, "defect", d.id(), "garbage!", null)).status().value());
        // 撤销成员后失去访问
        projects.revokeMember(f.admin, f.project, f.member, 1L);
        assertEquals("NOT_FOUND", assertThrows(ProjectAccessException.class,
                () -> history.history(f.member, f.project, "defect", d.id(), null, null)).code());
    }

    @Test void cursorPagesAreContiguousWithoutDuplicatesOrGaps() {
        var f = fixture();
        var d = defects.create(f.member, f.project, content("分页 0"), null, key());
        for (int i = 1; i <= 6; i++) {
            var body = content("分页 " + i);
            d = defects.edit(f.member, f.project, d.id(), body, "\"" + d.rowVersion() + "\"", key());
        }
        List<UUID> seen = new ArrayList<>();
        List<Long> revisions = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = history.history(f.viewer, f.project, "defect", d.id(), cursor, 3);
            page.items().forEach(e -> { seen.add(e.id()); revisions.add(e.revision()); });
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(7, seen.size());
        assertEquals(7, new HashSet<>(seen).size());
        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), revisions);
        var all = history.history(f.viewer, f.project, "defect", d.id(), null, 100).items();
        assertEquals(seen, all.stream().map(HistoryService.HistoryEntry::id).toList());
    }

    private record Fixture(UUID admin, UUID member, UUID viewer, UUID tenant, UUID project) { }

    private Fixture fixture() {
        var o = owner();
        UUID admin = principal("History admin"), member = principal("History member"), viewer = principal("History viewer");
        UUID tenant = UUID.randomUUID(), domain = UUID.randomUUID();
        o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)", tenant, "hist-" + tenant, "History", admin);
        o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)", domain, tenant, "History");
        for (UUID p : List.of(admin, member, viewer))
            o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])", tenant, p, p.equals(admin) ? "{TENANT_ADMIN}" : "{MEMBER}");
        var project = projects.createProject(admin, tenant, domain, "HIST-" + UUID.randomUUID(), "History");
        projects.putMember(admin, project.id(), member, List.of("PROJECT_MEMBER"), 0L, false);
        projects.putMember(admin, project.id(), viewer, List.of("PROJECT_VIEWER"), 0L, false);
        return new Fixture(admin, member, viewer, tenant, project.id());
    }

    private UUID principal(String name) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)", id, "https://history.example.invalid", id.toString(), name);
        return id;
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    private static tools.jackson.databind.JsonNode content(String title) {
        return CoreCommands.JSON.readTree("{\"title\":\"" + title + "\",\"description\":\"复现\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}");
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
