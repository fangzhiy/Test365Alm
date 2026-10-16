package com.test365alm.server.defect;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectService;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.trace.TraceLinkService;
import com.test365alm.server.metadata.FieldDefinitionService;

@Timeout(180)
class DefectMigrationUpgradeIT {
 @Configuration @EnableTransactionManagement static class RuntimeConfig {
  @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
  @Bean PlatformTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
 }
 @Test void nonemptyV17RequirementsTestsRunsAndTraceSurviveV18AndRemainUsable() throws Exception {
  try(var pg=new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres")).withDatabaseName("r09_owned_upgrade").withUsername("upgrade_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql")){
   pg.start();var flyway=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("17").load();assertEquals(17,flyway.migrate().migrationsExecuted);
   var owner=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
   UUID actor=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
   owner.update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",actor,"https://r09-upgrade.example.invalid",actor.toString(),"Upgrade");
   owner.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"upgrade-"+tenant,"Upgrade",actor);
   owner.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"Upgrade");owner.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{TENANT_ADMIN}')",tenant,actor);
   var ds=new DriverManagerDataSource(pg.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");
   UUID project,requirement,test,run,attempt,step;String traceKey=UUID.randomUUID().toString();TraceLinkService.LinkView originalLink;
   try(var c=context(ds,false)){
    var projects=c.getBean(ProjectService.class);project=projects.createProject(actor,tenant,domain,"UP-"+UUID.randomUUID(),"Upgrade").id();
    var req=c.getBean(RequirementService.class).create(actor,project,new RequirementService.CreateCommand("Old requirement","old body","HIGH"),key());requirement=req.id();
    var tc=c.getBean(TestCaseService.class).create(actor,project,new TestCaseService.CreateCommand("MANUAL","Old test","old desc","",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected"))),key());test=tc.id();
    c.getBean(FieldDefinitionService.class).create(actor,project,CoreCommands.JSON.readTree("{\"key\":\"old_field\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}"),key());
    originalLink=c.getBean(TraceLinkService.class).create(actor,project,requirement,test,traceKey);
    var e=c.getBean(ExecutionService.class);var set=e.createSet(actor,project,"Old set","",key());var instance=e.addInstance(actor,project,set.id(),test,tc.currentRevision().id(),key());var r=e.createRun(actor,project,instance.id(),key());run=r.run().id();attempt=r.currentAttempt().id();step=r.currentAttempt().steps().get(0).stepKey();
    e.saveStep(actor,project,run,attempt,step,"old FAIL","FAIL",1L,key());e.finish(actor,project,run,attempt,e.attempt(actor,project,run,attempt).rowVersion(),key());
   }
   var tables=owner.query("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",(r,i)->r.getString(1));var before=snapshot(owner,tables);
   for(String table:List.of("requirement","requirement_revision","test_case","test_revision","test_step","execution_run","run_attempt","run_step","execution_event","execution_idempotency","trace_link","trace_link_revision","core_command_idempotency"))assertFalse(before.get(table).isEmpty(),table+" must be nonempty before upgrade");
   var latest=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load();assertEquals(1,latest.migrate().migrationsExecuted);assertEquals(before,snapshot(owner,tables));assertEquals(0,latest.migrate().migrationsExecuted);assertEquals(before,snapshot(owner,tables));
   try(var c=context(ds,true)){
    assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(DefectService.class)));
    try(var connection=ds.getConnection();var stmt=connection.createStatement();var rs=stmt.executeQuery("SELECT current_user")){assertTrue(rs.next());assertEquals("test365alm_runtime",rs.getString(1));}
    assertEquals("old body",c.getBean(RequirementService.class).getAuthorized(actor,project,requirement).body());
    assertEquals(originalLink,c.getBean(TraceLinkService.class).create(actor,project,requirement,test,traceKey));
    assertEquals("FAIL",c.getBean(ExecutionService.class).attempt(actor,project,run,attempt).conclusion());
    var defect=c.getBean(DefectService.class).fromStep(actor,project,CoreCommands.JSON.readTree("{\"title\":\"Upgraded failure\",\"description\":\"text\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}"),run,attempt,step,key());
    assertEquals("old FAIL",defect.links().stream().filter(l->l.kind().equals("RUN_STEP")).findFirst().orElseThrow().actualResult());
   }
  }
 }
 private static AnnotationConfigApplicationContext context(DataSource ds,boolean defects){var c=new AnnotationConfigApplicationContext();c.registerBean(DataSource.class,()->ds);c.register(RuntimeConfig.class,ProjectService.class,CoreCommands.class,RequirementService.class,TestCaseService.class,ExecutionService.class,TraceLinkService.class,FieldDefinitionService.class);if(defects)c.register(DefectService.class,DefectEvidence.class);c.refresh();return c;}
 private static Map<String,List<String>> snapshot(JdbcTemplate db,List<String> tables){var map=new TreeMap<String,List<String>>();for(String table:tables)map.put(table,db.query("SELECT to_jsonb(t)::text FROM \""+table+"\" t ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1)));return map;}
 private static String key(){return UUID.randomUUID().toString();}
}
