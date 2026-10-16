package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariDataSource;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import tools.jackson.databind.json.JsonMapper;

/** Non-empty OLD schemas, real Flyway, then actual proxied services on runtime ONLY.
 * Each method owns a different disposable container. No application .env/config is loaded. */
@Timeout(180)
class ManualExecutionMigrationUpgradeIT {
    private static final String IMAGE = "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME = "test365alm_runtime";
    private static final String PASSWORD = "r03_isolated_test_role_only";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-01-02T03:04:05Z");
    private static final String OLD_KEY = "r07-original-operation";
    private static final List<String> TABLES = List.of("principal", "tenant", "domain", "tenant_member", "project", "project_member",
            "test_case", "test_case_number_allocator", "test_revision", "test_step", "test_set", "test_instance", "execution_manifest", "execution_manifest_step",
            "execution_run", "run_attempt", "run_step", "execution_event", "execution_outbox_event", "execution_idempotency", "audit_event");

    @Test void v13NonEmptyExecutionDatabaseUpgradesToV16() throws Exception { verifyUpgrade(13,16,false); }
    @Test void v14NonEmptyExecutionDatabaseUpgradesToV16() throws Exception { verifyUpgrade(14,16,false); }
    @Test void v16NonEmptyExecutionDatabaseUpgradesToV17WithoutLosingHistoryOrReplay() throws Exception { verifyUpgrade(14,17,true); }

