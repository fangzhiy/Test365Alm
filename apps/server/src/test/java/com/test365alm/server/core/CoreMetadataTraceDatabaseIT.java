package com.test365alm.server.core;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.metadata.FieldDefinitionService;
import com.test365alm.server.project.*;

@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class CoreMetadataTraceDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("r08_owned").withUsername("r08_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void ds(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size",()->"3");
    }
    @Autowired FieldDefinitionService fields;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate runtime;
    @Autowired com.test365alm.server.trace.TraceLinkService links;
    @Autowired com.test365alm.server.requirement.RequirementService requirements;
    @Autowired com.test365alm.server.testcase.TestCaseService tests;
    @Autowired com.test365alm.server.execution.ExecutionService execution;
    @Test void concurrentFieldRevisionsHaveOneExactStaleLoserAndTraceCreatesHaveOneIdentity() throws Exception {
        var f=fixture();var d=CoreCommands.JSON.readTree("{\"key\":\"note\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}");
        var first=fields.create(f.admin,f.project,d,key());
        var results=concurrent(()->fields.revise(f.admin,f.project,first.id(),d,"\"1\"",key()));
        assertEquals(1,results.stream().filter(x->x instanceof FieldDefinitionService.FieldView).count());
        assertEquals(1,results.stream().filter(x->x instanceof ProjectAccessException e && e.code().equals("STALE_VERSION")).count());
        assertEquals(2,fields.get(f.viewer,f.project,first.id(),null).rowVersion());
        var req=requirement(f);var test=test(f);String replay=key();
        results=concurrent(()->links.create(f.member,f.project,req.id(),test.id(),replay));
        assertEquals(results.get(0),results.get(1));assertEquals(1,links.list(f.viewer,f.project,req.id(),null,25).items().size());
        var other=test(f);results=concurrent(()->links.create(f.member,f.project,req.id(),other.id(),key()));
        assertEquals(1,results.stream().filter(x->x instanceof com.test365alm.server.trace.TraceLinkService.LinkView).count());
        assertEquals(1,results.stream().filter(x->x instanceof ProjectAccessException e && e.code().equals("DUPLICATE_LINK")).count());
        assertEquals(2,links.coverage(f.member,f.project,req.id()).linkedTests());
    }
    @Test void traceForeignDeletedEndpointsRlsAndAuditOutboxRollbackHaveNoEffects() throws Exception {
        var f=fixture();var foreign=fixture();var req=requirement(f);var test=test(f);var foreignTest=test(foreign);
        var before=snapshot(f.project);
        for(UUID bad:List.of(UUID.randomUUID(),foreignTest.id()))assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->links.create(f.member,f.project,req.id(),bad,key())).code());
        owner().update("UPDATE requirement SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",req.id());
        assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->links.create(f.member,f.project,req.id(),test.id(),key())).code());
        owner().update("UPDATE requirement SET deleted_at=NULL WHERE id=?",req.id());
        assertEquals(before,snapshot(f.project));
        for(String table:List.of("audit_event","core_outbox_event")){
            owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");
            try{var e=assertThrows(org.springframework.dao.DataAccessException.class,()->links.create(f.member,f.project,req.id(),test.id(),key()));assertTrue(e.getMostSpecificCause().getMessage().contains("permission denied for table "+table));}
            finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
            assertEquals(before,snapshot(f.project));
        }
        var link=links.create(f.member,f.project,req.id(),test.id(),key());before=snapshot(f.project);
        for(String table:List.of("audit_event","core_outbox_event")){
            owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");
            try{var e=assertThrows(org.springframework.dao.DataAccessException.class,()->links.invalidate(f.member,f.project,link.id(),"\"1\"",key()));assertTrue(e.getMostSpecificCause().getMessage().contains("permission denied for table "+table));}
            finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
            assertEquals(before,snapshot(f.project));
        }
        assertEquals("INACTIVE",links.invalidate(f.member,f.project,link.id(),"\"1\"",key()).state());
    }
    @Test void traceCoverageRequiresCurrentFrozenRevisionsPassAndNoActiveRerun() throws Exception {
        var f=fixture();var req=requirement(f);var test=test(f);links.create(f.member,f.project,req.id(),test.id(),key());
        var set=execution.createSet(f.member,f.project,"R08 coverage","",key());var instance=execution.addInstance(f.member,f.project,set.id(),test.id(),test.currentRevision().id(),key());
        var run=execution.createRun(f.member,f.project,instance.id(),key());var attempt=run.currentAttempt();
        var step=attempt.steps().get(0);
        execution.saveStep(f.member,f.project,run.run().id(),attempt.id(),step.stepKey(),"confirmed","PASS",step.rowVersion(),key());
        var updated=execution.attempt(f.member,f.project,run.run().id(),attempt.id());
        execution.finish(f.member,f.project,run.run().id(),attempt.id(),updated.rowVersion(),key());
        assertEquals(1,links.coverage(f.viewer,f.project,req.id()).passedTests());
        execution.rerun(f.member,f.project,run.run().id(),key());
        assertEquals(0,links.coverage(f.viewer,f.project,req.id()).passedTests());assertEquals(1,links.coverage(f.viewer,f.project,req.id()).activeTests());
    }
    @Test void fieldAndTraceKeysetPagesContinueWithoutScopeLeakOrDuplicates() throws Exception {
        var f=fixture();var req=requirement(f);
        for(int i=0;i<27;i++){
            fields.create(f.admin,f.project,CoreCommands.JSON.readTree("{\"key\":\"field_"+i+"\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}"),key());
            links.create(f.member,f.project,req.id(),test(f).id(),key());
        }
        var a=fields.list(f.viewer,f.project,null,25);assertEquals(25,a.items().size());assertNotNull(a.nextCursor());
        var b=fields.list(f.viewer,f.project,a.nextCursor(),25);assertEquals(2,b.items().size());assertNull(b.nextCursor());
        assertEquals(27,java.util.stream.Stream.concat(a.items().stream(),b.items().stream()).map(FieldDefinitionService.FieldView::id).distinct().count());
        var c=links.list(f.viewer,f.project,req.id(),null,25);var e=links.list(f.viewer,f.project,req.id(),c.nextCursor(),25);
        assertEquals(27,java.util.stream.Stream.concat(c.items().stream(),e.items().stream()).map(com.test365alm.server.trace.TraceLinkService.LinkView::id).distinct().count());assertNull(e.nextCursor());
    }
    private com.test365alm.server.requirement.RequirementService.RequirementView requirement(Fixture f){return requirements.create(f.member,f.project,new com.test365alm.server.requirement.RequirementService.CreateCommand("R08 req","body","HIGH"),key());}
    private com.test365alm.server.testcase.TestCaseService.TestCaseView test(Fixture f){return tests.create(f.member,f.project,new com.test365alm.server.testcase.TestCaseService.CreateCommand("MANUAL","R08 test","","",List.of(new com.test365alm.server.testcase.TestCaseService.StepCommand(null,1,"Open","Opened"))),key());}
    private List<Object> concurrent(java.util.concurrent.Callable<?> action)throws Exception{
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{List<java.util.concurrent.Future<Object>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));try{return action.call();}catch(ProjectAccessException e){return e;}}));start.countDown();return List.of(futures.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS),futures.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
    }
    @Test void traceLinksHaveRealEndpointsFrozenReplayAndNonPassUnexecutedCoverage() throws Exception {
        var f=fixture();var req=requirements.create(f.member,f.project,new com.test365alm.server.requirement.RequirementService.CreateCommand("R08 req","body","HIGH"),key());
        var test=tests.create(f.member,f.project,new com.test365alm.server.testcase.TestCaseService.CreateCommand("MANUAL","R08 test","","",List.of(new com.test365alm.server.testcase.TestCaseService.StepCommand(null,1,"Open","Opened"))),key());
        String key=key();var link=links.create(f.member,f.project,req.id(),test.id(),key);
        assertEquals(link,links.create(f.member,f.project,req.id(),test.id(),key));
        assertEquals(req.currentRevisionId(),link.requirementRevisionId());assertEquals(test.currentRevision().id(),link.testRevisionId());
        assertEquals(1,links.coverage(f.viewer,f.project,req.id()).linkedTests());assertEquals(0,links.coverage(f.member,f.project,req.id()).passedTests());
        var before=snapshot(f.project);
        assertEquals("DUPLICATE_LINK",assertThrows(ProjectAccessException.class,()->links.create(f.member,f.project,req.id(),test.id(),key())).code());
        assertEquals("FORBIDDEN",assertThrows(ProjectAccessException.class,()->links.create(f.viewer,f.project,req.id(),test.id(),key())).code());
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(ProjectAccessException.class,()->links.create(f.member,f.project,req.id(),UUID.randomUUID(),key)).code());
        assertEquals(before,snapshot(f.project));
        var inactive=links.invalidate(f.member,f.project,link.id(),"\"1\"",key());assertEquals("INACTIVE",inactive.state());
        assertEquals(link,links.create(f.member,f.project,req.id(),test.id(),key));
        assertEquals(0,links.coverage(f.viewer,f.project,req.id()).linkedTests());
        assertEquals(2,links.list(f.viewer,f.project,req.id(),null,1).items().get(0).rowVersion());
        projects.revokeMember(f.admin,f.project,f.member,1L);
        // Existing ProjectService deliberately conceals revoked project existence.
        assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->links.create(f.member,f.project,req.id(),test.id(),key)).code());
    }
    @Test void fieldDefinitionsPersistImmutableRevisionsAndValidateExactValues() throws Exception {
        var f=fixture();String key=key();var definition=CoreCommands.JSON.readTree("{\"key\":\"cost\",\"type\":\"NUMBER\",\"required\":true,\"precision\":5,\"scale\":2,\"maximum\":\"100.00\"}");
        var first=fields.create(f.admin,f.project,definition,key);
        assertEquals(first,fields.create(f.admin,f.project,definition,key));
        assertEquals("12.34",fields.validate(f.member,f.project,first.id(),1,CoreCommands.JSON.readTree("\"12.34\"")).value().textValue());
        var invalid=assertThrows(ProjectAccessException.class,()->fields.validate(f.viewer,f.project,first.id(),1,CoreCommands.JSON.readTree("\"101.00\"")));
        assertEquals("VALIDATION_FAILED",invalid.code());
        var revised=CoreCommands.JSON.readTree(definition.toString().replace("100.00","200.00"));
        var second=fields.revise(f.admin,f.project,first.id(),revised,"\"1\"",key());
        assertEquals(2,second.rowVersion());assertEquals(first,fields.get(f.member,f.project,first.id(),1L));
        var before=snapshot(f.project);
        assertEquals("STALE_VERSION",assertThrows(ProjectAccessException.class,()->fields.revise(f.admin,f.project,first.id(),definition,"\"1\"",key())).code());
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(ProjectAccessException.class,()->fields.create(f.admin,f.project,revised,key)).code());
        assertEquals(before,snapshot(f.project));
        assertEquals(2,count("SELECT count(*) FROM core_outbox_event WHERE project_id=?",f.project));
    }
    @Test void fieldAdminBoundaryRlsAndForeignScopesRejectWithoutEffects() throws Exception {
        var f=fixture();var foreign=fixture();var d=CoreCommands.JSON.readTree("{\"key\":\"note\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}");
        var first=fields.create(f.admin,f.project,d,key());var before=snapshot(f.project);
        for(UUID actor:List.of(f.member,f.viewer))assertEquals("FORBIDDEN",assertThrows(ProjectAccessException.class,()->fields.create(actor,f.project,d,key())).code());
        assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->fields.get(foreign.member,foreign.project,first.id(),null)).code());
        try(Connection c=runtimeConnection()){
            context(c,f,f.viewer);
            var ex=assertThrows(SQLException.class,()->sql(c,"INSERT INTO field_definition(tenant_id,project_id,id,field_key,data_type,row_version,created_by) VALUES(?,?,?,'illegal','TEXT',1,?)",f.tenant,f.project,UUID.randomUUID(),f.viewer));assertEquals("42501",ex.getSQLState());
            context(c,f,f.admin);
            ex=assertThrows(SQLException.class,()->sql(c,"UPDATE field_definition_revision SET definition='{}'::jsonb WHERE field_id=?",first.id()));assertEquals("42501",ex.getSQLState());
        }
        assertEquals(before,snapshot(f.project));
    }
    @Test void fieldAuditAndOutboxFailuresRollbackTheWholeCommand() throws Exception {
        var f=fixture();var d=CoreCommands.JSON.readTree("{\"key\":\"note\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}");
        for(String table:List.of("audit_event","core_outbox_event")){
            var before=snapshot(f.project);owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");
            try {var ex=assertThrows(org.springframework.dao.DataAccessException.class,()->fields.create(f.admin,f.project,d,key()));assertTrue(ex.getMostSpecificCause().getMessage().contains("permission denied for table "+table));}
            finally {owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
            assertEquals(before,snapshot(f.project));
        }
        assertEquals(1,fields.create(f.admin,f.project,d,key()).rowVersion());
    }
    private record Fixture(UUID admin,UUID member,UUID viewer,UUID tenant,UUID project){}
    private Fixture fixture(){
        UUID admin=principal(),member=principal(),viewer=principal(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();var o=owner();
        o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"r08-"+tenant,"R08",admin);
        o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"R08");
        for(UUID p:List.of(admin,member,viewer))o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,p,p.equals(admin)?"{TENANT_ADMIN}":"{MEMBER}");
        var project=projects.createProject(admin,tenant,domain,"R08-"+UUID.randomUUID(),"R08");
        projects.putMember(admin,project.id(),member,List.of("PROJECT_MEMBER"),0L,false);projects.putMember(admin,project.id(),viewer,List.of("PROJECT_VIEWER"),0L,false);
        return new Fixture(admin,member,viewer,tenant,project.id());
    }
    private UUID principal(){UUID id=UUID.randomUUID();owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",id,"https://r08.example.invalid",id.toString(),"R08");return id;}
    private JdbcTemplate owner(){return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));}
    private int count(String sql,Object...args){return owner().queryForObject(sql,Integer.class,args);}
    private Map<String,List<String>> snapshot(UUID project){var m=new TreeMap<String,List<String>>();for(String table:List.of("field_definition","field_definition_revision","trace_link","trace_link_revision","audit_event","core_outbox_event","core_command_idempotency"))m.put(table,owner().query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1),project));return m;}
    private Connection runtimeConnection() throws SQLException{return DriverManager.getConnection(PG.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");}
    private void context(Connection c,Fixture f,UUID actor)throws SQLException{for(var e:Map.of("tenant_id",f.tenant,"project_id",f.project,"principal_id",actor).entrySet())try(var s=c.prepareStatement("SELECT set_config(?,?,false)")){s.setString(1,"test365alm."+e.getKey());s.setString(2,e.getValue().toString());s.executeQuery().close();}}
    private void sql(Connection c,String sql,Object...args)throws SQLException{try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}}
    private static String key(){return UUID.randomUUID().toString();}
}
