package com.test365alm.server.signature;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
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
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.review.TestReviewService;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.defect.DefectService;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class SignatureMigrationUpgradeIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("signature_upgrade").withUsername("signature_upgrade_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");r.add("spring.flyway.enabled",()->"false");
    }
    @Autowired SignatureService signatures;
    @Autowired TestCaseService tests;
    @Autowired TestReviewService reviews;
    @Autowired ExecutionService execution;
    @Autowired DefectService defects;
    private Flyway flyway(String target){return Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).target(target).load();}

    @Test void nonemptyV25UpgradePreservesEveryOldColumnFrozenReplayReviewAndExecutionThenSignsThroughRuntime()throws Exception {
        assertEquals(25,flyway("25").migrate().migrationsExecuted);
        UUID actor=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID(),project=UUID.randomUUID();
        String issuer="https://signature-upgrade.example";
        try(var c=owner()) {
            sql(c,"INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,'Upgrade signer')",actor,issuer,actor.toString());
            sql(c,"INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,'Upgrade tenant',?)",tenant,tenant.toString(),actor);
            sql(c,"INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'Upgrade domain')",domain,tenant);
            sql(c,"INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,ARRAY['MEMBER'])",tenant,actor);
            sql(c,"INSERT INTO project(id,tenant_id,domain_id,code,name,created_by) VALUES(?,?,?,?,'Upgrade project',?)",project,tenant,domain,project.toString(),actor);
            sql(c,"INSERT INTO project_member(tenant_id,project_id,principal_id,roles) VALUES(?,?,?,ARRAY['PROJECT_MEMBER'])",tenant,project,actor);
        }
        var content=new TestCaseService.CreateCommand("MANUAL","V25 content","Description","Precondition",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected")),
            CoreCommands.JSON.readTree("{\"reviewer\":{\"value\":\""+actor+"\"}}"));
        var test=tests.create(actor,project,content,"v25-create-case");UUID revision=test.currentRevision().id();
        reviews.act(actor,project,test.id(),revision,CoreCommands.JSON.readTree("{\"action\":\"SUBMIT\"}"),"\"1\"","v25-submit-review");
        var approved=reviews.act(actor,project,test.id(),revision,CoreCommands.JSON.readTree("{\"action\":\"APPROVE\"}"),"\"2\"","v25-approve-review");
        var set=execution.createSet(actor,project,"V25 set","","v25-create-set");
        var instance=execution.addInstance(actor,project,set.id(),test.id(),revision,"v25-add-instance");
        var run=execution.createRun(actor,project,instance.id(),"v25-create-run");var a=run.currentAttempt();
        execution.saveStep(actor,project,run.run().id(),a.id(),a.steps().get(0).stepKey(),"Observed","PASS",1,"v25-step-pass");
        execution.finish(actor,project,run.run().id(),a.id(),1,"v25-run-finish");
        var defect=defects.create(actor,project,CoreCommands.JSON.readTree("{\"title\":\"V25 deviation\",\"description\":\"Frozen\",\"severity\":\"HIGH\",\"priority\":\"HIGH\"}"),null,"v25-create-defect");
        Map<String,List<String>> before;try(var c=owner()){before=snapshot(c);}
        for(String table:List.of("test_revision","test_step","test_review_event","test_review_outbox_event","execution_manifest","execution_manifest_step","run_attempt","run_step","execution_event","execution_idempotency","defect","defect_revision","defect_outbox_event","core_command_idempotency","audit_event"))assertFalse(before.get(table).isEmpty(),table);
        assertEquals(1,flyway("26").migrate().migrationsExecuted);assertEquals(0,flyway("26").migrate().migrationsExecuted);
        try(var c=owner()){assertEquals(before,snapshot(c),"every old table/row/column stays byte-for-byte JSONB equal");}
        assertEquals(test,tests.create(actor,project,content,"v25-create-case"));
        assertEquals(run,execution.createRun(actor,project,instance.id(),"v25-create-run"));
        assertEquals(approved,reviews.act(actor,project,test.id(),revision,CoreCommands.JSON.readTree("{\"action\":\"APPROVE\"}"),"\"2\"","v25-approve-review"));
        assertEquals(defect,defects.get(actor,project,defect.id()));
        var challenge=signatures.prepare(actor,project,SignatureRules.Kind.TEST_APPROVAL,test.id(),revision,CoreCommands.JSON.readTree("{}"),"\"3\"","v26-challenge","a".repeat(64));
        var proof=new SignatureLoginFlow.Proof(challenge.id(),actor,issuer,actor.toString(),Instant.now());
        var result=signatures.sign(actor,project,SignatureRules.Kind.TEST_APPROVAL,test.id(),revision,CoreCommands.JSON.createObjectNode().put("challengeId",challenge.id().toString()),"\"3\"","v26-signature","a".repeat(64),proof);
        assertEquals(revision,result.signature().snapshotId());assertTrue(result.signature().evidence().simulated());
        assertEquals(test.currentRevision(),tests.revision(actor,project,test.id(),revision));
    }
    private static Map<String,List<String>> snapshot(Connection c)throws SQLException {
        var tables=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','signature_challenge','signature_record','signature_outbox_event') ORDER BY tablename")){while(r.next())tables.add(r.getString(1));}
        var result=new TreeMap<String,List<String>>();
        for(String table:tables)try(var s=c.createStatement();var r=s.executeQuery("SELECT to_jsonb(t)::text FROM \""+table.replace("\"","\"\"")+"\" t ORDER BY to_jsonb(t)::text")){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}return result;
    }
    private static Connection owner()throws SQLException{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
    private static void sql(Connection c,String query,Object...values)throws SQLException{try(var s=c.prepareStatement(query)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
}