    private void verifyUpgrade(int version,int target,boolean via16) throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("r07_owned_upgrade").withUsername("r07_owner").withPassword(UUID.randomUUID().toString())
                .withLabel("test365alm.r07.run", UUID.randomUUID().toString()).withInitScript("r03-test-role.sql")) {
            pg.start();
            Flyway old = flyway(pg, Integer.toString(version));
            assertEquals(version, old.migrate().migrationsExecuted);
            try (Connection owner = owner(pg)) {
                assertEquals(Integer.toString(version), scalar(owner, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"));
                assertEquals(version == 14 ? "1" : "0", scalar(owner, "SELECT count(*)::text FROM information_schema.columns WHERE table_name='execution_idempotency' AND column_name='response_json'"));
                Fixture f = seed(owner, version);
                Map<String, List<String>> before = snapshot(owner, version, null);
                assertEquals(4, before.get("execution_run").size());
                assertEquals(8, before.get("run_step").size());
                assertEquals(13, before.get("execution_event").size());
                assertEquals(13, before.get("execution_outbox_event").size());
                assertEquals(1, before.get("execution_idempotency").size());

                if(via16){
                    assertEquals(16-version,flyway(pg,"16").migrate().migrationsExecuted);
                    assertEquals(before,snapshot(owner,version,null));
                    assertEquals("16",scalar(owner,"SELECT max(version::int)::text FROM flyway_schema_history WHERE success"));
                }
                Flyway latest = flyway(pg, Integer.toString(target));
                assertEquals(target - (via16?16:version), latest.migrate().migrationsExecuted);
                assertEquals(before, snapshot(owner, version, null), "all pre-existing columns and rows must survive the upgrade");
                assertEquals(Integer.toString(target), scalar(owner, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"));
                assertEquals("4", scalar(owner, "SELECT count(*)::text FROM execution_manifest WHERE build_complete"));
                assertEquals("4", scalar(owner, "SELECT count(*)::text FROM run_attempt a JOIN execution_run r ON r.id=a.run_id WHERE a.manifest_id=r.manifest_id"));
                assertEquals("8", scalar(owner, "SELECT count(*)::text FROM run_step s JOIN run_attempt a ON a.id=s.attempt_id WHERE s.manifest_id=a.manifest_id"));
                assertEquals(0, latest.migrate().migrationsExecuted);
                assertEquals(before, snapshot(owner, version, null), "second migrate must not generate business rows");

                try (var pool = new HikariDataSource()) {
                    pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername(RUNTIME); pool.setPassword(PASSWORD);
                    pool.setMaximumPoolSize(1); pool.setConnectionTimeout(3000);
                    try (var context = new AnnotationConfigApplicationContext()) {
                        context.registerBean(DataSource.class, () -> pool);
                        context.register(RuntimeServices.class, ProjectService.class, ExecutionService.class);
                        context.refresh();
                        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(ExecutionService.class)), "real transaction advice required");
                        try (Connection runtime = pool.getConnection()) {
                            assertEquals(RUNTIME, scalar(runtime, "SELECT current_user"));
                            assertEquals("false:false:false", scalar(runtime, "SELECT rolsuper::text||':'||rolbypassrls::text||':'||rolcreatedb::text FROM pg_roles WHERE rolname=current_user"));
                            assertEquals("0", scalar(runtime, "SELECT count(*)::text FROM pg_tables WHERE schemaname='public' AND tableowner=current_user"));
                        }
                        verifyRuntime(pg, owner, context.getBean(ExecutionService.class), f, version);
                    }
                }
                System.out.printf("Upgrade V%d%s->V%d: PASS; original runs=4 attempts=4 steps=8 events=13 outbox=13 idempotency=1; runtime/history/terminal/replay/isolation verified%n", version,via16?"->V16":"",target);
            }
        }
    }

    private void verifyRuntime(PostgreSQLContainer<?> pg, Connection owner, ExecutionService service, Fixture f, int version) throws Exception {
        // Read each historical conclusion through the production service, not owner SQL.
        for (int i = 0; i < 3; i++) {
            Run old = f.runs().get(i);
            var detail = service.run(f.actor(), f.project(), old.run());
            assertEquals(old.run(), detail.run().id()); assertEquals(old.manifest(), detail.manifest().id());
            assertEquals(old.hash(), detail.manifest().snapshotHash());
            assertEquals(version == 13 ? "m09-manifest-1" : "m09-manifest-2", detail.manifest().formatVersion());
            assertEquals("m09-manual-results-1", detail.manifest().rulesVersion());
            assertEquals(f.testCase(), detail.manifest().sourceTestCaseId()); assertEquals(old.revision(), detail.manifest().sourceRevisionId());
            assertEquals("Historical case", detail.manifest().title()); assertEquals("original description", detail.manifest().description());
            assertEquals("original setup", detail.manifest().preconditions());
            var attempt = service.attempt(f.actor(), f.project(), old.run(), old.attempt());
            assertEquals(old.attempt(), attempt.id()); assertEquals(1, attempt.attemptNo()); assertEquals(4, attempt.rowVersion());
            assertEquals(old.result(), attempt.conclusion()); assertEquals(TIME.plusMinutes(1), attempt.finishedAt());
            assertEquals(List.of(f.steps().get(0), f.steps().get(1)), attempt.steps().stream().map(ExecutionService.RunStepView::stepKey).toList());
            assertEquals(List.of(1, 2), attempt.steps().stream().map(ExecutionService.RunStepView::ordinal).toList());
            assertEquals(List.of("Open", "Check"), attempt.steps().stream().map(ExecutionService.RunStepView::action).toList());
            assertEquals(List.of("Visible", "Correct"), attempt.steps().stream().map(ExecutionService.RunStepView::expected).toList());
            assertEquals("old actual 1", attempt.steps().get(0).actualResult()); assertEquals(2, attempt.steps().get(0).rowVersion());
        }
        var summary = service.summary(f.actor(), f.project(), f.set());
        assertEquals(new ExecutionService.RunSummary(4, 0, 1, 1, 1, 1), summary);

        Map<String, List<String>> replayBefore = snapshot(owner, version, null);
        Run first = f.runs().get(0);
        if (version == 13) {
            assertNull(scalar(owner, "SELECT response_json::text FROM execution_idempotency WHERE idempotency_key=?", OLD_KEY));
            assertError(409, "IDEMPOTENCY_RESPONSE_UNAVAILABLE", () -> service.createRun(f.actor(), f.project(), first.instance(), OLD_KEY));
            assertError(409, "IDEMPOTENCY_KEY_REUSED", () -> service.createRun(f.actor(), f.project(), f.runs().get(1).instance(), OLD_KEY));
        } else {
            var replay = service.finish(f.actor(), f.project(), first.run(), first.attempt(), 3, OLD_KEY);
            assertEquals(service.attempt(f.actor(), f.project(), first.run(), first.attempt()), replay);
            assertError(409, "IDEMPOTENCY_KEY_REUSED", () -> service.finish(f.actor(), f.project(), first.run(), first.attempt(), 2, OLD_KEY));
        }
        assertEquals(replayBefore, snapshot(owner, version, null), "old-key rejection/replay must have zero side effects");

        // Each negative SQL statement owns a fresh runtime transaction/context.
        denySql(pg, f, "UPDATE run_step SET actual_result='tamper', row_version=row_version+1 WHERE attempt_id=? AND step_key=?",
                "55006", "run step is not editable", first.attempt(), f.steps().get(0));
        denySql(pg, f, "UPDATE run_attempt SET status='RUNNING', conclusion=NULL, finished_at=NULL, row_version=row_version+1 WHERE id=?",
                "55006", "finished attempt is immutable", first.attempt());
        denySql(pg, f, "UPDATE execution_manifest SET build_complete=FALSE WHERE id=?", "55006", "execution manifest is immutable", first.manifest());
        denySql(pg, f, "INSERT INTO execution_event (tenant_id,project_id,run_id,attempt_id,event_type,actor_principal_id) VALUES (?,?,?,?,'STEP_RECORDED',?)",
                "55006", "finished attempt cannot receive step result events", f.tenant(), f.project(), first.run(), first.attempt(), f.actor());
        denySql(pg, f, "INSERT INTO execution_outbox_event (tenant_id,project_id,run_id,attempt_id,event_type) VALUES (?,?,?,?,'STEP_RECORDED')",
                "55006", "finished attempt cannot receive step result events", f.tenant(), f.project(), first.run(), first.attempt());
        assertEquals(replayBefore, snapshot(owner, version, null));

        Map<String,List<String>> completedBefore = completedHistory(owner, f);
        // Continue the real pre-upgrade active attempt; the application pool is never owner.
        Run active = f.runs().get(3);
        var running = service.run(f.actor(), f.project(), active.run()).currentAttempt();
        assertEquals("RUNNING", running.status()); assertEquals(1, running.rowVersion());
        assertEquals(List.of(1L, 1L), running.steps().stream().map(ExecutionService.RunStepView::rowVersion).toList());
        var saved = service.saveStep(f.actor(), f.project(), active.run(), active.attempt(), f.steps().get(0), "upgraded actual", "PASS", 1, "r07-new-step-key-1");
        Map<String, List<String>> savedState = snapshot(owner, version, null);
        assertEquals(saved, service.saveStep(f.actor(), f.project(), active.run(), active.attempt(), f.steps().get(0), "upgraded actual", "PASS", 1, "r07-new-step-key-1"));
        assertEquals(savedState, snapshot(owner, version, null), "new frozen replay must not duplicate events");
        saved = service.saveStep(f.actor(), f.project(), active.run(), active.attempt(), f.steps().get(1), "upgraded second", "PASS", 1, "r07-new-step-key-2");
        assertEquals(3, saved.rowVersion());
        var finished = service.finish(f.actor(), f.project(), active.run(), active.attempt(), 3, "r07-new-finish-key");
        assertEquals("PASS", finished.conclusion()); assertEquals(4, finished.rowVersion());
        assertEquals("1:1:1:1", scalar(owner, """
                SELECT (SELECT count(*) FROM execution_event WHERE attempt_id=? AND event_type='RUN_FINISHED')::text||':'||
                (SELECT count(*) FROM execution_outbox_event WHERE attempt_id=? AND event_type='RUN_FINISHED')::text||':'||
                (SELECT count(*) FROM audit_event WHERE object_id=? AND action='run.finished')::text||':'||
                (SELECT count(*) FROM execution_idempotency WHERE route=? AND idempotency_key='r07-new-finish-key')::text
                """, active.attempt(), active.attempt(), active.run(), "finish:"+active.attempt()));
        assertEquals("1", scalar(owner, """
                SELECT count(DISTINCT tx)::text FROM (
                  SELECT xmin::text AS tx FROM execution_event WHERE attempt_id=? AND event_type='RUN_FINISHED'
                  UNION ALL SELECT xmin::text FROM execution_outbox_event WHERE attempt_id=? AND event_type='RUN_FINISHED'
                  UNION ALL SELECT xmin::text FROM audit_event WHERE object_id=? AND action='run.finished'
                  UNION ALL SELECT xmin::text FROM execution_idempotency WHERE route=? AND idempotency_key='r07-new-finish-key'
                  UNION ALL SELECT xmin::text FROM run_attempt WHERE id=?
                ) writes
                """, active.attempt(),active.attempt(),active.run(),"finish:"+active.attempt(),active.attempt()), "finish data, audit, outbox and frozen response must share one transaction");
        assertEquals(new ExecutionService.RunSummary(4, 0, 0, 2, 1, 1), service.summary(f.actor(), f.project(), f.set()));
        assertEquals(completedBefore, completedHistory(owner, f), "full completed history, not just conclusions, must remain frozen");
        var newRun = service.createRun(f.actor(), f.project(), first.instance(), "r07-explicit-new-run");
        Map<String,List<String>> newRunBefore = snapshot(owner, version, null);
        assertEquals(newRun, service.createRun(f.actor(), f.project(), first.instance(), "r07-explicit-new-run"));
        assertEquals(newRunBefore, snapshot(owner, version, null));
        // None of these valid writes may modify the three completed histories.
        for (int i = 0; i < 3; i++) assertEquals(f.runs().get(i).result(), service.attempt(f.actor(), f.project(), f.runs().get(i).run(), f.runs().get(i).attempt()).conclusion());

        Map<String, List<String>> deniedBefore = snapshot(owner, version, null);
        assertError(403, "FORBIDDEN", () -> service.createSet(f.viewer(), f.project(), "denied", "", "r07-viewer-key"));
        assertError(403, "FORBIDDEN", () -> service.saveStep(f.viewer(),f.project(),newRun.run().id(),newRun.currentAttempt().id(),f.steps().get(0),"denied","PASS",1,"r07-viewer-step"));
        assertEquals(first.run(), service.run(f.viewer(), f.project(), first.run()).run().id());
        assertError(404, "NOT_FOUND", () -> service.run(f.actor(), f.otherProject(), first.run()));
        assertError(404, "NOT_FOUND", () -> service.run(f.actor(), f.foreignProject(), first.run()));
        try (Connection runtime = runtime(pg, f)) {
            assertEquals("0", scalar(runtime, "SELECT count(*)::text FROM test_set WHERE project_id<>?", f.project()));
            SQLException error = assertThrows(SQLException.class, () -> execute(runtime,
                    "INSERT INTO test_set(tenant_id,project_id,name,created_by) VALUES(?,?,'foreign',?)", f.foreignTenant(), f.foreignProject(), f.actor()));
            assertEquals("42501", error.getSQLState()); assertTrue(error.getMessage().contains("row-level security"));
        }
        assertEquals(deniedBefore, snapshot(owner, version, null));
        execute(owner, "UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP WHERE project_id=? AND principal_id=?", f.project(), f.actor());
        Map<String, List<String>> revokedBefore = snapshot(owner, version, null);
        assertError(404, "NOT_FOUND", () -> service.run(f.actor(), f.project(), first.run()));
        try(Connection runtime=runtime(pg,f)) { assertEquals("0",scalar(runtime,"SELECT count(*)::text FROM execution_run WHERE project_id=?",f.project())); }
        if (version == 13) assertError(404, "NOT_FOUND", () -> service.createRun(f.actor(), f.project(), first.instance(), OLD_KEY));
        else assertError(404, "NOT_FOUND", () -> service.finish(f.actor(), f.project(), first.run(), first.attempt(), 3, OLD_KEY));
        assertEquals(revokedBefore, snapshot(owner, version, null));
    }

    /** Owner writes an OLD-format fixture only; never disables triggers or RLS. */
    private Fixture seed(Connection c, int version) throws Exception {
        UUID actor=UUID.randomUUID(), viewer=UUID.randomUUID(), tenant=UUID.randomUUID(), domain=UUID.randomUUID(), project=UUID.randomUUID();
        UUID other=UUID.randomUUID(), foreignTenant=UUID.randomUUID(), foreignDomain=UUID.randomUUID(), foreign=UUID.randomUUID();
        for (UUID id : List.of(actor, viewer)) execute(c, "INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,'https://r07.example.invalid',?, 'R07 synthetic')", id, id.toString());
        for (UUID id : List.of(tenant, foreignTenant)) execute(c, "INSERT INTO tenant(id,code,name,created_by) VALUES(?,?, 'R07',?)", id, id.toString(), actor);
        execute(c, "INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'local'),(?,?,'foreign')", domain, tenant, foreignDomain, foreignTenant);
        for (UUID id : List.of(actor, viewer)) execute(c, "INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,ARRAY['MEMBER'])", tenant, id);
        execute(c, "INSERT INTO project(id,tenant_id,domain_id,code,name,created_by) VALUES(?,?,?,?,'Authorized',?),(?,?,?,?,'Not joined',?),(?,?,?,?,'Foreign',?)",
                project,tenant,domain,project.toString(),actor,other,tenant,domain,other.toString(),actor,foreign,foreignTenant,foreignDomain,foreign.toString(),actor);
        execute(c, "INSERT INTO project_member(tenant_id,project_id,principal_id,roles) VALUES(?,?,?,ARRAY['PROJECT_MEMBER']),(?,?,?,ARRAY['PROJECT_VIEWER'])", tenant,project,actor,tenant,project,viewer);
        UUID test=UUID.randomUUID(), revision=UUID.randomUUID(), set=UUID.randomUUID();
        List<UUID> steps=List.of(UUID.randomUUID(),UUID.randomUUID());
        execute(c, "INSERT INTO test_case(tenant_id,project_id,id,display_number,test_type,created_by) VALUES(?,?,?,1,'MANUAL',?)",tenant,project,test,actor);
        execute(c, "INSERT INTO test_case_number_allocator(tenant_id,project_id,next_number) VALUES(?,?,2)",tenant,project);
        execute(c, "INSERT INTO test_revision(tenant_id,project_id,test_case_id,id,revision_no,title,description,preconditions,created_by) VALUES(?,?,?,?,1,'Historical case','original description','original setup',?)",tenant,project,test,revision,actor);
        for(int s=0;s<2;s++) execute(c,"INSERT INTO test_step(tenant_id,project_id,test_case_id,revision_id,step_key,ordinal,action,expected) VALUES(?,?,?,?,?,?,?,?)",tenant,project,test,revision,steps.get(s),s+1,s==0?"Open":"Check",s==0?"Visible":"Correct");
        execute(c,"UPDATE test_revision SET sealed_at=? WHERE id=?",TIME,revision);
        execute(c,"UPDATE test_case SET current_revision_id=? WHERE id=?",revision,test);
        execute(c,"INSERT INTO test_set(tenant_id,project_id,id,name,created_by) VALUES(?,?,?,'Historical set',?)",tenant,project,set,actor);
        execute(c,"INSERT INTO test_set(tenant_id,project_id,name,created_by) VALUES(?,?,'Other sentinel',?),(?,?,'Foreign sentinel',?)",tenant,other,actor,foreignTenant,foreign,actor);
        List<Run> runs=new ArrayList<>();
        execute(c,"SELECT set_config('test365alm.execution_building','true',false)");
        for(int i=0;i<4;i++) {
            UUID instance=UUID.randomUUID(), manifest=UUID.randomUUID(), run=UUID.randomUUID(), attempt=UUID.randomUUID();
            String result=i==0?"FAIL":i==1?"PASS":i==2?"BLOCKED":null;
            // A distinct test set is unnecessary: the instance unique constraint requires distinct revisions.
            UUID sourceRevision=revision;
            if(i>0) {
                sourceRevision=UUID.randomUUID();
                execute(c,"INSERT INTO test_revision(tenant_id,project_id,test_case_id,id,revision_no,title,description,preconditions,created_by) VALUES(?,?,?,?,?,'Historical case','original description','original setup',?)",tenant,project,test,sourceRevision,i+1,actor);
                for(int s=0;s<2;s++) execute(c,"INSERT INTO test_step(tenant_id,project_id,test_case_id,revision_id,step_key,ordinal,action,expected) VALUES(?,?,?,?,?,?,?,?)",tenant,project,test,sourceRevision,steps.get(s),s+1,s==0?"Open":"Check",s==0?"Visible":"Correct");
                execute(c,"UPDATE test_revision SET sealed_at=? WHERE id=?",TIME,sourceRevision);
            }
            execute(c,"INSERT INTO test_instance(tenant_id,project_id,id,test_set_id,test_case_id,test_revision_id,display_order,created_by) VALUES(?,?,?,?,?,?,?,?)",tenant,project,instance,set,test,sourceRevision,i+1,actor);
            String hash=manifestHash(version,test,sourceRevision,i+1,steps);
            execute(c,"INSERT INTO execution_manifest(tenant_id,project_id,id,test_instance_id,source_test_case_id,source_revision_id,source_revision_no,title,description,preconditions,format_version,rules_version,snapshot_hash) VALUES(?,?,?,?,?,?,?,'Historical case','original description','original setup',?,'m09-manual-results-1',?)",tenant,project,manifest,instance,test,sourceRevision,i+1,version==13?"m09-manifest-1":"m09-manifest-2",hash);
            for(int s=0;s<2;s++) execute(c,"INSERT INTO execution_manifest_step(tenant_id,project_id,manifest_id,step_key,ordinal,action,expected) VALUES(?,?,?,?,?,?,?)",tenant,project,manifest,steps.get(s),s+1,s==0?"Open":"Check",s==0?"Visible":"Correct");
            if(version==14) execute(c,"UPDATE execution_manifest SET build_complete=TRUE WHERE id=?",manifest);
            execute(c,"INSERT INTO execution_run(tenant_id,project_id,id,test_instance_id,manifest_id,status,row_version,created_by) VALUES(?,?,?,?,?,'RUNNING',1,?)",tenant,project,run,instance,manifest,actor);
            if(version==13) execute(c,"INSERT INTO run_attempt(tenant_id,project_id,id,run_id,attempt_no,status,conclusion,row_version,started_by,started_at,finished_at) VALUES(?,?,?,?,1,?,?,?,?,?,?)",tenant,project,attempt,run,result==null?"RUNNING":"FINISHED",result,result==null?1:4,actor,TIME,result==null?null:TIME.plusMinutes(1));
            else execute(c,"INSERT INTO run_attempt(tenant_id,project_id,id,run_id,manifest_id,attempt_no,started_by,started_at) VALUES(?,?,?,?,?,1,?,?)",tenant,project,attempt,run,manifest,actor,TIME);
            for(int s=0;s<2;s++) {
                String sql="INSERT INTO run_step(tenant_id,project_id,attempt_id,step_key,ordinal,action,expected,actual_result,conclusion,row_version,updated_by"+(version==14?",manifest_id":"")+") VALUES(?,?,?,?,?,?,?,?,?,?,?"+(version==14?",?":"")+")";
                List<Object> args=new ArrayList<>(List.of(tenant,project,attempt,steps.get(s),s+1,s==0?"Open":"Check",s==0?"Visible":"Correct"));
                args.add(result==null?"":"old actual "+(s+1)); args.add(result==null?"NOT_RUN":result); args.add(result==null?1:2); args.add(result==null?null:actor);
                if(version==14) args.add(manifest);
                execute(c,sql,args.toArray());
            }
            if(result!=null && version==14) {
                execute(c,"UPDATE run_attempt SET row_version=row_version+1 WHERE id=?",attempt);
                execute(c,"UPDATE run_attempt SET row_version=row_version+1 WHERE id=?",attempt);
                execute(c,"UPDATE run_attempt SET status='FINISHED',conclusion=?,row_version=row_version+1,finished_at=? WHERE id=?",result,TIME.plusMinutes(1),attempt);
            }
            if(result!=null) execute(c,"UPDATE execution_run SET status='FINISHED',row_version=2 WHERE id=?",run);
            for(String event : result==null?List.of("RUN_STARTED"):List.of("RUN_STARTED","STEP_RECORDED","STEP_RECORDED","RUN_FINISHED")) {
                execute(c,"INSERT INTO execution_event(tenant_id,project_id,run_id,attempt_id,event_type,actor_principal_id,payload,occurred_at) VALUES(?,?,?,?,?,?,?::jsonb,?)",tenant,project,run,attempt,event,actor,"{\"oldVersion\":"+version+"}",TIME);
                execute(c,"INSERT INTO execution_outbox_event(tenant_id,project_id,run_id,attempt_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?,?::jsonb,?)",tenant,project,run,attempt,event,"{\"oldVersion\":"+version+"}",TIME);
                execute(c,"INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision) VALUES(?,?,?,?,'run',?,0)",tenant,project,actor,event.toLowerCase().replace('_','.'),run);
            }
            runs.add(new Run(instance,manifest,run,attempt,hash,result,sourceRevision));
        }
        execute(c,"UPDATE test_case SET current_revision_id=?,row_version=4 WHERE id=?",runs.get(3).revision(),test);
        Run first=runs.get(0);
        if(version==13) execute(c,"INSERT INTO execution_idempotency(tenant_id,project_id,principal_id,route,idempotency_key,request_hash,object_id,object_type) VALUES(?,?,?,'run:create',?,?,?,'RUN')",tenant,project,actor,OLD_KEY,oldHash("run",first.instance().toString()),first.run());
        else {
            List<ExecutionService.RunStepView> frozenSteps=new ArrayList<>();
            try(var q=c.prepareStatement("SELECT step_key,ordinal,action,expected,actual_result,conclusion,row_version,updated_by,updated_at FROM run_step WHERE attempt_id=? ORDER BY ordinal")) {
                q.setObject(1,first.attempt()); try(var rows=q.executeQuery()) {while(rows.next()) frozenSteps.add(new ExecutionService.RunStepView(rows.getObject(1,UUID.class),rows.getInt(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getLong(7),rows.getObject(8,UUID.class),rows.getObject(9,OffsetDateTime.class)));}
            }
            var frozen=new ExecutionService.AttemptView(first.attempt(),first.run(),1,"FINISHED","FAIL",4,actor,TIME,TIME.plusMinutes(1),frozenSteps);
            String response=JsonMapper.builder().findAndAddModules().build().writeValueAsString(frozen);
            execute(c,"INSERT INTO execution_idempotency(tenant_id,project_id,principal_id,route,idempotency_key,request_hash,object_id,object_type,response_json) VALUES(?,?,?,?,?,?,?,'ATTEMPT',?::jsonb)",tenant,project,actor,"finish:"+first.attempt(),OLD_KEY,oldHash("finish",first.run().toString(),first.attempt().toString(),"3"),first.attempt(),response);
        }
        return new Fixture(tenant,project,actor,viewer,other,foreignTenant,foreign,test,revision,set,steps,runs);
    }

    // V13 4c3636d's actual length encoding, preserved independently of production helpers.
    private static String oldHash(String... fields) throws Exception {
        StringBuilder text=new StringBuilder();
        for(String field:fields) text.append(field==null?"N;":"V"+field.length()+":"+field+";");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static String manifestHash(int version, UUID test, UUID revision, int no, List<UUID> steps) throws Exception {
        if(version==13) return oldHash("manifest",revision.toString(),"Historical case","original description","original setup",
                List.of(steps.get(0)+"|1|Open|Visible",steps.get(1)+"|2|Check|Correct").toString());
        return oldHash("m09-manifest-2","m09-manual-results-1",test.toString(),revision.toString(),Integer.toString(no),"Historical case","original description","original setup","2",steps.get(0).toString(),"1","Open","Visible",steps.get(1).toString(),"2","Check","Correct");
    }
    private static Map<String,List<String>> snapshot(Connection c,int oldVersion,UUID unused) throws SQLException {
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(String table:TABLES) {
            String projection="to_jsonb(t)";
            if(oldVersion==13) projection+=switch(table) {
                case "execution_manifest" -> "-'build_complete'";
                case "run_attempt","run_step" -> "-'manifest_id'";
                case "execution_event","execution_outbox_event" -> "-'object_type'-'object_id'";
                case "execution_idempotency" -> "-'response_json'";
                default -> "";
            };
            List<String> rows=new ArrayList<>();
            try(var q=c.createStatement(); var rs=q.executeQuery("SELECT ("+projection+")::text AS snapshot FROM "+table+" t ORDER BY snapshot")) {while(rs.next()) rows.add(rs.getString(1));}
            result.put(table,rows);
        }
        return result;
    }
    private static Map<String,List<String>> completedHistory(Connection c, Fixture f) throws SQLException {
        Map<String,List<String>> result=new LinkedHashMap<>();
        UUID active=f.runs().get(3).run(), attempt=f.runs().get(3).attempt();
        for(String table:List.of("execution_manifest","execution_manifest_step","execution_run","run_attempt","run_step","execution_event","execution_outbox_event","execution_idempotency")) {
            String condition=switch(table) {
                case "execution_run" -> "id<>?";
                case "run_attempt","execution_event","execution_outbox_event" -> "run_id<>?";
                case "run_step" -> "attempt_id<>?";
                case "execution_idempotency" -> "idempotency_key=?";
                default -> "TRUE";
            };
            List<String> rows=new ArrayList<>();
            try(var q=c.prepareStatement("SELECT to_jsonb(t)::text AS snapshot FROM "+table+" t WHERE "+condition+" ORDER BY snapshot")) {
                if(!condition.equals("TRUE")) q.setObject(1,table.equals("run_step")?attempt:table.equals("execution_idempotency")?OLD_KEY:active);
                try(var rs=q.executeQuery()){while(rs.next()) rows.add(rs.getString(1));}
            } result.put(table,rows);
        } return result;
    }
    private static Flyway flyway(PostgreSQLContainer<?> pg,String target) {
        var config=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).locations("classpath:db/migration");
        if(target!=null) config.target(target); return config.load();
    }
    private static Connection owner(PostgreSQLContainer<?> pg) throws SQLException {return DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());}
    private static Connection runtime(PostgreSQLContainer<?> pg,Fixture f) throws SQLException {
        Connection c=DriverManager.getConnection(pg.getJdbcUrl(),RUNTIME,PASSWORD);
        try(var q=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,false),set_config('test365alm.project_id',?,false),set_config('test365alm.principal_id',?,false)")) {
            q.setString(1,f.tenant().toString());q.setString(2,f.project().toString());q.setString(3,f.actor().toString());q.executeQuery().close();
        } return c;
    }
    private static void denySql(PostgreSQLContainer<?> pg,Fixture f,String sql,String state,String message,Object...args) throws Exception {
        try(Connection c=runtime(pg,f)) { SQLException error=assertThrows(SQLException.class,()->execute(c,sql,args)); assertEquals(state,error.getSQLState());assertTrue(error.getMessage().contains(message),error.getMessage()); }
    }
    private static void assertError(int status,String code,org.junit.jupiter.api.function.Executable action) {
        ProjectAccessException error=assertThrows(ProjectAccessException.class,action);assertEquals(status,error.status().value());assertEquals(code,error.code());
    }
    private static String scalar(Connection c,String sql,Object...args) throws SQLException {
        try(var q=c.prepareStatement(sql)) {for(int i=0;i<args.length;i++) q.setObject(i+1,args[i]);try(var rs=q.executeQuery()){assertTrue(rs.next());return rs.getString(1);}}
    }
    private static void execute(Connection c,String sql,Object...args) throws SQLException {
        try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++) q.setObject(i+1,args[i]);q.execute();}
    }
    @Configuration @EnableTransactionManagement
    static class RuntimeServices {
        @Bean JdbcTemplate jdbcTemplate(DataSource source){return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
    }
    private record Run(UUID instance,UUID manifest,UUID run,UUID attempt,String hash,String result,UUID revision){}
    private record Fixture(UUID tenant,UUID project,UUID actor,UUID viewer,UUID otherProject,UUID foreignTenant,UUID foreignProject,UUID testCase,UUID revision,UUID set,List<UUID> steps,List<Run> runs){}
}
