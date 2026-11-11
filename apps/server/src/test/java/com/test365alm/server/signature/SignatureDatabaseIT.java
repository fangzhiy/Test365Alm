package com.test365alm.server.signature;

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
class SignatureDatabaseIT {
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

    @Test void approvedRevisionFreezesVerifiedIdentityMeaningContentAndSimulatedEvidenceAtomically()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);var before=count(f,"audit_event");
        var signed=sign(f,t,c,proof(f,c),key());var r=signed.signature();
        assertNotNull(r);assertEquals("用例批准确认",r.meaning());assertEquals(f.actor,r.actorId());
        assertEquals(t.currentRevision().id(),r.snapshotId());assertEquals("Signature user",r.displayName());
        assertEquals(ISSUER,r.issuer());assertEquals(f.actor.toString(),r.subject());assertEquals("SIMULATED_V1",r.evidence().provider());
        assertTrue(r.evidence().simulated());assertEquals("APPROVED",r.contentSnapshot().at("/content/review/state").textValue());
        assertFalse(signed.canSign());assertEquals(before+1,count(f,"audit_event"));assertEquals(1,count(f,"signature_record"));
        assertEquals(1,count(f,"signature_outbox_event"));assertEquals(signed,signatures.get(f.viewer,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id()));
        assertEquals(t.rowVersion(),tests.getAuthorized(f.actor,f.project,t.id()).rowVersion());
        String payload=CoreCommands.JSON.writeValueAsString(r);
        for(String forbidden:new String[]{"password","access_token","id_token","session_binding","code_verifier"})assertFalse(payload.contains(forbidden));
    }
    @Test void draftSubmittedForeignReviewerViewerAndCrossProjectNeverCreateChallenges()throws Exception {
        var f=fixture();var t=create(f);var kind=SignatureRules.Kind.TEST_APPROVAL;var r=t.currentRevision().id();
        assertThrows(ProjectAccessException.class,()->signatures.prepare(f.actor,f.project,kind,t.id(),r,empty(),tag(1),key(),BINDING));
        var submitted=reviews.act(f.actor,f.project,t.id(),r,command("SUBMIT"),tag(1),key());
        assertThrows(ProjectAccessException.class,()->signatures.prepare(f.actor,f.project,kind,t.id(),r,empty(),tag(submitted.rowVersion()),key(),BINDING));
        reviews.act(f.actor,f.project,t.id(),r,command("APPROVE"),tag(submitted.rowVersion()),key());
        for(UUID wrong:List.of(f.admin,f.viewer))assertThrows(ProjectAccessException.class,()->signatures.prepare(wrong,f.project,kind,t.id(),r,empty(),tag(3),key(),BINDING));
        var other=fixture();assertThrows(ProjectAccessException.class,()->signatures.get(other.actor,other.project,kind,t.id(),r));
        assertEquals(0,count(f,"signature_challenge"));assertEquals(0,count(f,"signature_record"));
    }
    @Test void forgedCrossSessionOtherSubjectMissingOrStaleProofCannotSign()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);
        for(var proof:Arrays.asList(null,new SignatureLoginFlow.Proof(c.id(),f.actor,ISSUER,"other",Instant.now()),
                new SignatureLoginFlow.Proof(c.id(),f.actor,ISSUER,f.actor.toString(),Instant.now().minusSeconds(600)),
                new SignatureLoginFlow.Proof(UUID.randomUUID(),f.actor,ISSUER,f.actor.toString(),Instant.now())))
            assertThrows(ProjectAccessException.class,()->sign(f,t,c,proof,key()));
        assertThrows(ProjectAccessException.class,()->signatures.sign(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),body(c),tag(t.rowVersion()),key(),"0".repeat(64),proof(f,c)));
        assertThrows(ProjectAccessException.class,()->signatures.authenticate(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),UUID.randomUUID(),BINDING));
        assertEquals(0,count(f,"signature_record"));
    }
    @Test void sameIntentReplayIsFrozenAndNewKeyOrReusedKeyCannotCreateSecondSignature()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);String key=key();
        var signed=sign(f,t,c,proof(f,c),key);assertEquals(signed,sign(f,t,c,null,key));
        assertThrows(ProjectAccessException.class,()->sign(f,t,c,proof(f,c),key()));
        var wrong=new SignatureService.Challenge(UUID.randomUUID(),c.expiresAt(),c.authenticationUrl());
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(ProjectAccessException.class,()->sign(f,t,wrong,null,key)).code());
        assertEquals(1,count(f,"signature_record"));assertEquals(1,count(f,"signature_outbox_event"));
    }
    @Test void editsInvalidatePendingChallengeAndNewRevisionNeedsReviewWhileOldSignatureRemainsFrozen()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);var signed=sign(f,t,c,proof(f,c),key());
        var old=t.currentRevision();var changed=tests.appendRevision(f.actor,f.project,t.id(),new TestCaseService.RevisionCommand("Changed",old.description(),old.preconditions(),old.steps().stream().map(s->new TestCaseService.StepCommand(s.stepKey(),s.ordinal(),s.action(),s.expected())).toList()),tag(t.rowVersion()),key());
        assertEquals("DRAFT",reviews.get(f.actor,f.project,t.id(),changed.currentRevision().id(),null,null).state());
        assertFalse(signatures.get(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),changed.currentRevision().id()).canSign());
        assertEquals(signed.signature(),signatures.get(f.viewer,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),old.id()).signature());
        assertThrows(ProjectAccessException.class,()->signatures.authenticate(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),old.id(),c.id(),BINDING));
        assertEquals(old,tests.revision(f.actor,f.project,t.id(),old.id()));
    }
    @Test void revokedDisabledAndExpiredMembershipCannotSignReplayOrRead()throws Exception {
        for(String mutation:List.of("revoke","disable","expire")) {
            var f=fixture();var t=approved(f);var c=prepare(f,t);String key=key();sign(f,t,c,proof(f,c),key);
            try(var db=owner()) {
                if(mutation.equals("disable"))sql(db,"UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.actor);
                else if(mutation.equals("expire"))sql(db,"UPDATE project_member SET valid_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE tenant_id=? AND project_id=? AND principal_id=?",f.tenant,f.project,f.actor);
                else sql(db,"UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND principal_id=?",f.tenant,f.project,f.actor);
            }
            assertThrows(ProjectAccessException.class,()->sign(f,t,c,null,key));
            assertThrows(ProjectAccessException.class,()->signatures.get(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id()));
            assertEquals(1,count(f,"signature_record"));
        }
    }
    @Test void concurrentSameIntentReturnsOneRecordAndDistinctIntentCannotDoubleSign()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);var proof=proof(f,c);String key=key();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<SignatureService.View> call=()->{assertTrue(start.await(10,TimeUnit.SECONDS));return sign(f,t,c,proof,key);};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertEquals(1,count(f,"signature_record"));assertEquals(1,count(f,"signature_outbox_event"));
    }
    @Test void outboxFailureRollsBackSignatureAuditAndIdempotencyAndOriginalIntentRecovers()throws Exception {
        for(String table:List.of("audit_event","signature_outbox_event"))assertSignatureRollback(table);
    }
    private void assertSignatureRollback(String table)throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);String key=key();int before=count(f,"audit_event");
        try(var db=owner()) {db.createStatement().execute("CREATE FUNCTION fail_signature_"+f.tenant.toString().replace("-","")+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+f.tenant+"'::uuid THEN RAISE EXCEPTION 'signature isolated fault'; END IF; RETURN NEW; END $$");
            db.createStatement().execute("CREATE TRIGGER fail_signature_"+f.tenant.toString().replace("-","")+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION fail_signature_"+f.tenant.toString().replace("-","")+"()");}
        try {assertThrows(RuntimeException.class,()->sign(f,t,c,proof(f,c),key));assertEquals(0,count(f,"signature_record"));assertEquals(0,count(f,"signature_outbox_event"));assertEquals(before,count(f,"audit_event"));}
        finally {try(var db=owner()){db.createStatement().execute("DROP TRIGGER fail_signature_"+f.tenant.toString().replace("-","")+" ON "+table);db.createStatement().execute("DROP FUNCTION fail_signature_"+f.tenant.toString().replace("-","")+"()");}}
        assertNotNull(sign(f,t,c,proof(f,c),key).signature());
    }
    @Test void concurrentDistinctChallengesProduceExactlyOneSignatureAndOneConflict()throws Exception {
        var f=fixture();var t=approved(f);var a=prepare(f,t);var b=prepare(f,t);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var futures=List.of(a,b).stream().map(c->pool.submit(()->{
                assertTrue(start.await(10,TimeUnit.SECONDS));
                try {sign(f,t,c,proof(f,c),key());return "signed";}
                catch(ProjectAccessException e){return e.code();}
            })).toList();start.countDown();
            var outcomes=new ArrayList<String>();for(var future:futures)outcomes.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"signed"));assertEquals(1,Collections.frequency(outcomes,"ALREADY_SIGNED"));
        }finally{pool.shutdownNow();}
        assertEquals(1,count(f,"signature_record"));assertEquals(1,count(f,"signature_outbox_event"));
    }
    @Test void concurrentContentRevisionAndSigningSerializeWithoutSigningChangedContent()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);var old=t.currentRevision();
        var command=new TestCaseService.RevisionCommand("Concurrent revision",old.description(),old.preconditions(),old.steps().stream().map(s->new TestCaseService.StepCommand(s.stepKey(),s.ordinal(),s.action(),s.expected())).toList());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var signing=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{return sign(f,t,c,proof(f,c),key()).signature();}catch(ProjectAccessException e){assertTrue(Set.of("SIGNATURE_NOT_ALLOWED","STALE_VERSION").contains(e.code()));return null;}});
            var editing=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return tests.appendRevision(f.actor,f.project,t.id(),command,tag(t.rowVersion()),key());});
            start.countDown();var signed=signing.get(30,TimeUnit.SECONDS);var edited=editing.get(30,TimeUnit.SECONDS);
            assertNotEquals(old.id(),edited.currentRevision().id());assertEquals(old,tests.revision(f.actor,f.project,t.id(),old.id()));
            assertFalse(signatures.get(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),edited.currentRevision().id()).canSign());
            var history=signatures.get(f.viewer,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),old.id()).signature();
            assertEquals(signed,history);if(history!=null)assertEquals(old.id(),history.snapshotId());
        }finally{pool.shutdownNow();}
    }
    @Test void terminalRunAndClosedDeviationSignExactSnapshotsWithoutMutatingTheirVersions()throws Exception {
        var f=fixture();var t=create(f);var set=execution.createSet(f.actor,f.project,"Signature set","",key());
        var instance=execution.addInstance(f.actor,f.project,set.id(),t.id(),t.currentRevision().id(),key());
        var run=execution.createRun(f.actor,f.project,instance.id(),key());var a=run.currentAttempt();
        assertThrows(ProjectAccessException.class,()->signatures.prepare(f.actor,f.project,SignatureRules.Kind.RUN_COMPLETION,run.run().id(),a.id(),empty(),tag(a.rowVersion()),key(),BINDING));
        for(var s:a.steps())execution.saveStep(f.actor,f.project,run.run().id(),a.id(),s.stepKey(),"Observed","PASS",s.rowVersion(),key());
        var finished=execution.finish(f.actor,f.project,run.run().id(),a.id(),execution.attempt(f.actor,f.project,run.run().id(),a.id()).rowVersion(),key());
        var c=signatures.prepare(f.actor,f.project,SignatureRules.Kind.RUN_COMPLETION,run.run().id(),a.id(),empty(),tag(finished.rowVersion()),key(),BINDING);
        var signed=signatures.sign(f.actor,f.project,SignatureRules.Kind.RUN_COMPLETION,run.run().id(),a.id(),body(c),tag(finished.rowVersion()),key(),BINDING,proof(f,c));
        assertEquals(finished,execution.attempt(f.actor,f.project,run.run().id(),a.id()));assertEquals("FINISHED",signed.signature().contentSnapshot().at("/content/attempt/status").textValue());
        execution.rerun(f.actor,f.project,run.run().id(),key());assertEquals(signed.signature(),signatures.get(f.viewer,f.project,SignatureRules.Kind.RUN_COMPLETION,run.run().id(),a.id()).signature());
        var d=defects.create(f.actor,f.project,CoreCommands.JSON.readTree("{\"title\":\"Deviation\",\"description\":\"Observed\",\"severity\":\"HIGH\",\"priority\":\"HIGH\"}"),null,key());
        var resolved=defects.transition(f.actor,f.project,d.id(),"RESOLVED","Resolved",tag(d.rowVersion()),key());
        assertThrows(ProjectAccessException.class,()->signatures.prepare(f.actor,f.project,SignatureRules.Kind.DEVIATION_CLOSURE,d.id(),resolved.currentRevision().id(),empty(),tag(resolved.rowVersion()),key(),BINDING));
        var closed=defects.transition(f.actor,f.project,d.id(),"CLOSED","Closed",tag(resolved.rowVersion()),key());
        var dc=signatures.prepare(f.actor,f.project,SignatureRules.Kind.DEVIATION_CLOSURE,d.id(),closed.currentRevision().id(),empty(),tag(closed.rowVersion()),key(),BINDING);
        var ds=signatures.sign(f.actor,f.project,SignatureRules.Kind.DEVIATION_CLOSURE,d.id(),closed.currentRevision().id(),body(dc),tag(closed.rowVersion()),key(),BINDING,proof(f,dc));
        assertEquals(closed,defects.get(f.actor,f.project,d.id()));assertEquals("CLOSED",ds.signature().contentSnapshot().at("/content/revision/status").textValue());
    }
    @Test void runtimeCannotMutateSignedEvidenceTruncateOrWriteAsViewerAndRlsHidesForeignRecords()throws Exception {
        var f=fixture();var t=approved(f);var c=prepare(f,t);sign(f,t,c,proof(f,c),key());
        try(var db=runtime(f,f.actor)) {
            for(String table:List.of("signature_record","signature_challenge","signature_outbox_event")) {
                assertThrows(SQLException.class,()->db.createStatement().executeUpdate("UPDATE "+table+" SET id=id"));
                assertThrows(SQLException.class,()->db.createStatement().executeUpdate("DELETE FROM "+table));
                assertThrows(SQLException.class,()->db.createStatement().executeUpdate("TRUNCATE "+table+" CASCADE"));
            }
        }
        try(var db=runtime(f,f.viewer)) {
            assertThrows(SQLException.class,()->sql(db,"INSERT INTO signature_challenge SELECT gen_random_uuid(),tenant_id,project_id,kind,object_id,snapshot_id,source_version,?,issuer,subject,display_name,session_binding,content_snapshot,content_digest,created_at FROM signature_challenge WHERE id=?",f.viewer,c.id()));
            assertThrows(SQLException.class,()->sql(db,"INSERT INTO signature_record SELECT gen_random_uuid(),tenant_id,project_id,challenge_id,kind,object_id,snapshot_id,?,content_digest,payload FROM signature_record",f.viewer));
        }
        var other=fixture();try(var db=runtime(other,other.actor);var rs=db.createStatement().executeQuery("SELECT count(*) FROM signature_record")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
    }
    private static final String ISSUER="https://signature-it.example",BINDING="a".repeat(64);
    private SignatureService.Challenge prepare(F f,TestCaseService.TestCaseView t){return signatures.prepare(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),empty(),tag(t.rowVersion()),key(),BINDING);}
    private SignatureService.View sign(F f,TestCaseService.TestCaseView t,SignatureService.Challenge c,SignatureLoginFlow.Proof proof,String key){return signatures.sign(f.actor,f.project,SignatureRules.Kind.TEST_APPROVAL,t.id(),t.currentRevision().id(),body(c),tag(t.rowVersion()),key,BINDING,proof);}
    private static SignatureLoginFlow.Proof proof(F f,SignatureService.Challenge c){return new SignatureLoginFlow.Proof(c.id(),f.actor,ISSUER,f.actor.toString(),Instant.now());}
    private static tools.jackson.databind.JsonNode empty(){return CoreCommands.JSON.readTree("{}");}
    private static tools.jackson.databind.JsonNode body(SignatureService.Challenge c){return CoreCommands.JSON.createObjectNode().put("challengeId",c.id().toString());}
    private static tools.jackson.databind.JsonNode command(String action){return CoreCommands.JSON.createObjectNode().put("action",action);}
    private TestCaseService.TestCaseView create(F f){return tests.create(f.actor,f.project,new TestCaseService.CreateCommand("MANUAL","Sign test","Description","Precondition",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected")),CoreCommands.JSON.readTree("{\"reviewer\":{\"value\":\""+f.actor+"\"}}")),key());}
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
