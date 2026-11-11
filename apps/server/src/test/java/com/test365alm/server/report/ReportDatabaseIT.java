package com.test365alm.server.report;
import com.test365alm.server.signature.*;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;
import com.test365alm.server.review.TestReviewService;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.defect.DefectService;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class ReportDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("signature_it").withUsername("signature_owner").withPassword("signature_isolated_owner_password")
        .withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");r.add("spring.flyway.url",PG::getJdbcUrl);
        r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
    }
    @Autowired ProjectService projects;
    @Autowired TestCaseService tests;
    @Autowired TestReviewService reviews;
    @Autowired SignatureService signatures;
    @Autowired ExecutionService execution;
    @Autowired DefectService defects;

    @Autowired ReportService reports;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void historicalFailureSurvivesRevisionAssignmentAndPassingRerun()throws Exception {
        var f=fixture();var t=create(f);var set=execution.createSet(f.actor,f.project,"历史测试集","说明",key());
        var i=execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
        execution.assignInstance(f.actor,f.project,i.id(),f.actor,1,key());
        var r=execution.createRun(f.actor,f.project,i.id(),key());var a=finish(f,r,"FAIL","原始失败 <script>");
        var before=reports.run(f.viewer,f.project,r.run().id(),a.id());
        tests.appendRevision(f.actor,f.project,t.id(),new TestCaseService.RevisionCommand("新用例","New","",List.of(new TestCaseService.StepCommand(t.currentRevision().steps().get(0).stepKey(),1,"Changed","Changed")),empty()),tag(t.rowVersion()),key());
        execution.assignInstance(f.actor,f.project,i.id(),f.admin,2,key());
        execution.rerun(f.actor,f.project,r.run().id(),key());finish(f,execution.run(f.actor,f.project,r.run().id()),"PASS","复测通过");
        var after=reports.run(f.viewer,f.project,r.run().id(),a.id());
        assertEquals(before.facts(),after.facts());assertEquals(before.steps(),after.steps());
        assertEquals("原始目的",after.facts().at("/businessFields/test_purpose/value").asText());
        assertEquals("FAIL",after.facts().at("/attempt/conclusion").asText());assertEquals(f.actor.toString(),after.facts().at("/manifest/assignee_id").asText());
        assertEquals("原始失败 <script>",after.steps().get(0).path("actual_result").asText());
        assertEquals(1,reports.testSet(f.viewer,f.project,set.id(),null).facts().at("/counts/categories/PASS").asInt());
    }
    @Test void latestSelectionPrecedesFilteringWithStableTiesAndSeparateHistoricalActivity()throws Exception {
        var f=fixture();var t=create(f);var set=execution.createSet(f.actor,f.project,"Latest","",key());
        var i=execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
        var first=execution.createRun(f.actor,f.project,i.id(),key());finish(f,first,"FAIL","Fail");
        var other=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
            execution.rerun(f.actor,f.project,first.run().id(),key());
            assertEquals(0,reports.testSet(f.actor,f.project,set.id(),"FAIL").items().size());
            assertEquals(1,reports.testSet(f.actor,f.project,set.id(),null).facts().at("/counts/categories/RUNNING").asInt());
            return execution.createRun(f.actor,f.project,i.id(),key());
        });
        var selected=reports.testSet(f.actor,f.project,set.id(),null);
        String expected=first.run().id().toString().compareTo(other.run().id().toString())>0?first.run().id().toString():other.run().id().toString();
        assertEquals(expected,selected.items().get(0).path("run_id").asText());assertEquals(1,selected.facts().path("otherActiveAttempts").asInt());
        assertThrows(ProjectAccessException.class,()->reports.run(f.actor,f.project,other.run().id(),other.currentAttempt().id()));
    }
    @Test void emptyZeroDenominatorsAndDuplicateCaseRevisionsBeyondDefaultPageAreComplete()throws Exception {
        var f=fixture();var set=execution.createSet(f.actor,f.project,"Large","",key());
        var e=reports.testSet(f.viewer,f.project,set.id(),null);assertTrue(e.facts().at("/counts/completionRate").isNull());assertTrue(e.facts().at("/counts/passRate").isNull());
        var t=create(f);
        for(int n=0;n<55;n++){
            execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
            t=tests.appendRevision(f.actor,f.project,t.id(),new TestCaseService.RevisionCommand("Revision "+n,"","",List.of(new TestCaseService.StepCommand(t.currentRevision().steps().get(0).stepKey(),1,"Act "+n,"Expected")),empty()),tag(t.rowVersion()),key());
        }
        var report=reports.testSet(f.viewer,f.project,set.id(),"NOT_RUN");assertEquals(55,report.items().size());assertEquals(55,report.facts().at("/counts/categories/NOT_RUN").asInt());assertTrue(report.facts().at("/counts/passRate").isNull());
    }
    @Test void manualDefectsAndRealRelationsPreserveOriginalFailureAndRetestCycle()throws Exception {
        var f=fixture();var t=create(f);var set=execution.createSet(f.actor,f.project,"Scoped","",key());
        var i=execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
        var r=execution.createRun(f.actor,f.project,i.id(),key());var a=finish(f,r,"FAIL","原始失败");
        var content=CoreCommands.JSON.readTree("{\"title\":\"偏差百分号%_\",\"description\":\"长描述\",\"severity\":\"HIGH\",\"priority\":\"HIGH\"}");
        defects.create(f.actor,f.project,content,null,key());
        var d=defects.fromStep(f.actor,f.project,content,r.run().id(),a.id(),a.steps().get(0).stepKey(),key());
        var resolved=defects.transition(f.actor,f.project,d.id(),"RESOLVED","修复",tag(d.rowVersion()),key());
        var rt=defects.retest(f.actor,f.project,d.id(),tag(resolved.rowVersion()),key());finish(f,execution.run(f.actor,f.project,rt.runId()),"PASS","新结果");
        var reopened=defects.transition(f.actor,f.project,d.id(),"REOPENED","新问题",tag(resolved.rowVersion()),key());
        var second=defects.transition(f.actor,f.project,d.id(),"RESOLVED","第二周期",tag(reopened.rowVersion()),key());
        assertEquals(2,reports.defects(f.viewer,f.project,null,null,"HIGH","%_").items().size());
        var scoped=reports.defects(f.viewer,f.project,set.id(),null,null,"");assertEquals(1,scoped.items().size());
        assertEquals("FAIL",scoped.links().get(0).path("source_conclusion").asText());assertEquals("原始失败",scoped.links().get(0).path("actual_result").asText());
        assertEquals("PASS",scoped.retests().get(0).path("conclusion").asText());assertNotEquals(second.currentRevision().id().toString(),scoped.retests().get(0).path("resolved_revision_id").asText());
    }
    @Test void exactReviewAndThreeSignatureKindsUseStoredPayloadWithoutReadSideWrites()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);var sig=sign(f,t,c,proof(f,c),key()).signature();
        var set=execution.createSet(f.actor,f.project,"Signed","",key());var i=execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
        var r=execution.createRun(f.actor,f.project,i.id(),key());var a=finish(f,r,"PASS","通过");
        var rc=signatures.prepare(f.actor,f.project,SignatureRules.Kind.RUN_COMPLETION,r.run().id(),a.id(),empty(),tag(a.rowVersion()),key(),BINDING);
        signatures.sign(f.actor,f.project,SignatureRules.Kind.RUN_COMPLETION,r.run().id(),a.id(),body(rc),tag(a.rowVersion()),key(),BINDING,proof(f,rc));
        var d=defects.create(f.actor,f.project,CoreCommands.JSON.readTree("{\"title\":\"Manual\",\"description\":\"\",\"severity\":\"HIGH\",\"priority\":\"HIGH\"}"),null,key());
        var resolved=defects.transition(f.actor,f.project,d.id(),"RESOLVED","fixed",tag(d.rowVersion()),key());var closed=defects.transition(f.actor,f.project,d.id(),"CLOSED","closed",tag(resolved.rowVersion()),key());
        var dc=signatures.prepare(f.actor,f.project,SignatureRules.Kind.DEVIATION_CLOSURE,d.id(),closed.currentRevision().id(),empty(),tag(closed.rowVersion()),key(),BINDING);
        var ds=signatures.sign(f.actor,f.project,SignatureRules.Kind.DEVIATION_CLOSURE,d.id(),closed.currentRevision().id(),body(dc),tag(closed.rowVersion()),key(),BINDING,proof(f,dc)).signature();
        int outbox=count(f,"signature_outbox_event"),records=count(f,"signature_record");
        var report=reports.run(f.viewer,f.project,r.run().id(),a.id());assertEquals(2,report.signatures().size());assertEquals(2,report.reviews().size());
        assertTrue(report.signatures().stream().anyMatch(x->x.path("contentDigest").asText().equals(sig.contentDigest())));
        var stored=reports.defects(f.viewer,f.project,null,null,null,"").signatures().get(0);assertEquals(ds.contentDigest(),stored.path("contentDigest").asText());
        try(var db=owner();var st=db.prepareStatement("SELECT payload::text FROM signature_record WHERE id=?")){st.setObject(1,ds.id());try(var rs=st.executeQuery()){assertTrue(rs.next());assertEquals(CoreCommands.JSON.readTree(rs.getString(1)),stored);}}
        assertEquals(outbox,count(f,"signature_outbox_event"));assertEquals(records,count(f,"signature_record"));
    }
    @Test void viewerReadAndCrossTenantProjectRevokedDisabledDenials()throws Exception {
        var f=fixture();var other=fixture();var set=execution.createSet(f.actor,f.project,"Permission","",key());
        assertEquals("SET",reports.testSet(f.viewer,f.project,set.id(),null).kind());
        assertThrows(ProjectAccessException.class,()->reports.testSet(other.actor,f.project,set.id(),null));
        var otherSet=execution.createSet(other.actor,other.project,"Other","",key());assertThrows(ProjectAccessException.class,()->reports.testSet(f.actor,f.project,otherSet.id(),null));
        try(var db=owner()){sql(db,"DELETE FROM project_member WHERE project_id=? AND principal_id=?",f.project,f.viewer);}
        assertThrows(ProjectAccessException.class,()->reports.testSet(f.viewer,f.project,set.id(),null));
        try(var db=owner()){sql(db,"UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.actor);}
        assertThrows(ProjectAccessException.class,()->reports.defects(f.actor,f.project,null,null,null,""));
    }
    private ExecutionService.AttemptView finish(F f,ExecutionService.RunDetail r,String outcome,String actual){
        var a=r.currentAttempt();for(var s:a.steps())execution.saveStep(f.actor,f.project,r.run().id(),a.id(),s.stepKey(),actual,outcome,s.rowVersion(),key());
        return execution.finish(f.actor,f.project,r.run().id(),a.id(),execution.attempt(f.actor,f.project,r.run().id(),a.id()).rowVersion(),key());
    }
    private static final String ISSUER="https://signature-it.example",BINDING="a".repeat(64);
    private SignatureService.Challenge prepare(F f,TestCaseService.TestCaseView t){return signatures.prepare(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),empty(),tag(t.rowVersion()),key(),BINDING);}
    private SignatureService.View sign(F f,TestCaseService.TestCaseView t,SignatureService.Challenge c,SignatureLoginFlow.Proof proof,String key){return signatures.sign(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),body(c),tag(t.rowVersion()),key,BINDING,proof);}
    private static SignatureLoginFlow.Proof proof(F f,SignatureService.Challenge c){return new SignatureLoginFlow.Proof(c.id(),f.actor,ISSUER,f.actor.toString(),Instant.now());}
    private static tools.jackson.databind.JsonNode empty(){return CoreCommands.JSON.readTree("{}");}
    private static tools.jackson.databind.JsonNode body(SignatureService.Challenge c){return CoreCommands.JSON.createObjectNode().put("challengeId",c.id().toString());}
    private static tools.jackson.databind.JsonNode command(String action){return CoreCommands.JSON.createObjectNode().put("action",action);}
    private TestCaseService.TestCaseView create(F f){return tests.create(f.actor,f.project,new TestCaseService.CreateCommand("MANUAL","原始用例","Description","Precondition",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected")),CoreCommands.JSON.readTree("{\"test_purpose\":{\"value\":\"原始目的\"},\"reviewer\":{\"value\":\""+f.actor+"\"}}")),key());}
    private TestCaseService.TestCaseView approved(F f){var t=create(f);var r=t.currentRevision().id();var s=reviews.act(f.actor,f.project,t.id(),r,command("SUBMIT"),tag(t.rowVersion()),key());reviews.act(f.actor,f.project,t.id(),r,command("APPROVE"),tag(s.rowVersion()),key());return tests.getAuthorized(f.actor,f.project,t.id());}
    private static String key(){return UUID.randomUUID().toString();}
    private static String tag(long n){return "\""+n+"\"";}
    private static Connection owner()throws SQLException{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
    private static int sql(Connection db,String query,Object...args)throws SQLException{try(var st=db.prepareStatement(query)){for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st.executeUpdate();}}
    private static Connection runtime(F f,UUID actor)throws SQLException{var db=DriverManager.getConnection(PG.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");for(var e:Map.of("tenant_id",f.tenant,"project_id",f.project,"principal_id",actor).entrySet())try(var st=db.prepareStatement("SELECT set_config(?,?,false)")){st.setString(1,"test365alm."+e.getKey());st.setString(2,e.getValue().toString());st.execute();}return db;}
    private int count(F f,String table)throws SQLException{try(var db=owner();var st=db.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND project_id=?")){st.setObject(1,f.tenant);st.setObject(2,f.project);try(var rs=st.executeQuery()){rs.next();return rs.getInt(1);}}}
    private F fixture()throws Exception {
        UUID admin=UUID.randomUUID(),actor=UUID.randomUUID(),viewer=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
        try(var db=owner()) {
            for(UUID id:List.of(admin,actor,viewer))sql(db,"INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,'Signature user')",id,ISSUER,id.toString());
            sql(db,"INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,'Signature tenant',?)",tenant,tenant.toString(),admin);
            sql(db,"INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'Signature domain')",domain,tenant);
            for(UUID id:List.of(admin,actor,viewer))sql(db,"INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,id,id.equals(admin)?"{TENANT_ADMIN}":"{MEMBER}");
        }
        UUID project=projects.createProject(admin,tenant,domain,"S-"+tenant,"Signatures").id();
        projects.putMember(admin,project,actor,List.of("PROJECT_MEMBER"),0L,false);projects.putMember(admin,project,viewer,List.of("PROJECT_VIEWER"),0L,false);
        return new F(admin,actor,viewer,tenant,project);
    }
    private record F(UUID admin,UUID actor,UUID viewer,UUID tenant,UUID project) { }
}
