package com.test365alm.server.defect;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;

@ActiveProfiles("integration") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class DefectDatabaseIT {
 @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
  .withDatabaseName("r09_owned_defect").withUsername("r09_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
 @DynamicPropertySource static void ds(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");r.add("spring.datasource.password",()->"r03_isolated_test_role_only");r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);}
 @Autowired DefectService defects;@Autowired ProjectService projects;@Autowired JdbcTemplate runtime;
 @Autowired com.test365alm.server.testcase.TestCaseService tests;@Autowired com.test365alm.server.requirement.RequirementService requirements;
 @Autowired com.test365alm.server.execution.ExecutionService execution;@Autowired com.test365alm.server.trace.TraceLinkService trace;
 @Test void memberCreatesImmutableDefectAndFrozenReplayWithAtomicEvidence() {
  var f=fixture();String key=key();var d=defects.create(f.member,f.project,content("First"),null,key);
  assertEquals(d,defects.create(f.member,f.project,content("First"),null,key));
  assertEquals(1,d.displayNumber());assertEquals("OPEN",d.currentRevision().status());
  var changed=defects.edit(f.member,f.project,d.id(),content("Edited"),"\"1\"",key());
  assertEquals(2,changed.rowVersion());assertEquals("First",defects.revision(f.viewer,f.project,d.id(),d.currentRevision().id()).title());
  assertEquals(d,defects.create(f.member,f.project,content("First"),null,key));
  assertEquals(2,count("defect_revision",f.project));assertEquals(2,count("defect_outbox_event",f.project));assertEquals(2,count("audit_event",f.project));
  var before=snapshot(f.project);
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(ProjectAccessException.class,()->defects.create(f.member,f.project,content("Other"),null,key)).code());
  assertEquals(before,snapshot(f.project));
 }
 private record Fixture(UUID admin,UUID member,UUID viewer,UUID tenant,UUID project){}
 private Fixture fixture(){
  var o=owner();UUID admin=principal(),member=principal(),viewer=principal(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
  o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"r09-"+tenant,"R09",admin);o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"R09");
  for(UUID p:List.of(admin,member,viewer))o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,p,p.equals(admin)?"{TENANT_ADMIN}":"{MEMBER}");
  var project=projects.createProject(admin,tenant,domain,"R09-"+UUID.randomUUID(),"R09");projects.putMember(admin,project.id(),member,List.of("PROJECT_MEMBER"),0L,false);projects.putMember(admin,project.id(),viewer,List.of("PROJECT_VIEWER"),0L,false);
  return new Fixture(admin,member,viewer,tenant,project.id());
 }
 private UUID principal(){UUID id=UUID.randomUUID();owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",id,"https://r09.example.invalid",id.toString(),"R09");return id;}
 private JdbcTemplate owner(){return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));}
 private int count(String table,UUID project){return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
 private Map<String,List<String>> snapshot(UUID project){var m=new TreeMap<String,List<String>>();for(String table:List.of("defect","defect_revision","defect_link","defect_retest","defect_number_allocator","defect_outbox_event","audit_event","core_command_idempotency"))m.put(table,owner().query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1),project));return m;}
 private static tools.jackson.databind.JsonNode content(String title){return CoreCommands.JSON.readTree("{\"title\":\""+title+"\",\"description\":\"reproduce\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}");}
 private static String key(){return UUID.randomUUID().toString();}
}
