package com.test365alm.server.release;

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
import com.test365alm.server.defect.*;

@Timeout(180)
class ReleaseMigrationUpgradeIT {
    @Configuration @EnableTransactionManagement static class RuntimeConfig {
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
    }

    @Test void nonemptyV18RequirementsTestsExecutionDefectsAndTraceSurviveV19AndRemainUsable() throws Exception {
        try(var pg=new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("r10_owned_upgrade").withUsername("r10_upgrade_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql")) {
            pg.start();assertEquals(18,Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("18").load().migrate().migrationsExecuted);
            var owner=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
            UUID admin=UUID.randomUUID(),member=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
            for(UUID actor:List.of(admin,member))owner.update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",actor,"https://r10-upgrade.example.invalid",actor.toString(),"Upgrade");
            owner.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"upgrade-"+tenant,"Upgrade",admin);owner.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"Upgrade");
            owner.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{TENANT_ADMIN}')",tenant,admin);owner.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{MEMBER}')",tenant,member);
            var ds=new DriverManagerDataSource(pg.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");
            UUID project,requirement,test,run,attempt,defect;String traceKey=key(),defectKey=key();TraceLinkService.LinkView originalLink;DefectService.DefectView originalDefect;
            try(var c=context(ds,false)) {
                var projects=c.getBean(ProjectService.class);project=projects.createProject(admin,tenant,domain,"UP-"+key(),"Upgrade").id();projects.putMember(admin,project,member,List.of("PROJECT_MEMBER"),0L,false);
                var requirements=c.getBean(RequirementService.class);var req=requirements.create(member,project,new RequirementService.CreateCommand("Original requirement","original body","HIGH"),key());requirement=req.id();
                requirements.update(member,project,requirement,new RequirementService.UpdateCommand("Revised requirement","revised body",null),"\"1\"",key());
                var tests=c.getBean(TestCaseService.class);var tc=tests.create(member,project,new TestCaseService.CreateCommand("MANUAL","Original test","old description","",List.of(new TestCaseService.StepCommand(null,1,"First act","First expected"),new TestCaseService.StepCommand(null,2,"Second act","Second expected"))),key());test=tc.id();
                tc=tests.appendRevision(member,project,test,new TestCaseService.RevisionCommand("Revised test","revised description","",List.of(new TestCaseService.StepCommand(tc.currentRevision().steps().get(1).stepKey(),1,"Second act","Second expected"),new TestCaseService.StepCommand(tc.currentRevision().steps().get(0).stepKey(),2,"First act","First expected"))),"\"1\"",key());
                c.getBean(FieldDefinitionService.class).create(admin,project,CoreCommands.JSON.readTree("{\"key\":\"upgrade_note\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":30}"),key());
                originalLink=c.getBean(TraceLinkService.class).create(member,project,requirement,test,traceKey);
                var e=c.getBean(ExecutionService.class);var set=e.createSet(member,project,"Old set","",key());var instance=e.addInstance(member,project,set.id(),test,tc.currentRevision().id(),key());var r=e.createRun(member,project,instance.id(),key());run=r.run().id();attempt=r.currentAttempt().id();
                for(var step:r.currentAttempt().steps())e.saveStep(member,project,run,attempt,step.stepKey(),"V18 FAIL "+step.ordinal(),"FAIL",step.rowVersion(),key());e.finish(member,project,run,attempt,e.attempt(member,project,run,attempt).rowVersion(),key());
                var d=c.getBean(DefectService.class);originalDefect=d.fromStep(member,project,defectBody(),run,attempt,r.currentAttempt().steps().get(0).stepKey(),defectKey);defect=originalDefect.id();
                d.edit(member,project,defect,CoreCommands.JSON.readTree("{\"title\":\"Edited old defect\",\"description\":\"V18 history\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}"),"\"1\"",key());
            }
            var tables=owner.query("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",(r,i)->r.getString(1));var before=snapshot(owner,tables);
            for(String table:List.of("tenant","project","project_member","requirement","requirement_revision","test_case","test_revision","test_step","trace_link","trace_link_revision","execution_run","execution_manifest","execution_manifest_step","run_attempt","run_step","execution_event","execution_idempotency","defect","defect_revision","defect_link","defect_outbox_event","audit_event","core_command_idempotency"))assertFalse(before.get(table).isEmpty(),table+" must contain actual V18 business data before upgrade");
            assertEquals(2,owner.queryForObject("SELECT count(*) FROM requirement_revision WHERE project_id=?",Integer.class,project));assertEquals(2,owner.queryForObject("SELECT count(*) FROM test_revision WHERE project_id=?",Integer.class,project));assertEquals(2,owner.queryForObject("SELECT count(*) FROM defect_revision WHERE project_id=?",Integer.class,project));
            var latest=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load();assertEquals(1,latest.migrate().migrationsExecuted);assertEquals(before,snapshot(owner,tables),"every old table and column must survive byte-for-byte JSON representation");assertEquals(0,latest.migrate().migrationsExecuted);assertEquals(before,snapshot(owner,tables));
            try(var c=context(ds,true)) {
                var releases=c.getBean(ReleaseService.class);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(releases));
                try(var connection=ds.getConnection();var stmt=connection.createStatement();var rs=stmt.executeQuery("SELECT current_user,rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")){assertTrue(rs.next());assertEquals("test365alm_runtime",rs.getString(1));assertFalse(rs.getBoolean(2));assertFalse(rs.getBoolean(3));}
                assertEquals("revised body",c.getBean(RequirementService.class).getAuthorized(member,project,requirement).body());assertEquals(originalLink,c.getBean(TraceLinkService.class).create(member,project,requirement,test,traceKey));
                assertEquals("FAIL",c.getBean(ExecutionService.class).attempt(member,project,run,attempt).conclusion());
                assertEquals(originalDefect,c.getBean(DefectService.class).fromStep(member,project,defectBody(),run,attempt,originalDefect.links().stream().filter(l->l.kind().equals("RUN_STEP")).findFirst().orElseThrow().stepKey(),defectKey),"old key returns frozen result rather than edited defect");
                var release=releases.create(admin,project,CoreCommands.JSON.readTree("{\"name\":\"Upgraded release\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-31\"}"),key());
                releases.addScope(admin,project,release.id(),CoreCommands.JSON.readTree("{\"kind\":\"REQUIREMENT\",\"requirementId\":\""+requirement+"\"}"),key());releases.addScope(admin,project,release.id(),CoreCommands.JSON.readTree("{\"kind\":\"TEST_CASE\",\"testCaseId\":\""+test+"\"}"),key());
                var quality=releases.evaluate(admin,project,release.id(),key());assertEquals(1,quality.coverage().numerator());assertEquals(1,quality.completed().numerator());assertEquals(0,quality.effectivePass().numerator());assertEquals(1,quality.openDefects().numerator());assertEquals(quality,releases.snapshot(member,project,release.id(),quality.id()));
                for(String table:tables)if(!Set.of("audit_event","core_command_idempotency").contains(table))assertEquals(before.get(table),snapshot(owner,List.of(table)).get(table),"new M06 commands must not rewrite "+table);
            }
        }
    }
    private static AnnotationConfigApplicationContext context(DataSource ds,boolean releases){var c=new AnnotationConfigApplicationContext();c.registerBean(DataSource.class,()->ds);c.register(RuntimeConfig.class,ProjectService.class,CoreCommands.class,RequirementService.class,TestCaseService.class,ExecutionService.class,TraceLinkService.class,FieldDefinitionService.class,DefectService.class,DefectEvidence.class);if(releases)c.register(ReleaseService.class,ReleaseMetrics.class);c.refresh();return c;}
    private static Map<String,List<String>> snapshot(JdbcTemplate db,List<String> tables){var m=new TreeMap<String,List<String>>();for(String table:tables)m.put(table,db.query("SELECT to_jsonb(t)::text FROM \""+table+"\" t ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1)));return m;}
    private static tools.jackson.databind.JsonNode defectBody(){return CoreCommands.JSON.readTree("{\"title\":\"Old failure\",\"description\":\"V18 source\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}");}
    private static String key(){return UUID.randomUUID().toString();}
}
