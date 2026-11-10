package com.test365alm.server.review;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
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

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class TestReviewMigrationUpgradeIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("review_upgrade").withUsername("review_upgrade_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");r.add("spring.flyway.enabled",()->"false");
    }
    @Autowired TestReviewService reviews;
    @Autowired TestCaseService tests;
    private Flyway flyway(String target){return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).target(target).load();}

    @Test void nonemptyPreReviewUpgradePreservesFrozenContentAndRuntimeReviewsExactRevision()throws Exception {
        assertEquals(23,flyway("23").migrate().migrationsExecuted);
        UUID actor=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID(),project=UUID.randomUUID(),test=UUID.randomUUID(),revision=UUID.randomUUID(),step=UUID.randomUUID();
        Map<String,List<String>> before;
        try(var c=owner()) {
            c.setAutoCommit(false);
            sql(c,"INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,'https://review-upgrade.example',?,'Upgrade reviewer')",actor,actor.toString());
            sql(c,"INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,'Upgrade tenant',?)",tenant,tenant.toString(),actor);
            sql(c,"INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'Upgrade domain')",domain,tenant);
            sql(c,"INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,ARRAY['MEMBER'])",tenant,actor);
            sql(c,"INSERT INTO project(id,tenant_id,domain_id,code,name,created_by) VALUES(?,?,?,?,'Upgrade project',?)",project,tenant,domain,project.toString(),actor);
            sql(c,"INSERT INTO project_member(tenant_id,project_id,principal_id,roles) VALUES(?,?,?,ARRAY['PROJECT_MEMBER'])",tenant,project,actor);
            sql(c,"INSERT INTO test_case(tenant_id,project_id,id,display_number,created_by) VALUES(?,?,?,1,?)",tenant,project,test,actor);
            String fields="{\"reviewer\":{\"kind\":\"SYSTEM\",\"revision\":1,\"definition\":{\"key\":\"reviewer\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":36},\"value\":\""+actor+"\"}}";
            sql(c,"INSERT INTO test_revision(tenant_id,project_id,test_case_id,id,revision_no,title,description,preconditions,created_by,business_fields) VALUES(?,?,?,?,1,'Legacy review case','Frozen description','Frozen preconditions',?,?::jsonb)",tenant,project,test,revision,actor,fields);
            sql(c,"INSERT INTO test_step(tenant_id,project_id,test_case_id,revision_id,step_key,ordinal,action,expected) VALUES(?,?,?,?,?,1,'Frozen action','Frozen expected')",tenant,project,test,revision,step);
            sql(c,"UPDATE test_revision SET sealed_at=CURRENT_TIMESTAMP WHERE id=?",revision);
            sql(c,"UPDATE test_case SET current_revision_id=? WHERE id=?",revision,test);
            c.commit();before=snapshot(c,project);c.commit();
        }
        assertEquals(1,flyway("25").migrate().migrationsExecuted);
        assertEquals(0,flyway("25").migrate().migrationsExecuted);
        try(var c=owner()){assertEquals(before,snapshot(c,project));}
        var saved=tests.getAuthorized(actor,project,test);
        var submitted=reviews.act(actor,project,test,revision,CoreCommands.JSON.readTree("{\"action\":\"SUBMIT\"}"),"\"1\"","upgrade-review-submit");
        var approved=reviews.act(actor,project,test,revision,CoreCommands.JSON.readTree("{\"action\":\"APPROVE\"}"),"\"2\"","upgrade-review-approve");
        assertEquals("APPROVED",approved.state());assertEquals(submitted.contentDigest(),approved.contentDigest());
        assertEquals(saved.currentRevision(),tests.revision(actor,project,test,revision));
        var changed=tests.appendRevision(actor,project,test,new TestCaseService.RevisionCommand("New content","Frozen description","Frozen preconditions",
            List.of(new TestCaseService.StepCommand(step,1,"Frozen action","Frozen expected"))),"\"3\"","upgrade-review-revision");
        assertEquals("DRAFT",reviews.get(actor,project,test,changed.currentRevision().id(),null,null).state());
        assertEquals(approved.latest(),reviews.get(actor,project,test,revision,null,null).latest());
    }
    private static Map<String,List<String>> snapshot(Connection c,UUID project)throws SQLException {
        var result=new TreeMap<String,List<String>>();for(String table:List.of("test_case","test_revision","test_step","audit_event","test_case_outbox_event","test_case_idempotency"))
            try(var s=c.prepareStatement("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text")) {
                s.setObject(1,project);try(var r=s.executeQuery()){var rows=new ArrayList<String>();while(r.next())rows.add(r.getString(1));result.put(table,rows);}
            }return result;
    }
    private static Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private static void sql(Connection c,String query,Object... values)throws SQLException{try(var s=c.prepareStatement(query)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
}
