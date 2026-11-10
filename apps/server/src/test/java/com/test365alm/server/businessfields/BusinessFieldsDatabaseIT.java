package com.test365alm.server.businessfields;

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
import com.test365alm.server.project.*;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.execution.ExecutionService;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class BusinessFieldsDatabaseIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("business_fields_it").withUsername("business_fields_owner").withPassword("business_fields_owner_test_password")
        .withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);
        r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired com.test365alm.server.defect.DefectService defects;
    @Autowired com.test365alm.server.requirement.RequirementService requirements;
    @Autowired com.test365alm.server.metadata.FieldDefinitionService fields;
    @Autowired com.test365alm.server.history.HistoryService history;
    @Autowired ProjectService projects;
    @Autowired TestCaseService tests;
    @Autowired ExecutionService executions;

    @Test void fieldsPersistOnExactRevisionReplayAndHistory() throws Exception {
        var f=fixture();String key=key();var input=create(f,"original");
        var first=tests.create(f.member,f.project,input,key);
        assertEquals("original",first.currentRevision().businessFields().path("test_purpose").path("value").asText());
        var set=executions.createSet(f.member,f.project,"Frozen","",key());
        var instance=executions.addInstance(f.member,f.project,set.id(),first.id(),first.currentRevision().id(),key());
        var run=executions.createRun(f.member,f.project,instance.id(),key());
        String frozenManifest;try(var c=owner();var stmt=c.prepareStatement("SELECT to_jsonb(m)::text FROM execution_manifest m WHERE id=?")){stmt.setObject(1,run.run().manifestId());try(var rs=stmt.executeQuery()){rs.next();frozenManifest=rs.getString(1);}}
        var second=revise(f,first,"new purpose",key());
        try(var c=owner();var stmt=c.prepareStatement("SELECT to_jsonb(m)::text FROM execution_manifest m WHERE id=?")){stmt.setObject(1,run.run().manifestId());try(var rs=stmt.executeQuery()){rs.next();assertEquals(frozenManifest,rs.getString(1));}}
        assertEquals(2,second.rowVersion());
        assertEquals(first,tests.create(f.member,f.project,input,key));
        assertEquals(first.currentRevision(),tests.revision(f.member,f.project,first.id(),first.currentRevision().id()));
        assertEquals("new purpose",tests.getAuthorized(f.member,f.project,first.id()).currentRevision().businessFields().path("test_purpose").path("value").asText());
        assertTrue(history.history(f.member,f.project,"test_case",first.id(),null,25).items().stream().flatMap(e->e.changes().stream()).anyMatch(c->c.field().equals("businessFields.test_purpose")&&"original".equals(c.before())&&"new purpose".equals(c.after())));
        assertCode("STALE_VERSION",()->revise(f,first,"conflicting",key()));
        assertCode("IDEMPOTENCY_KEY_REUSED",()->tests.create(f.member,f.project,create(f,"other"),key));
    }
    @Test void customDefinitionRevisionsRemainFrozenAndCrossProjectIsRejected() throws Exception {
        var f=fixture();var other=fixture();
        var one=fields.create(f.admin,f.project,json("{\"key\":\"test_purpose\",\"type\":\"ENUM\",\"required\":true,\"options\":[\"OQ\",\"PQ\"]}"),key());
        var input=new TestCaseService.CreateCommand("MANUAL","configured","","",List.of(),json("{\"test_purpose\":{\"fieldId\":\""+one.id()+"\",\"revision\":1,\"value\":\"OQ\"}}"));
        var saved=tests.create(f.member,f.project,input,key());
        var d=json("{\"key\":\"test_purpose\",\"type\":\"ENUM\",\"required\":true,\"options\":[\"PQ\"]}");
        fields.revise(f.admin,f.project,one.id(),d,"\"1\"",key());
        assertEquals("OQ",tests.revision(f.member,f.project,saved.id(),saved.currentRevision().id()).businessFields().path("test_purpose").path("value").asText());
        assertThrows(ProjectAccessException.class,()->tests.create(other.member,other.project,input,key()));
        var invalid=new TestCaseService.CreateCommand("MANUAL","invalid","","",List.of(),json(input.businessFields().toString().replace("\"revision\":1","\"revision\":2")));
        assertCode("VALIDATION_FAILED",()->tests.create(f.member,f.project,invalid,key()));
    }
    @Test void reviewerRejectsForeignDisabledExpiredAndRevokedIdentitiesAndReplayReauthorizes() throws Exception {
        var f=fixture();var other=fixture();
        var saved=tests.create(f.member,f.project,create(f,"purpose"),key());
        for(UUID bad:List.of(other.member,UUID.randomUUID())) {
            var input=new TestCaseService.CreateCommand("MANUAL","bad","","",List.of(),json("{\"reviewer\":{\"value\":\""+bad+"\"}}"));
            assertCode("VALIDATION_FAILED",()->tests.create(f.member,f.project,input,key()));
        }
        try(var c=owner()){sql(c,"UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.viewer);}
        assertCode("VALIDATION_FAILED",()->tests.create(f.member,f.project,reviewer(f.viewer),key()));
        try(var c=owner()){sql(c,"UPDATE principal SET disabled_at=NULL WHERE id=?",f.viewer);sql(c,"UPDATE project_member SET valid_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE project_id=? AND principal_id=?",f.project,f.viewer);}
        assertCode("VALIDATION_FAILED",()->tests.create(f.member,f.project,reviewer(f.viewer),key()));
        try(var c=owner()){sql(c,"UPDATE project_member SET valid_until=NULL,revoked_at=CURRENT_TIMESTAMP WHERE project_id=? AND principal_id=?",f.project,f.viewer);}
        assertCode("VALIDATION_FAILED",()->tests.create(f.member,f.project,reviewer(f.viewer),key()));
        String replayKey=key();var input=create(f,"replay");tests.create(f.member,f.project,input,replayKey);
        projects.revokeMember(f.admin,f.project,f.member,1L);
        assertThrows(ProjectAccessException.class,()->tests.create(f.member,f.project,input,replayKey));
        assertThrows(ProjectAccessException.class,()->tests.getAuthorized(other.member,f.project,saved.id()));
    }
    @Test void requirementAndDefectFieldsPersistAndStatusCopiesFrozenValues() throws Exception {
        var f=fixture();var value=json("{\"equipment_system_id\":{\"value\":\"EQ-42\"},\"owning_department\":{\"value\":\"QA\"}}");
        var req=requirements.create(f.member,f.project,new com.test365alm.server.requirement.RequirementService.CreateCommand("spec","body","HIGH",value),key());
        String key=key();var patch=new com.test365alm.server.requirement.RequirementService.UpdateCommand(null,null,null,json("{\"owning_department\":{\"value\":\"Engineering\"}}"));
        var next=requirements.update(f.member,f.project,req.id(),patch,"\"1\"",key);
        assertEquals(next,requirements.update(f.member,f.project,req.id(),patch,"\"1\"",key));
        assertEquals("QA",requirements.revision(f.member,f.project,req.id(),req.currentRevisionId()).businessFields().path("owning_department").path("value").asText());
        var body=json("{\"title\":\"deviation\",\"description\":\"details\",\"severity\":\"HIGH\",\"priority\":\"HIGH\",\"businessFields\":{\"occurrence_department\":{\"value\":\"QA\"},\"occurrence_location\":{\"value\":\"Line 1\"},\"reviewer\":{\"value\":\""+f.viewer+"\"}}}");
        var defect=defects.create(f.member,f.project,body,null,key());
        var moved=defects.transition(f.member,f.project,defect.id(),"IN_PROGRESS","investigate","\"1\"",key());
        assertEquals(defect.currentRevision().businessFields(),moved.currentRevision().businessFields());
        assertEquals(defect.currentRevision(),defects.revision(f.member,f.project,defect.id(),defect.currentRevision().id()));
    }
    @Test void runtimeSqlCannotRewriteSealedFieldsOrInsertForeignReviewerAndViewerCannotWrite() throws Exception {
        var f=fixture();var foreign=fixture();var saved=tests.create(f.member,f.project,create(f,"purpose"),key());
        try(var c=runtime(f,f.member)) {
            assertThrows(SQLException.class,()->sql(c,"UPDATE test_revision SET business_fields='{}' WHERE id=?",saved.currentRevision().id()));
            var bad=com.test365alm.server.core.CoreCommands.JSON.createObjectNode();
            bad.set("reviewer",com.test365alm.server.core.CoreCommands.JSON.createObjectNode().put("kind","SYSTEM").put("revision",1).set("definition",json("{\"key\":\"reviewer\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":36}")));
            ((tools.jackson.databind.node.ObjectNode)bad.get("reviewer")).put("value",foreign.member.toString());
            assertThrows(SQLException.class,()->sql(c,"INSERT INTO test_revision(tenant_id,project_id,test_case_id,id,revision_no,title,description,preconditions,created_by,sealed_at,business_fields) VALUES(?,?,?,?,2,'bad','','',?,NULL,?::jsonb)",f.tenant,f.project,saved.id(),UUID.randomUUID(),f.member,bad.toString()));
        }
        assertThrows(ProjectAccessException.class,()->tests.create(f.viewer,f.project,create(f,"viewer"),key()));
    }
    @Test void outboxFailureRollsBackBusinessFieldsAndIdempotency() throws Exception {
        var f=fixture();var saved=tests.create(f.member,f.project,create(f,"before"),key());
        try(var c=owner()){sql(c,"CREATE FUNCTION reject_business_fields_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+f.project+"'::uuid THEN RAISE EXCEPTION 'isolated test failure'; END IF; RETURN NEW; END $$");sql(c,"CREATE TRIGGER reject_business_fields_test BEFORE INSERT ON test_case_outbox_event FOR EACH ROW EXECUTE FUNCTION reject_business_fields_test()");}
        try { assertThrows(RuntimeException.class,()->revise(f,saved,"after",key()));assertEquals(saved,tests.getAuthorized(f.member,f.project,saved.id()));assertEquals(1,count("test_revision",f.project)); }
        finally {try(var c=owner()){sql(c,"DROP TRIGGER reject_business_fields_test ON test_case_outbox_event");sql(c,"DROP FUNCTION reject_business_fields_test()");}}
    }
    private static tools.jackson.databind.JsonNode json(String s){return com.test365alm.server.core.CoreCommands.JSON.readTree(s);}
    private TestCaseService.CreateCommand create(Fixture f,String purpose){return new TestCaseService.CreateCommand("MANUAL","test","description","",List.of(new TestCaseService.StepCommand(null,1,"Open","Opened")),json("{\"test_purpose\":{\"value\":\""+purpose+"\"},\"reviewer\":{\"value\":\""+f.viewer+"\"}}"));}
    private TestCaseService.CreateCommand reviewer(UUID actor){return new TestCaseService.CreateCommand("MANUAL","test","","",List.of(),json("{\"reviewer\":{\"value\":\""+actor+"\"}}"));}
    private TestCaseService.TestCaseView revise(Fixture f,TestCaseService.TestCaseView old,String purpose,String key){var r=old.currentRevision();return tests.appendRevision(f.member,f.project,old.id(),new TestCaseService.RevisionCommand(r.title(),r.description(),r.preconditions(),r.steps().stream().map(s->new TestCaseService.StepCommand(s.stepKey(),s.ordinal(),s.action(),s.expected())).toList(),json("{\"test_purpose\":{\"value\":\""+purpose+"\"}}")),"\""+old.rowVersion()+"\"",key);}
    private static String key() { return "business-fields-"+UUID.randomUUID(); }
    private static void assertCode(String code,org.junit.jupiter.api.function.Executable call) { assertEquals(code,assertThrows(ProjectAccessException.class,call).code()); }
    private int count(String table,UUID project) throws Exception { return count(table,project,""); }
    private int count(String table,UUID project,String suffix) throws Exception { try(Connection c=owner();var s=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE project_id=?"+suffix)){s.setObject(1,project);try(var r=s.executeQuery()){r.next();return r.getInt(1);}} }
    private static Connection owner() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static Connection runtime(Fixture f,UUID actor) throws SQLException { Connection c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");try(var s=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,false),set_config('test365alm.project_id',?,false),set_config('test365alm.principal_id',?,false)")){s.setString(1,f.tenant.toString());s.setString(2,f.project.toString());s.setString(3,actor.toString());s.execute();}return c; }
    private static int sql(Connection c,String sql,Object... args) throws SQLException { try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();} }
    private Fixture fixture() throws Exception {
        UUID admin=UUID.randomUUID(),member=UUID.randomUUID(),viewer=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
        try(Connection c=owner()) {
            for(UUID p:List.of(admin,member,viewer))sql(c,"INSERT INTO principal (id,issuer,subject,display_name) VALUES (?,'https://business-fields-it.example',?,'Folder test')",p,p.toString());
            sql(c,"INSERT INTO tenant (id,code,name,created_by) VALUES (?,?,'Folder tenant',?)",tenant,tenant.toString(),admin);
            sql(c,"INSERT INTO domain (id,tenant_id,name) VALUES (?,?,'Folder domain')",domain,tenant);
            for(UUID p:List.of(admin,member,viewer))sql(c,"INSERT INTO tenant_member (tenant_id,principal_id,roles) VALUES (?,?,?::text[])",tenant,p,p.equals(admin)?"{TENANT_ADMIN}":"{MEMBER}");
        }
        UUID project=projects.createProject(admin,tenant,domain,"F-"+UUID.randomUUID(),"Folders").id();
        projects.putMember(admin,project,member,List.of("PROJECT_MEMBER"),0L,false);
        projects.putMember(admin,project,viewer,List.of("PROJECT_VIEWER"),0L,false);
        return new Fixture(admin,member,viewer,tenant,project);
    }
    private record Fixture(UUID admin,UUID member,UUID viewer,UUID tenant,UUID project) {}
}
