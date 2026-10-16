package com.test365alm.server.release;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.trace.TraceLinkService;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.defect.DefectService;

/** Owner creates disposable fixtures and verifies committed state; every business command uses runtime. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class ReleaseCycleDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("r10_owned_release").withUsername("r10_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void ds(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size",()->"3");
    }
    @Autowired ReleaseService releases;
    @Autowired ProjectService projects;
    @Autowired RequirementService requirements;
    @Autowired TestCaseService tests;
    @Autowired TraceLinkService trace;
    @Autowired ExecutionService execution;
    @Autowired DefectService defects;
    @Autowired JdbcTemplate runtime;

    @Test void adminCreatesReleaseCycleAndFrozenIdempotentResultsWithAtomicEvents() {
        var f=fixture();assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(releases));
        assertEquals("test365alm_runtime",runtime.queryForObject("SELECT current_user",String.class));
        String createKey=key();var first=releases.create(f.admin,f.project,releaseBody("First"),createKey);
        assertEquals(first,releases.create(f.admin,f.project,releaseBody("First"),createKey));
        assertEquals(1,first.displayNumber());assertEquals(1,first.rowVersion());assertEquals(0,first.scopeVersion());assertEquals("PLANNED",first.status());
        assertEquals(19,owner().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class),"empty isolated database must migrate through V19");
        String cycleKey=key();var cycle=releases.createCycle(f.admin,f.project,first.id(),cycleBody("Cycle"),cycleKey);
        assertEquals(cycle,releases.createCycle(f.admin,f.project,first.id(),cycleBody("Cycle"),cycleKey));
        var edited=releases.edit(f.admin,f.project,first.id(),json("{\"name\":\"Edited\"}"),tag(1),key());
        assertEquals(2,edited.rowVersion());assertEquals("Edited",releases.get(f.viewer,f.project,first.id()).name());
        assertEquals(first,releases.create(f.admin,f.project,releaseBody("First"),createKey),"replay must not substitute mutable current release");
        var changed=releases.editCycle(f.admin,f.project,first.id(),cycle.id(),json("{\"name\":\"Edited cycle\"}"),tag(1),key());
        assertEquals(2,changed.rowVersion());assertEquals(cycle,releases.createCycle(f.admin,f.project,first.id(),cycleBody("Cycle"),cycleKey));
        assertEquals(4,count("release_outbox_event",f.project));
        assertEquals(4,owner().queryForObject("SELECT count(*) FROM audit_event WHERE project_id=? AND action IN ('RELEASE_CREATED','RELEASE_UPDATED','CYCLE_CREATED','CYCLE_UPDATED')",Integer.class,f.project));
        assertEquals(4,count("core_command_idempotency",f.project));
        var before=snapshot(f.project);
        denied(409,"IDEMPOTENCY_KEY_REUSED",()->releases.create(f.admin,f.project,releaseBody("Other"),createKey));
        denied(409,"IDEMPOTENCY_KEY_REUSED",()->releases.createCycle(f.admin,f.project,first.id(),cycleBody("Other"),cycleKey));
        assertEquals(before,snapshot(f.project));
    }

    @Test void datesRejectInvalidCyclesAndParentShrinkWithoutSideEffects() {
        var f=fixture();var r=releases.create(f.admin,f.project,releaseBody("Dates"),key());
        var cycle=releases.createCycle(f.admin,f.project,r.id(),cycleBody("Inside"),key());var before=snapshot(f.project);
        denied(422,"CYCLE_OUTSIDE_RELEASE",()->releases.createCycle(f.admin,f.project,r.id(),json("{\"name\":\"Outside\",\"startDate\":\"2026-09-30\",\"endDate\":\"2026-10-20\"}"),key()));
        denied(422,"CYCLE_OUTSIDE_RELEASE",()->releases.edit(f.admin,f.project,r.id(),json("{\"endDate\":\"2026-10-12\"}"),tag(1),key()));
        denied(422,"CYCLE_OUTSIDE_RELEASE",()->releases.editCycle(f.admin,f.project,r.id(),cycle.id(),json("{\"endDate\":\"2026-11-01\"}"),tag(1),key()));
        denied(422,"VALIDATION_FAILED",()->releases.create(f.admin,f.project,json("{\"name\":\"Bad date\",\"startDate\":\"2026-02-30\",\"endDate\":\"2026-10-31\"}"),key()));
        denied(422,"VALIDATION_FAILED",()->releases.editCycle(f.admin,f.project,r.id(),cycle.id(),json("{\"startDate\":\"2026-10-25\"}"),tag(1),key()));
        assertEquals(before,snapshot(f.project));assertEquals(cycle,releases.getCycle(f.member,f.project,r.id(),cycle.id()));
        var exact=releases.editCycle(f.admin,f.project,r.id(),cycle.id(),json("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-31\"}"),tag(1),key());
        assertEquals("2026-10-01",exact.startDate().toString());assertEquals("2026-10-31",exact.endDate().toString());
    }

    @Test void archivedReleaseAndClosedCycleRejectIllegalWritesAndTransitions() {
        var f=fixture();var r=releases.create(f.admin,f.project,releaseBody("State"),key());var cycle=releases.createCycle(f.admin,f.project,r.id(),cycleBody("Cycle"),key());
        var req=requirement(f);var s=releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());
        releases.editCycle(f.admin,f.project,r.id(),cycle.id(),json("{\"status\":\"CLOSED\"}"),tag(1),key());var before=snapshot(f.project);
        denied(409,"CYCLE_READ_ONLY",()->releases.editCycle(f.admin,f.project,r.id(),cycle.id(),json("{\"name\":\"closed edit\"}"),tag(2),key()));
        denied(409,"CYCLE_READ_ONLY",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),cycle.id()),key()));
        assertEquals(before,snapshot(f.project));
        releases.edit(f.admin,f.project,r.id(),json("{\"status\":\"ACTIVE\"}"),tag(1),key());before=snapshot(f.project);
        denied(409,"INVALID_TRANSITION",()->releases.edit(f.admin,f.project,r.id(),json("{\"status\":\"PLANNED\"}"),tag(2),key()));assertEquals(before,snapshot(f.project));
        releases.edit(f.admin,f.project,r.id(),json("{\"status\":\"CLOSED\"}"),tag(2),key());
        denied(409,"RELEASE_READ_ONLY",()->releases.edit(f.admin,f.project,r.id(),json("{\"name\":\"closed edit\"}"),tag(3),key()));
        releases.edit(f.admin,f.project,r.id(),json("{\"status\":\"ARCHIVED\"}"),tag(3),key());before=snapshot(f.project);
        denied(409,"RELEASE_READ_ONLY",()->releases.createCycle(f.admin,f.project,r.id(),cycleBody("Late"),key()));
        denied(409,"RELEASE_READ_ONLY",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key()));
        denied(409,"RELEASE_READ_ONLY",()->releases.removeScope(f.admin,f.project,r.id(),s.id(),key()));
        denied(409,"RELEASE_READ_ONLY",()->releases.evaluate(f.admin,f.project,r.id(),key()));
        assertEquals(before,snapshot(f.project));assertEquals("ARCHIVED",releases.get(f.viewer,f.project,r.id()).status());
    }

    @Test void typedScopeFreezesCurrentRevisionsAndRejectsForeignOrWrongTargets() {
        var f=fixture();var foreign=fixture();var sibling=sibling(f);var r=releases.create(f.admin,f.project,releaseBody("Scope"),key());
        var req=requirement(f);var deletedReq=requirement(f);var tc=test(f);var otherReq=requirement(foreign);var siblingTest=test(sibling);
        var rq=releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());
        assertEquals(1,releases.get(f.member,f.project,r.id()).scopeVersion(),"DB scope trigger must advance once per membership");
        var ts=releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),null),key());
        assertEquals(2,releases.get(f.member,f.project,r.id()).scopeVersion(),"service must not double-count the DB-triggered version");
        assertEquals(req.currentRevisionId(),rq.requirementRevisionId());assertEquals(tc.currentRevision().id(),ts.testRevisionId());
        requirements.update(f.member,f.project,req.id(),new RequirementService.UpdateCommand("Changed",null,null),tag(1),key());
        reviseTest(f,tc);assertEquals(rq,releases.scope(f.viewer,f.project,r.id(),null,25).items().stream().filter(x->x.id().equals(rq.id())).findFirst().orElseThrow());
        assertEquals(ts,releases.scope(f.viewer,f.project,r.id(),null,25).items().stream().filter(x->x.id().equals(ts.id())).findFirst().orElseThrow());
        var cycle=releases.createCycle(f.admin,f.project,r.id(),cycleBody("Cycle"),key());var before=snapshot(f.project);
        denied(409,"SCOPE_REVISION_CONFLICT",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),cycle.id()),key()));
        for(UUID id:List.of(otherReq.id(),tc.id(),UUID.randomUUID()))denied(404,"NOT_FOUND",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",id,null),key()));
        denied(404,"NOT_FOUND",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",siblingTest.id(),null),key()));
        denied(404,"NOT_FOUND",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),UUID.randomUUID()),key()));
        owner().update("UPDATE requirement SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",deletedReq.id());
        try{denied(404,"NOT_FOUND",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",deletedReq.id(),null),key()));}
        finally{owner().update("UPDATE requirement SET deleted_at=NULL WHERE id=?",deletedReq.id());}
        assertEquals(before,snapshot(f.project));
        releases.removeScope(f.admin,f.project,r.id(),ts.id(),key());
        assertEquals(tests.getAuthorized(f.member,f.project,tc.id()).currentRevision().id(),releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),null),key()).testRevisionId());
    }

    @Test void snapshotsStayImmutableWhileTraceExecutionDefectAndScopeFactsChange() {
        var f=fixture();var req=requirement(f);var tc=test(f);var link=trace.create(f.member,f.project,req.id(),tc.id(),key());
        var r=releases.create(f.admin,f.project,releaseBody("Quality"),key());var rs=releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());
        releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),null),key());
        var a=releases.evaluate(f.admin,f.project,r.id(),key());var frozen=jsonOf(a);assertMetric(a,"coverage",1,1);assertMetric(a,"plannedTests",1,1);assertMetric(a,"completed",0,1);assertMetric(a,"effectivePass",0,1);assertMetric(a,"openDefects",0,0);
        var run=start(f,tc);complete(f,run.run().id(),run.currentAttempt().id(),"FAIL");var failed=execution.run(f.member,f.project,run.run().id());
        var d=defects.fromStep(f.member,f.project,defectBody("Scoped failure"),run.run().id(),failed.currentAttempt().id(),failed.currentAttempt().steps().get(0).stepKey(),key());
        var b=releases.evaluate(f.admin,f.project,r.id(),key());assertNotEquals(a.id(),b.id());assertMetric(b,"completed",1,1);assertMetric(b,"effectivePass",0,1);assertMetric(b,"openDefects",1,1);assertEquals(frozen,jsonOf(releases.snapshot(f.viewer,f.project,r.id(),a.id())));
        trace.invalidate(f.member,f.project,link.id(),tag(1),key());defects.transition(f.member,f.project,d.id(),"RESOLVED","repair",tag(1),key());
        var retest=defects.retest(f.member,f.project,d.id(),tag(2),key());complete(f,retest.runId(),retest.attemptId(),"PASS");
        defects.transition(f.member,f.project,d.id(),"CLOSED","verified",tag(2),key());
        var c=releases.evaluate(f.admin,f.project,r.id(),key());assertMetric(c,"coverage",0,1);assertMetric(c,"completed",1,1);assertMetric(c,"effectivePass",1,1);assertMetric(c,"openDefects",0,1);
        requirements.update(f.member,f.project,req.id(),new RequirementService.UpdateCommand("New requirement",null,null),tag(1),key());reviseTest(f,tc);releases.removeScope(f.admin,f.project,r.id(),rs.id(),key());
        var changed=releases.evaluate(f.admin,f.project,r.id(),key());assertEquals(0,jsonOf(changed).get("requirementCount").intValue());assertNotEquals(a.scopeFingerprint(),changed.scopeFingerprint());
        assertEquals(frozen,jsonOf(releases.snapshot(f.member,f.project,r.id(),a.id())));assertEquals(jsonOf(b),jsonOf(releases.snapshot(f.member,f.project,r.id(),b.id())));
        assertFalse(jsonOf(b).get("evidence").toString().isBlank());
    }

    @Test void metricsExcludeInactiveTraceActiveRerunsAndUnrelatedRevisionPass() {
        var f=fixture();var req=requirement(f);var tc=test(f);var link=trace.create(f.member,f.project,req.id(),tc.id(),key());
        var r=releases.create(f.admin,f.project,releaseBody("Metric boundaries"),key());var cycle=releases.createCycle(f.admin,f.project,r.id(),cycleBody("Cycle"),key());
        releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),cycle.id()),key());
        releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),null),key());releases.addScope(f.admin,f.project,r.id(),scopeBody("TEST_CASE",tc.id(),cycle.id()),key());
        var unrelated=start(f,test(f));complete(f,unrelated.run().id(),unrelated.currentAttempt().id(),"PASS");
        var foreign=fixture();var foreignRun=start(foreign,test(foreign));complete(foreign,foreignRun.run().id(),foreignRun.currentAttempt().id(),"PASS");
        var initial=releases.evaluate(f.admin,f.project,r.id(),key());assertMetric(initial,"effectivePass",0,1);assertEquals(1,jsonOf(initial).get("requirementCount").intValue());
        var run=start(f,tc);complete(f,run.run().id(),run.currentAttempt().id(),"PASS");assertMetric(releases.evaluate(f.admin,f.project,r.id(),key()),"effectivePass",1,1);
        var active=execution.rerun(f.member,f.project,run.run().id(),key());var suppressed=releases.evaluate(f.admin,f.project,r.id(),key());assertMetric(suppressed,"completed",0,1);assertMetric(suppressed,"effectivePass",0,1);
        complete(f,run.run().id(),active.id(),"PASS");trace.invalidate(f.member,f.project,link.id(),tag(1),key());assertMetric(releases.evaluate(f.admin,f.project,r.id(),key()),"coverage",0,1);
        var latest=reviseTest(f,tc);var otherRevision=start(f,latest);complete(f,otherRevision.run().id(),otherRevision.currentAttempt().id(),"PASS");
        var last=releases.evaluate(f.admin,f.project,r.id(),key());assertMetric(last,"completed",0,1);assertMetric(last,"effectivePass",0,1);
        var d=defects.create(f.member,f.project,defectBody("Requirement related"),req.id(),key());defects.transition(f.member,f.project,d.id(),"RESOLVED","repair",tag(1),key());
        defects.transition(f.member,f.project,d.id(),"REOPENED","still broken",tag(2),key());assertMetric(releases.evaluate(f.admin,f.project,r.id(),key()),"openDefects",1,1);
        assertMetric(initial,"openDefects",0,0);
    }

    @Test void adminOnlyRuntimeRlsRejectsMembersForeignScopesAndIdentityLoss() throws Exception {
        var f=fixture();String replay=key();var r=releases.create(f.admin2,f.project,releaseBody("Permissions"),replay);var foreign=fixture();var foreignRelease=releases.create(foreign.admin,foreign.project,releaseBody("Foreign"),key());
        assertEquals(r,releases.get(f.member,f.project,r.id()));assertEquals(r,releases.get(f.viewer,f.project,r.id()));var before=snapshot(f.project);
        for(UUID actor:List.of(f.member,f.viewer)) {
            denied(403,"FORBIDDEN",()->releases.create(actor,f.project,releaseBody("Illegal"),key()));
            denied(403,"FORBIDDEN",()->releases.evaluate(actor,f.project,r.id(),key()));
            try(var c=runtimeConnection(f,actor)){assertEquals(0,sql(c,"UPDATE release_plan SET name='illegal',row_version=row_version+1 WHERE id=?",r.id()));}
        }
        denied(404,"NOT_FOUND",()->releases.get(f.outsider,f.project,r.id()));
        denied(404,"NOT_FOUND",()->releases.get(f.admin,f.project,foreignRelease.id()));assertEquals(before,snapshot(f.project));
        try(var c=runtimeConnection(f,f.admin)) {
            assertEquals("test365alm_runtime",scalar(c,"SELECT current_user"));assertEquals("false",scalar(c,"SELECT rolsuper::text FROM pg_roles WHERE rolname=current_user"));assertEquals("false",scalar(c,"SELECT rolbypassrls::text FROM pg_roles WHERE rolname=current_user"));
            assertEquals(1,Integer.parseInt(scalar(c,"SELECT count(*) FROM release_plan")));
            context(c,foreign,f.admin);assertEquals(0,Integer.parseInt(scalar(c,"SELECT count(*) FROM release_plan")));context(c,f,f.admin);assertEquals(1,Integer.parseInt(scalar(c,"SELECT count(*) FROM release_plan")));
            sqlDenied(c,"INSERT INTO release_plan(tenant_id,project_id,id,display_number,name,description,start_date,end_date,status,row_version,created_by) VALUES(?,?,?,99,'foreign','','2026-10-01','2026-10-31','PLANNED',1,?)","42501",foreign.tenant,foreign.project,UUID.randomUUID(),f.admin);
        }
        projects.revokeMember(f.admin,f.project,f.admin2,1L);denied(404,"NOT_FOUND",()->releases.create(f.admin2,f.project,releaseBody("Permissions"),replay));
        owner().update("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?",f.tenant,f.member);denied(404,"NOT_FOUND",()->releases.get(f.member,f.project,r.id()));
        // Direct service lookup hides the project via RLS; the real HTTP filter separately returns 403.
        owner().update("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.viewer);denied(404,"NOT_FOUND",()->releases.get(f.viewer,f.project,r.id()));
    }

    @Test void twoAdminsUpdatingReleaseOrCycleHaveOneExactStaleLoser() throws Exception {
        var f=fixture();var r=releases.create(f.admin,f.project,releaseBody("Concurrent"),key());var cy=releases.createCycle(f.admin,f.project,r.id(),cycleBody("Cycle"),key());
        var winner=concurrent(index->releases.edit(index==0?f.admin:f.admin2,f.project,r.id(),json("{\"name\":\"Winner "+index+"\"}"),tag(1),key()));assertOneWinner(winner,ReleaseService.ReleaseView.class);
        var success=(ReleaseService.ReleaseView)winner.stream().filter(x->x instanceof ReleaseService.ReleaseView).findFirst().orElseThrow();assertEquals(success,releases.get(f.member,f.project,r.id()));assertEquals(2,success.rowVersion());
        winner=concurrent(index->releases.editCycle(index==0?f.admin:f.admin2,f.project,r.id(),cy.id(),json("{\"name\":\"Cycle winner "+index+"\"}"),tag(1),key()));assertOneWinner(winner,ReleaseService.CycleView.class);
        var cycle=(ReleaseService.CycleView)winner.stream().filter(x->x instanceof ReleaseService.CycleView).findFirst().orElseThrow();assertEquals(cycle,releases.getCycle(f.viewer,f.project,r.id(),cy.id()));assertEquals(2,cycle.rowVersion());assertEquals(4,count("release_outbox_event",f.project));assertEquals(4,count("core_command_idempotency",f.project));
    }

    @Test void sameKeyConcurrentCreatesEvaluateAndScopeHaveOneFrozenResult() throws Exception {
        var f=fixture();String rk=key();var pair=concurrent(i->releases.create(f.admin,f.project,releaseBody("Same"),rk));assertEquals(pair.get(0),pair.get(1));var r=(ReleaseService.ReleaseView)pair.get(0);
        String ck=key();pair=concurrent(i->releases.createCycle(f.admin,f.project,r.id(),cycleBody("Same cycle"),ck));assertEquals(pair.get(0),pair.get(1));
        var req=requirement(f);String sk=key();pair=concurrent(i->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),sk));assertEquals(pair.get(0),pair.get(1));var scope=(ReleaseService.ScopeView)pair.get(0);
        String ek=key();pair=concurrent(i->releases.evaluate(f.admin,f.project,r.id(),ek));assertEquals(pair.get(0),pair.get(1));var snapshot=pair.get(0);
        assertEquals(1,count("release_plan",f.project));assertEquals(1,count("release_cycle",f.project));assertEquals(1,count("release_scope",f.project));assertEquals(1,count("quality_snapshot",f.project));
        var before=snapshot(f.project);denied(409,"DUPLICATE_SCOPE",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key()));
        denied(409,"IDEMPOTENCY_KEY_REUSED",()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",UUID.randomUUID(),null),sk));assertEquals(before,snapshot(f.project));
        String removeKey=key();var removed=releases.removeScope(f.admin,f.project,r.id(),scope.id(),removeKey);assertEquals(removed,releases.removeScope(f.admin,f.project,r.id(),scope.id(),removeKey));assertEquals(snapshot,releases.evaluate(f.admin,f.project,r.id(),ek));
        assertEquals(5,count("release_outbox_event",f.project));
    }

    @Test void auditAndOutboxFailuresRollbackCreateEditScopeAndEvaluateExactly() {
        var f=fixture();var r=releases.create(f.admin,f.project,releaseBody("Nonempty"),key());releases.createCycle(f.admin,f.project,r.id(),cycleBody("Existing cycle"),key());
        var req=requirement(f);releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());releases.evaluate(f.admin,f.project,r.id(),key());
        for(String table:List.of("audit_event","release_outbox_event")) {
            for(String op:List.of("create","update","scope","evaluate")) {
                var fresh=requirement(f);String intent=key();long version=releases.get(f.admin,f.project,r.id()).rowVersion();var update=json("{\"name\":\"Rollback edit "+key()+"\"}");
                Runnable call=switch(op){case "create"->()->releases.create(f.admin,f.project,releaseBody("Rollback create"),intent);case "update"->()->releases.edit(f.admin,f.project,r.id(),update,tag(version),intent);case "scope"->()->releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",fresh.id(),null),intent);default->()->releases.evaluate(f.admin,f.project,r.id(),intent);};
                var before=snapshot(f.project);owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");
                try{var ex=assertThrows(org.springframework.dao.DataAccessException.class,call::run);assertTrue(ex.getMostSpecificCause().getMessage().contains("permission denied for table "+table),ex.getMostSpecificCause().getMessage());}
                finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
                assertEquals(before,snapshot(f.project),table+" / "+op+" must rollback every object, scope, counter, event and key");call.run();assertNotEquals(before,snapshot(f.project),"legal recovery command must commit");
            }
        }
    }

    @Test void boundedListsContinueWithoutDuplicatesOrForeignProjects() {
        var f=fixture();var foreign=fixture();releases.create(foreign.admin,foreign.project,releaseBody("Foreign"),key());
        ReleaseService.ReleaseView r=null;for(int i=0;i<27;i++)r=releases.create(f.admin,f.project,releaseBody("Page "+i),key());final UUID id=r.id();
        assertPages(releases.list(f.viewer,f.project,null,25),cursor->releases.list(f.viewer,f.project,cursor,25),27);
        for(int i=0;i<27;i++){releases.createCycle(f.admin,f.project,id,cycleBody("Cycle "+i),key());releases.addScope(f.admin,f.project,id,scopeBody("REQUIREMENT",requirement(f).id(),null),key());releases.evaluate(f.admin,f.project,id,key());}
        assertPages(releases.cycles(f.member,f.project,id,null,25),cursor->releases.cycles(f.member,f.project,id,cursor,25),27);
        assertPages(releases.scope(f.viewer,f.project,id,null,25),cursor->releases.scope(f.viewer,f.project,id,cursor,25),27);
        assertPages(releases.snapshots(f.viewer,f.project,id,null,25),cursor->releases.snapshots(f.viewer,f.project,id,cursor,25),27);
        assertEquals(27,jsonOf(releases.evaluate(f.admin,f.project,id,key())).get("requirementCount").intValue(),"counts cover all pages");
    }

    @Test void runtimeCannotRewriteSnapshotsOrForgeCrossScopeRelations() throws Exception {
        var f=fixture();var sibling=sibling(f);var r=releases.create(f.admin,f.project,releaseBody("SQL"),key());var foreignRelease=releases.create(sibling.admin,sibling.project,releaseBody("Foreign"),key());
        var req=requirement(f);var foreignReq=requirement(sibling);releases.addScope(f.admin,f.project,r.id(),scopeBody("REQUIREMENT",req.id(),null),key());var snap=releases.evaluate(f.admin,f.project,r.id(),key());var before=snapshot(f.project);
        try(var c=runtimeConnection(f,f.admin)) {
            for(String sql:List.of("UPDATE quality_snapshot SET scope_version=scope_version+1","DELETE FROM quality_snapshot","TRUNCATE quality_snapshot"))sqlDenied(c,sql,"42501");
            sqlDenied(c,"INSERT INTO release_cycle(tenant_id,project_id,id,release_id,name,start_date,end_date,status,row_version,created_by) VALUES(?,?,?,?,'Wrong release','2026-10-01','2026-10-02','PLANNED',1,?)","23503",f.tenant,f.project,UUID.randomUUID(),foreignRelease.id(),f.admin);
            var invalid=assertThrows(SQLException.class,()->sql(c,"INSERT INTO release_scope(tenant_id,project_id,id,release_id,kind,requirement_id,requirement_revision_id,created_by) VALUES(?,?,?,?,'REQUIREMENT',?,?,?)",f.tenant,f.project,UUID.randomUUID(),r.id(),foreignReq.id(),foreignReq.currentRevisionId(),f.admin));
            assertEquals("23514",invalid.getSQLState());assertTrue(invalid.getMessage().contains("requirement scope must freeze current revision"),invalid.getMessage());
        }
        assertEquals(before,snapshot(f.project));assertEquals(snap,releases.snapshot(f.viewer,f.project,r.id(),snap.id()));
        assertNotNull(releases.createCycle(f.admin,f.project,r.id(),cycleBody("Legal control"),key()).id());
    }

    private record Fixture(UUID admin,UUID admin2,UUID member,UUID viewer,UUID outsider,UUID tenant,UUID domain,UUID project){}
    private Fixture fixture() {
        UUID admin=principal(),admin2=principal(),member=principal(),viewer=principal(),outsider=principal(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();var o=owner();
        o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"r10-"+tenant,"R10",admin);o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"R10");
        for(UUID p:List.of(admin,admin2,member,viewer,outsider))o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,p,p.equals(admin)||p.equals(outsider)?"{TENANT_ADMIN}":"{MEMBER}");
        var project=projects.createProject(admin,tenant,domain,"R10-"+UUID.randomUUID(),"R10");projects.putMember(admin,project.id(),admin2,List.of("PROJECT_ADMIN"),0L,false);projects.putMember(admin,project.id(),member,List.of("PROJECT_MEMBER"),0L,false);projects.putMember(admin,project.id(),viewer,List.of("PROJECT_VIEWER"),0L,false);
        return new Fixture(admin,admin2,member,viewer,outsider,tenant,domain,project.id());
    }
    private Fixture sibling(Fixture f){var p=projects.createProject(f.admin,f.tenant,f.domain,"Sibling-"+key(),"Sibling");projects.putMember(f.admin,p.id(),f.member,List.of("PROJECT_MEMBER"),0L,false);return new Fixture(f.admin,f.admin2,f.member,f.viewer,f.outsider,f.tenant,f.domain,p.id());}
    private UUID principal(){UUID id=UUID.randomUUID();owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",id,"https://r10.example.invalid",id.toString(),"R10");return id;}
    private RequirementService.RequirementView requirement(Fixture f){return requirements.create(f.member,f.project,new RequirementService.CreateCommand("Requirement","original body","HIGH"),key());}
    private TestCaseService.TestCaseView test(Fixture f){return tests.create(f.member,f.project,new TestCaseService.CreateCommand("MANUAL","Source","original description","",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected"))),key());}
    private TestCaseService.TestCaseView reviseTest(Fixture f,TestCaseService.TestCaseView tc){return tests.appendRevision(f.member,f.project,tc.id(),new TestCaseService.RevisionCommand("New source","new description","",List.of(new TestCaseService.StepCommand(tc.currentRevision().steps().get(0).stepKey(),1,"New act","New expected"))),tag(tc.rowVersion()),key());}
    private ExecutionService.RunDetail start(Fixture f,TestCaseService.TestCaseView tc){var set=execution.createSet(f.member,f.project,"Set","",key());var instance=execution.addInstance(f.member,f.project,set.id(),tc.id(),tc.currentRevision().id(),key());return execution.createRun(f.member,f.project,instance.id(),key());}
    private void complete(Fixture f,UUID run,UUID attempt,String conclusion){for(var s:execution.attempt(f.member,f.project,run,attempt).steps())execution.saveStep(f.member,f.project,run,attempt,s.stepKey(),"actual "+conclusion,conclusion,s.rowVersion(),key());execution.finish(f.member,f.project,run,attempt,execution.attempt(f.member,f.project,run,attempt).rowVersion(),key());}
    private JdbcTemplate owner(){return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));}
    private int count(String table,UUID project){return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
    private Map<String,List<String>> snapshot(UUID project){var m=new TreeMap<String,List<String>>();for(String table:List.of("release_plan","release_cycle","release_scope","quality_snapshot","release_number_allocator","release_outbox_event","audit_event","core_command_idempotency"))m.put(table,owner().query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text",(rs,i)->rs.getString(1),project));return m;}
    private Connection runtimeConnection(Fixture f,UUID actor)throws SQLException{var c=DriverManager.getConnection(PG.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");context(c,f,actor);return c;}
    private void context(Connection c,Fixture f,UUID actor)throws SQLException{for(var e:Map.of("tenant_id",f.tenant,"project_id",f.project,"principal_id",actor).entrySet())try(var s=c.prepareStatement("SELECT set_config(?,?,false)")){s.setString(1,"test365alm."+e.getKey());s.setString(2,e.getValue().toString());s.executeQuery().close();}}
    private static int sql(Connection c,String query,Object...args)throws SQLException{try(var s=c.prepareStatement(query)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();}}
    private static void sqlDenied(Connection c,String query,String state,Object...args){var e=assertThrows(SQLException.class,()->sql(c,query,args));assertEquals(state,e.getSQLState(),e.getMessage());}
    private static String scalar(Connection c,String query)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    private static void denied(int status,String code,Runnable action){var e=assertThrows(ProjectAccessException.class,action::run);assertEquals(status,e.status().value());assertEquals(code,e.code());}
    private static void assertMetric(Object snapshot,String metric,long numerator,long denominator){var n=jsonOf(snapshot).get(metric);assertEquals(numerator,n.get("numerator").longValue(),metric);assertEquals(denominator,n.get("denominator").longValue(),metric);}
    private static JsonNode jsonOf(Object object){return CoreCommands.JSON.valueToTree(object);}
    private static JsonNode json(String value){return CoreCommands.JSON.readTree(value);}
    private static JsonNode releaseBody(String name){return json("{\"name\":\""+name+"\",\"description\":\"description\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-31\"}");}
    private static JsonNode cycleBody(String name){return json("{\"name\":\""+name+"\",\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\"}");}
    private static JsonNode scopeBody(String kind,UUID id,UUID cycle){return json("{\"kind\":\""+kind+"\",\""+(kind.equals("REQUIREMENT")?"requirementId":"testCaseId")+"\":\""+id+"\""+(cycle==null?"":",\"cycleId\":\""+cycle+"\"")+"}");}
    private static JsonNode defectBody(String title){return json("{\"title\":\""+title+"\",\"description\":\"failure\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}");}
    private static String key(){return UUID.randomUUID().toString();}
    private static String tag(long version){return "\""+version+"\"";}
    private static List<Object> concurrent(java.util.function.IntFunction<Object> action)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{List<Future<Object>> results=new ArrayList<>();for(int i=0;i<2;i++){final int index=i;results.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{return action.apply(index);}catch(ProjectAccessException e){return e;}}));}start.countDown();return List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private static void assertOneWinner(List<Object> results,Class<?> type){assertEquals(1,results.stream().filter(type::isInstance).count());assertEquals(1,results.stream().filter(x->x instanceof ProjectAccessException e&&e.status().value()==412&&e.code().equals("STALE_VERSION")).count());}
    private static <T> void assertPages(CoreCommands.Page<T> first,java.util.function.Function<String,CoreCommands.Page<T>> next,int total){assertEquals(25,first.items().size());assertNotNull(first.nextCursor());var second=next.apply(first.nextCursor());assertEquals(total-25,second.items().size());assertNull(second.nextCursor());assertEquals(total,java.util.stream.Stream.concat(first.items().stream(),second.items().stream()).map(x->jsonOf(x).get("id").asText()).distinct().count());}
}
