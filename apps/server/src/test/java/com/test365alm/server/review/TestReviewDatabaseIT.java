package com.test365alm.server.review;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
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
import com.test365alm.server.testcase.TestCaseService;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class TestReviewDatabaseIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("test_review_it").withUsername("test_review_owner").withPassword("test_review_owner_test_password")
        .withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);
        r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired ProjectService projects;
    @Autowired TestCaseService tests;
    @Autowired TestReviewService reviews;

    @Test void submitRejectResubmitApproveBindsExactContentAndPreservesEveryDecision() throws Exception {
        var f=fixture();var t=create(f);int initialAudits=count("audit_event",f);
        var draft=get(f,f.viewer,t);
        assertEquals("DRAFT",draft.state());assertFalse(draft.canSubmit());assertFalse(draft.canDecide());
        var submitted=act(f,f.member,t,"SUBMIT",t.rowVersion(),key());
        assertEquals("SUBMITTED",submitted.state());assertFalse(submitted.canDecide());
        assertTrue(get(f,f.reviewer,t).canDecide());
        var rejected=act(f,f.reviewer,t,"REJECT",submitted.rowVersion(),key());
        assertEquals("REJECTED",rejected.state());
        var resubmitted=act(f,f.member,t,"SUBMIT",rejected.rowVersion(),key());
        var approved=act(f,f.reviewer,t,"APPROVE",resubmitted.rowVersion(),key());
        assertEquals("APPROVED",approved.state());assertFalse(approved.canSubmit());assertFalse(approved.canDecide());
        assertEquals(4,approved.items().size());
        assertEquals(t.currentRevision().id(),approved.latest().revisionId());
        assertEquals(f.reviewer,approved.latest().actorId());assertEquals(f.reviewer,approved.latest().reviewerId());
        assertTrue(approved.contentDigest().matches("[a-f0-9]{64}"));
        for(var event:approved.items())assertEquals(approved.contentDigest(),event.contentDigest());
        assertEquals(t.currentRevision(),tests.revision(f.viewer,f.project,t.id(),t.currentRevision().id()));
        assertEquals(4,count("test_review_event",f));assertEquals(4,count("test_review_outbox_event",f));
        assertEquals(initialAudits+4,count("audit_event",f));assertEquals(4,count("core_command_idempotency",f));
    }

    @Test void newContentRevisionStartsDraftWhileHistoricalApprovalAndDigestRemainFrozen() throws Exception {
        var f=fixture();var t=create(f);var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key());
        var a=act(f,f.reviewer,t,"APPROVE",s.rowVersion(),key());
        var changed=revise(f,t,a.rowVersion(),"changed purpose");
        var next=get(f,f.member,changed);
        assertEquals("DRAFT",next.state());assertNull(next.latest());assertTrue(next.canSubmit());
        assertNotEquals(a.contentDigest(),next.contentDigest());
        var old=get(f,f.viewer,t);assertEquals("APPROVED",old.state());assertEquals(a.latest(),old.latest());
        assertFalse(old.canSubmit());assertFalse(old.canDecide());
        assertEquals(changed.currentRevision().id(),old.currentRevisionId());
        code("REVIEW_REVISION_NOT_CURRENT",()->act(f,f.reviewer,t,"APPROVE",changed.rowVersion(),key()));
    }

    @Test void onlyDesignatedWriterCanDecideAndViewerForeignScopesOrForgedIdentityHaveNoEffects() throws Exception {
        var f=fixture();var foreign=fixture();var t=create(f);var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key());
        var before=snapshot(f);
        for(UUID actor:List.of(f.member,f.admin,f.viewer))code("FORBIDDEN",()->act(f,actor,t,"APPROVE",s.rowVersion(),key()));
        code("NOT_FOUND",()->reviews.get(foreign.member,foreign.project,t.id(),t.currentRevision().id(),null,null));
        code("NOT_FOUND",()->reviews.get(foreign.member,f.project,t.id(),t.currentRevision().id(),null,null));
        code("INVALID_REQUEST",()->reviews.act(f.reviewer,f.project,t.id(),t.currentRevision().id(),
            CoreCommands.JSON.readTree("{\"action\":\"APPROVE\",\"actorId\":\""+f.reviewer+"\"}"),tag(s.rowVersion()),key()));
        assertEquals(before,snapshot(f));
    }

    @Test void disabledRevokedExpiredOrViewerReviewerCannotActOrReplay() throws Exception {
        for(String mutation:List.of("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",
                "UPDATE project_member SET revoked_at=CURRENT_TIMESTAMP WHERE principal_id=?",
                "UPDATE project_member SET valid_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE principal_id=?",
                "UPDATE project_member SET roles=ARRAY['PROJECT_VIEWER'] WHERE principal_id=?")) {
            var f=fixture();var t=create(f);String key=key();var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key);
            try(var c=owner()){sql(c,mutation,f.reviewer);}
            var before=snapshot(f);
            code("VALIDATION_FAILED",()->act(f,f.member,t,"SUBMIT",t.rowVersion(),key));
            assertThrows(ProjectAccessException.class,()->act(f,f.reviewer,t,"APPROVE",s.rowVersion(),key()));
            assertEquals(before,snapshot(f));
        }
    }

    @Test void frozenReplayRetainsOriginalVersionAndStateAndReauthorizesItsActor() throws Exception {
        var f=fixture();var t=create(f);String key=key();var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key);
        act(f,f.reviewer,t,"APPROVE",s.rowVersion(),key());
        assertEquals(s,act(f,f.member,t,"SUBMIT",t.rowVersion(),key));
        code("IDEMPOTENCY_KEY_REUSED",()->act(f,f.member,t,"SUBMIT",s.rowVersion(),key));
        var changed=revise(f,t,s.rowVersion()+1,"next");
        assertEquals(s,act(f,f.member,t,"SUBMIT",t.rowVersion(),key));
        assertEquals("DRAFT",get(f,f.member,changed).state());
        projects.revokeMember(f.admin,f.project,f.member,1L);
        code("NOT_FOUND",()->act(f,f.member,t,"SUBMIT",t.rowVersion(),key));
    }

    @Test void concurrentDecisionsAndContentEditsUseOneSharedStrongVersion() throws Exception {
        var f=fixture();var t=create(f);var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key());
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Object> approve=()->{gate.await();try{return act(f,f.reviewer,t,"APPROVE",s.rowVersion(),key());}catch(ProjectAccessException e){return e;}};
            Callable<Object> reject=()->{gate.await();try{return act(f,f.reviewer,t,"REJECT",s.rowVersion(),key());}catch(ProjectAccessException e){return e;}};
            var a=pool.submit(approve);var b=pool.submit(reject);gate.countDown();
            var results=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(x->x instanceof TestReviewService.ReviewView).count());
            assertEquals(1,results.stream().filter(x->x instanceof ProjectAccessException e&&e.code().equals("STALE_VERSION")).count());
            code("STALE_VERSION",()->revise(f,t,s.rowVersion(),"stale"));
            assertEquals(2,count("test_review_event",f));
        } finally {pool.shutdownNow();}
    }

    @Test void auditAndOutboxFailureRollbackEventVersionAndIdempotencyAndSameIntentCanRecover() throws Exception {
        for(String table:List.of("audit_event","test_review_outbox_event")) {
            var f=fixture();var t=create(f);String key=key();var before=snapshot(f);
            try(var c=owner()) {
                sql(c,"CREATE FUNCTION fail_review_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+f.project+"'::uuid THEN RAISE EXCEPTION 'isolated review failure'; END IF; RETURN NEW; END $$");
                sql(c,"CREATE TRIGGER fail_review_test BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION fail_review_test()");
            }
            try {assertThrows(RuntimeException.class,()->act(f,f.member,t,"SUBMIT",t.rowVersion(),key));assertEquals(before,snapshot(f));}
            finally {try(var c=owner()){sql(c,"DROP TRIGGER fail_review_test ON "+table);sql(c,"DROP FUNCTION fail_review_test()");}}
            assertEquals("SUBMITTED",act(f,f.member,t,"SUBMIT",t.rowVersion(),key).state());
        }
    }

    @Test void runtimeRlsRejectsForgedBindingViewerWritesAndHistoricalMutationOrIncompleteCommit() throws Exception {
        var f=fixture();var t=create(f);var s=act(f,f.member,t,"SUBMIT",t.rowVersion(),key());var event=s.latest();
        for(String statement:List.of("UPDATE test_review_event SET reason='tampered' WHERE id=?",
                "DELETE FROM test_review_event WHERE id=?"))try(var c=runtime(f,f.reviewer)) {
            assertEquals("42501",assertThrows(SQLException.class,()->sql(c,statement,event.id())).getSQLState());
        }
        try(var c=runtime(f,f.viewer)) {
            assertEquals("42501",assertThrows(SQLException.class,()->sql(c,"TRUNCATE test_review_event")).getSQLState());
            assertThrows(SQLException.class,()->insert(c,f,t,s.rowVersion(),"APPROVE","APPROVED",f.viewer,f.reviewer,s.contentDigest()));
        }
        try(var c=runtime(f,f.member)) {
            assertThrows(SQLException.class,()->insert(c,f,t,s.rowVersion(),"APPROVE","APPROVED",f.member,f.reviewer,s.contentDigest()));
        }
        try(var c=runtime(f,f.reviewer)) {
            assertThrows(SQLException.class,()->insert(c,f,t,s.rowVersion(),"APPROVE","APPROVED",f.reviewer,f.reviewer,"0".repeat(64)));
            c.setAutoCommit(false);
            sql(c,"UPDATE test_case SET row_version=row_version+1 WHERE id=?",t.id());
            insert(c,f,t,s.rowVersion()+1,"APPROVE","APPROVED",f.reviewer,f.reviewer,s.contentDigest());
            assertEquals("23514",assertThrows(SQLException.class,c::commit).getSQLState());c.rollback();
        }
        assertEquals(s.rowVersion(),tests.getAuthorized(f.member,f.project,t.id()).rowVersion());
        assertEquals(1,count("test_review_event",f));
    }

    @Test void boundedHistoryUsesStableVersionCursorAndReadAuthorizationBeforeRecords() throws Exception {
        var f=fixture();var t=create(f);long version=t.rowVersion();
        for(int i=0;i<14;i++) {
            version=act(f,f.member,t,"SUBMIT",version,key()).rowVersion();
            version=act(f,f.reviewer,t,"REJECT",version,key()).rowVersion();
        }
        var first=reviews.get(f.viewer,f.project,t.id(),t.currentRevision().id(),null,25);
        assertEquals(25,first.items().size());assertNotNull(first.nextCursor());
        var second=reviews.get(f.viewer,f.project,t.id(),t.currentRevision().id(),first.nextCursor(),25);
        assertEquals(3,second.items().size());assertNull(second.nextCursor());
        var ids=new HashSet<UUID>();first.items().forEach(e->assertTrue(ids.add(e.id())));second.items().forEach(e->assertTrue(ids.add(e.id())));
        assertEquals(first.latest(),second.latest());
        code("INVALID_REQUEST",()->reviews.get(f.member,f.project,t.id(),t.currentRevision().id(),"0",25));
        code("INVALID_REQUEST",()->reviews.get(f.member,f.project,t.id(),t.currentRevision().id(),null,101));
    }

    private void insert(Connection c,F f,TestCaseService.TestCaseView t,long version,String action,String state,UUID actor,UUID reviewer,String digest)throws SQLException {
        sql(c,"INSERT INTO test_review_event(id,tenant_id,project_id,test_case_id,revision_id,revision_no,row_version,action,state,reviewer_id,actor_id,content_digest,reason) VALUES(?,?,?,?,?,1,?,?,?,?,?,?,'reason')",
            UUID.randomUUID(),f.tenant,f.project,t.id(),t.currentRevision().id(),version,action,state,reviewer,actor,digest);
    }
    private TestCaseService.TestCaseView create(F f) {
        return tests.create(f.member,f.project,new TestCaseService.CreateCommand("MANUAL","review test","description","preconditions",
            List.of(new TestCaseService.StepCommand(null,1,"Open","Opened")),CoreCommands.JSON.readTree("{\"reviewer\":{\"value\":\""+f.reviewer+"\"}}")),key());
    }
    private TestCaseService.TestCaseView revise(F f,TestCaseService.TestCaseView t,long version,String text) {
        var r=t.currentRevision();return tests.appendRevision(f.member,f.project,t.id(),new TestCaseService.RevisionCommand(text,r.description(),r.preconditions(),
            r.steps().stream().map(s->new TestCaseService.StepCommand(s.stepKey(),s.ordinal(),s.action(),s.expected())).toList()),tag(version),key());
    }
    private TestReviewService.ReviewView get(F f,UUID actor,TestCaseService.TestCaseView t) {return reviews.get(actor,f.project,t.id(),t.currentRevision().id(),null,null);}
    private TestReviewService.ReviewView act(F f,UUID actor,TestCaseService.TestCaseView t,String action,long version,String key) {
        return reviews.act(actor,f.project,t.id(),t.currentRevision().id(),CoreCommands.JSON.createObjectNode().put("action",action).put("reason","reason"),tag(version),key);
    }
    private static String key(){return "review-"+UUID.randomUUID();}
    private static String tag(long v){return "\""+v+"\"";}
    private static void code(String code,org.junit.jupiter.api.function.Executable call){assertEquals(code,assertThrows(ProjectAccessException.class,call).code());}
    private Map<String,List<String>> snapshot(F f)throws Exception {
        Map<String,List<String>> result=new TreeMap<>();try(var c=owner()) {
            for(String table:List.of("test_case","test_revision","test_review_event","audit_event","test_review_outbox_event","core_command_idempotency")) {
                var rows=new ArrayList<String>();try(var s=c.prepareStatement("SELECT row_to_json(t)::text FROM "+table+" t WHERE project_id=? ORDER BY row_to_json(t)::text")) {
                    s.setObject(1,f.project);try(var r=s.executeQuery()){while(r.next())rows.add(r.getString(1));}
                }result.put(table,rows);
            }
        }return result;
    }
    private int count(String table,F f)throws Exception{return snapshot(f).get(table).size();}
    private static Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private static Connection runtime(F f,UUID actor)throws SQLException {
        var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");
        try(var s=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,false),set_config('test365alm.project_id',?,false),set_config('test365alm.principal_id',?,false)")) {
            s.setString(1,f.tenant.toString());s.setString(2,f.project.toString());s.setString(3,actor.toString());s.execute();
        }return c;
    }
    private static int sql(Connection c,String query,Object... args)throws SQLException {
        try(var s=c.prepareStatement(query)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();}
    }
    private F fixture()throws Exception {
        UUID admin=UUID.randomUUID(),member=UUID.randomUUID(),reviewer=UUID.randomUUID(),viewer=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
        try(var c=owner()) {
            for(UUID p:List.of(admin,member,reviewer,viewer))sql(c,"INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,'https://review-it.example',?,'Review user')",p,p.toString());
            sql(c,"INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,'Review tenant',?)",tenant,tenant.toString(),admin);
            sql(c,"INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'Review domain')",domain,tenant);
            for(UUID p:List.of(admin,member,reviewer,viewer))sql(c,"INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,p,p.equals(admin)?"{TENANT_ADMIN}":"{MEMBER}");
        }
        UUID project=projects.createProject(admin,tenant,domain,"R-"+UUID.randomUUID(),"Reviews").id();
        projects.putMember(admin,project,member,List.of("PROJECT_MEMBER"),0L,false);
        projects.putMember(admin,project,reviewer,List.of("PROJECT_MEMBER"),0L,false);
        projects.putMember(admin,project,viewer,List.of("PROJECT_VIEWER"),0L,false);
        return new F(admin,member,reviewer,viewer,tenant,project);
    }
    private record F(UUID admin,UUID member,UUID reviewer,UUID viewer,UUID tenant,UUID project) { }
}
