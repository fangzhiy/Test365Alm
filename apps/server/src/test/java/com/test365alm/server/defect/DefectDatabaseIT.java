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
  assertEquals(2,count("defect_revision",f.project));assertEquals(2,count("defect_outbox_event",f.project));
  assertEquals(2,owner().queryForObject("SELECT count(*) FROM audit_event WHERE project_id=? AND object_type='defect'",Integer.class,f.project));
  var before=snapshot(f.project);
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(ProjectAccessException.class,()->defects.create(f.member,f.project,content("Other"),null,key)).code());
  assertEquals(before,snapshot(f.project));
 }
 @Test void concurrentEditsAndTransitionsHaveOneExactStaleLoser() throws Exception {
  var f=fixture();var d=defects.create(f.member,f.project,content("Concurrent"),null,key());
  var results=concurrent(()->defects.edit(f.member,f.project,d.id(),content("Winner"),"\"1\"",key()));
  assertOneWinner(results);var current=defects.get(f.viewer,f.project,d.id());assertEquals(2,current.rowVersion());
  assertEquals("Winner",current.currentRevision().title());assertEquals(2,defects.history(f.viewer,f.project,d.id(),null,25).items().size());
  results=concurrent(()->defects.transition(f.member,f.project,d.id(),"RESOLVED","fixed","\"2\"",key()));assertOneWinner(results);
  assertEquals("RESOLVED",defects.get(f.viewer,f.project,d.id()).currentRevision().status());
  assertEquals(3,count("defect_revision",f.project));assertEquals(3,count("defect_outbox_event",f.project));
 }
 @Test void lifecycleRejectsIllegalTransitionsAndClosedHistoryCannotBeRewritten() throws Exception {
  var f=fixture();var d=defects.create(f.member,f.project,content("Lifecycle"),null,key());UUID id=d.id();var before=snapshot(f.project);
  assertEquals("INVALID_TRANSITION",assertThrows(ProjectAccessException.class,()->defects.transition(f.member,f.project,id,"CLOSED","x","\"1\"",key())).code());assertEquals(before,snapshot(f.project));
  d=defects.transition(f.member,f.project,d.id(),"IN_PROGRESS","investigating","\"1\"",key());
  d=defects.transition(f.member,f.project,d.id(),"RESOLVED","fixed","\"2\"",key());
  d=defects.transition(f.member,f.project,d.id(),"REOPENED","still broken","\"3\"",key());
  d=defects.transition(f.member,f.project,d.id(),"RESOLVED","second fix","\"4\"",key());
  var closed=defects.transition(f.member,f.project,d.id(),"CLOSED","verified manually","\"5\"",key());
  before=snapshot(f.project);assertEquals("DEFECT_CLOSED",assertThrows(ProjectAccessException.class,()->defects.edit(f.member,f.project,closed.id(),content("rewrite"),"\"6\"",key())).code());
  try(var c=runtimeConnection(f,f.member)){
   for(String sql:List.of("UPDATE defect_revision SET title='rewrite'","DELETE FROM defect_link","TRUNCATE defect_revision")){
    var ex=assertThrows(java.sql.SQLException.class,()->c.createStatement().executeUpdate(sql));assertEquals("42501",ex.getSQLState());
   }
  }
  assertEquals(before,snapshot(f.project));assertEquals(6,defects.history(f.viewer,f.project,closed.id(),null,25).items().size());
 }
 @Test void failingStepSourceAndExplicitRetestsPreserveFailureAndRequireRelatedPass() {
  var f=fixture();var run=failingRun(f);var original=run.currentAttempt();var source=original.steps().get(0);String intent=key();
  var d=defects.fromStep(f.member,f.project,content("Failure"),run.run().id(),original.id(),source.stepKey(),intent);
  assertEquals(d,defects.fromStep(f.member,f.project,content("Failure"),run.run().id(),original.id(),source.stepKey(),intent));
  assertEquals(2,d.links().size());var link=d.links().stream().filter(l->l.kind().equals("RUN_STEP")).findFirst().orElseThrow();
  assertEquals(run.manifest().sourceRevisionId(),link.testRevisionId());assertEquals("observed FAIL",link.actualResult());
  assertEquals("FAIL",link.sourceConclusion());assertEquals("Expected",link.expectedResult());
  d=defects.transition(f.member,f.project,d.id(),"RESOLVED","repair","\"1\"",key());var resolved=d;
  assertEquals("RETEST_PASS_REQUIRED",assertThrows(ProjectAccessException.class,()->defects.transition(f.member,f.project,resolved.id(),"CLOSED","x","\"2\"",key())).code());
  var retry=defects.retest(f.member,f.project,d.id(),"\"2\"",key());complete(f,retry.runId(),retry.attemptId(),"FAIL");
  assertFalse(defects.verification(f.viewer,f.project,d.id()).eligible());
  assertEquals("RETEST_PASS_REQUIRED",assertThrows(ProjectAccessException.class,()->defects.transition(f.member,f.project,resolved.id(),"CLOSED","x","\"2\"",key())).code());
  String retryKey=key();retry=defects.retest(f.member,f.project,d.id(),"\"2\"",retryKey);assertEquals(retry,defects.retest(f.member,f.project,d.id(),"\"2\"",retryKey));
  assertFalse(defects.verification(f.viewer,f.project,d.id()).eligible());complete(f,retry.runId(),retry.attemptId(),"PASS");
  assertEquals(retry.attemptId(),defects.verification(f.viewer,f.project,d.id()).attemptId());
  var closed=defects.transition(f.member,f.project,d.id(),"CLOSED","verified","\"2\"",key());assertEquals("CLOSED",closed.currentRevision().status());
  assertEquals(d.links(),closed.links());assertEquals("FAIL",execution.attempt(f.viewer,f.project,run.run().id(),original.id()).conclusion());
  assertEquals("PASS",execution.attempt(f.viewer,f.project,retry.runId(),retry.attemptId()).conclusion());
  assertEquals(2,defects.retests(f.viewer,f.project,d.id(),null,25).items().size());
 }
 @Test void invalidSourceForeignEndpointsAndViewerSqlWritesHaveNoEffects() throws Exception {
  var f=fixture();var foreign=fixture();var run=failingRun(f);var a=run.currentAttempt();var step=a.steps().get(0);var before=snapshot(f.project);
  assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->defects.fromStep(f.member,f.project,content("Wrong step"),run.run().id(),a.id(),UUID.randomUUID(),key())).code());
  assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->defects.fromStep(foreign.member,foreign.project,content("Wrong scope"),run.run().id(),a.id(),step.stepKey(),key())).code());
  assertEquals("FORBIDDEN",assertThrows(ProjectAccessException.class,()->defects.create(f.viewer,f.project,content("Denied"),null,key())).code());
  assertEquals(before,snapshot(f.project));
  var d=defects.create(f.member,f.project,content("Own"),null,key());before=snapshot(f.project);
  var active=execution.rerun(f.member,f.project,run.run().id(),key());before=snapshot(f.project);
  assertEquals("SOURCE_NOT_FINISHED",assertThrows(ProjectAccessException.class,()->defects.fromStep(f.member,f.project,content("Active"),run.run().id(),active.id(),step.stepKey(),key())).code());assertEquals(before,snapshot(f.project));
  complete(f,run.run().id(),active.id(),"PASS");before=snapshot(f.project);
  assertEquals("NOT_FAILED_SOURCE",assertThrows(ProjectAccessException.class,()->defects.fromStep(f.member,f.project,content("PASS is not failure"),run.run().id(),active.id(),step.stepKey(),key())).code());assertEquals(before,snapshot(f.project));
  try(var c=runtimeConnection(f,f.viewer)){
   // SELECT is allowed, UPDATE USING hides the row from the viewer.
   assertEquals(0,c.createStatement().executeUpdate("UPDATE defect SET row_version=row_version+1 WHERE id='"+d.id()+"'"));
  }
  assertEquals(before,snapshot(f.project));projects.revokeMember(f.admin,f.project,f.member,1L);
  assertEquals("NOT_FOUND",assertThrows(ProjectAccessException.class,()->defects.get(f.member,f.project,d.id())).code());
 }
 @Test void auditAndOutboxFailuresRollbackCreateEditAndTransitionExactly() {
  var f=fixture();
  for(String table:List.of("audit_event","defect_outbox_event")){
   var before=snapshot(f.project);fault(table,()->defects.create(f.member,f.project,content("rollback"),null,key()));assertEquals(before,snapshot(f.project));
  }
  var d=defects.create(f.member,f.project,content("Baseline"),null,key());d=defects.edit(f.member,f.project,d.id(),content("Nonempty history"),"\"1\"",key());var current=d;
  for(String table:List.of("audit_event","defect_outbox_event")){
   var before=snapshot(f.project);fault(table,()->defects.edit(f.member,f.project,current.id(),content("rollback edit"),"\"2\"",key()));assertEquals(before,snapshot(f.project));
   fault(table,()->defects.transition(f.member,f.project,current.id(),"RESOLVED","repair","\"2\"",key()));assertEquals(before,snapshot(f.project));
  }
  assertEquals("RESOLVED",defects.transition(f.member,f.project,d.id(),"RESOLVED","repair","\"2\"",key()).currentRevision().status());
 }
 @Test void concurrentSameKeyCreationAndBoundedPagesHaveNoDuplicateNumbers() throws Exception {
  var f=fixture();String same=key();var results=concurrent(()->defects.create(f.member,f.project,content("Same"),null,same));assertEquals(results.get(0),results.get(1));
  results=concurrent(()->defects.create(f.member,f.project,content("Different"),null,key()));assertEquals(2,results.stream().map(v->((DefectService.DefectView)v).displayNumber()).distinct().count());
  for(int i=0;i<24;i++)defects.create(f.member,f.project,content("Page "+i),null,key());
  var a=defects.list(f.viewer,f.project,"",null,25);var b=defects.list(f.viewer,f.project,"",a.nextCursor(),25);
  assertEquals(25,a.items().size());assertEquals(2,b.items().size());assertNull(b.nextCursor());
  assertEquals(27,java.util.stream.Stream.concat(a.items().stream(),b.items().stream()).map(DefectService.DefectView::id).distinct().count());
 }
 @Test void unrelatedPassActiveRetestAndPreviousResolutionCannotVerifyCurrentCycle() {
  var f=fixture();var run=failingRun(f);var a=run.currentAttempt();var d=defects.fromStep(f.member,f.project,content("Cycle"),run.run().id(),a.id(),a.steps().get(0).stepKey(),key());
  d=defects.transition(f.member,f.project,d.id(),"RESOLVED","first repair","\"1\"",key());
  var unrelated=execution.rerun(f.member,f.project,run.run().id(),key());complete(f,run.run().id(),unrelated.id(),"PASS");assertFalse(defects.verification(f.member,f.project,d.id()).eligible());
  var retest=defects.retest(f.member,f.project,d.id(),"\"2\"",key());assertFalse(defects.verification(f.member,f.project,d.id()).eligible());complete(f,retest.runId(),retest.attemptId(),"PASS");assertTrue(defects.verification(f.member,f.project,d.id()).eligible());
  d=defects.transition(f.member,f.project,d.id(),"REOPENED","new failure","\"2\"",key());assertFalse(defects.verification(f.member,f.project,d.id()).eligible(),"REOPENED cannot advertise previous cycle PASS");
  d=defects.transition(f.member,f.project,d.id(),"RESOLVED","second repair","\"3\"",key());var resolved=d;assertFalse(defects.verification(f.member,f.project,d.id()).eligible());
  assertEquals("RETEST_PASS_REQUIRED",assertThrows(ProjectAccessException.class,()->defects.transition(f.member,f.project,resolved.id(),"CLOSED","old PASS","\"4\"",key())).code());
  retest=defects.retest(f.member,f.project,d.id(),"\"4\"",key());complete(f,retest.runId(),retest.attemptId(),"PASS");assertTrue(defects.verification(f.member,f.project,d.id()).eligible());
  var newer=execution.rerun(f.member,f.project,retest.runId(),key());assertFalse(defects.verification(f.member,f.project,d.id()).eligible(),"new active attempt supersedes old completed PASS");
  complete(f,retest.runId(),newer.id(),"PASS");assertFalse(defects.verification(f.member,f.project,d.id()).eligible(),"unassociated newer PASS is not defect verification");
 }
 @Test void legitimateRuntimeWriterCannotForgeInitialStateAppendCommittedSourceOrAlterSnapshot() throws Exception {
  var f=fixture();var run=failingRun(f);var a=run.currentAttempt();var s=a.steps().get(0);var d=defects.create(f.member,f.project,content("No source yet"),null,key());var before=snapshot(f.project);
  try(var c=runtimeConnection(f,f.member)) {
   assertEquals("test365alm_runtime",query(c,"SELECT current_user"));
   assertEquals(f.member.toString(),query(c,"SELECT current_setting('test365alm.principal_id')"));
   String initial="INSERT INTO defect(tenant_id,project_id,id,display_number,row_version,status,title,severity,priority,current_revision_id,created_by) VALUES('"+f.tenant+"','"+f.project+"','"+UUID.randomUUID()+"',999,1,'CLOSED','forged','HIGH','HIGH','"+UUID.randomUUID()+"','"+f.member+"')";
   sqlDenied(c,initial,"23514","defect initial state must be OPEN version 1");
   sqlDenied(c,sourceInsert(f,d.id(),run,"observed FAIL"),"55006","defect links can only be assembled in creation transaction");
  }
  // A new creation transaction with correct scope, real FKs and unused IDs: only the snapshot differs.
  try(var c=runtimeConnection(f,f.member)) {
   c.setAutoCommit(false);UUID id=UUID.randomUUID(),rev=UUID.randomUUID();
   c.createStatement().executeUpdate("INSERT INTO defect(tenant_id,project_id,id,display_number,row_version,status,title,severity,priority,current_revision_id,created_by) VALUES('"+f.tenant+"','"+f.project+"','"+id+"',999,1,'OPEN','building','HIGH','HIGH','"+rev+"','"+f.member+"')");
   sqlDenied(c,sourceInsert(f,id,run,"forged snapshot"),"23514","defect source must match finished failing step snapshot");c.rollback();
  }
  assertEquals(before,snapshot(f.project));var valid=defects.fromStep(f.member,f.project,content("Valid source"),run.run().id(),a.id(),s.stepKey(),key());assertEquals("observed FAIL",valid.links().stream().filter(l->l.kind().equals("RUN_STEP")).findFirst().orElseThrow().actualResult());
 }
 private static String query(java.sql.Connection c,String sql)throws Exception{try(var rs=c.createStatement().executeQuery(sql)){assertTrue(rs.next());return rs.getString(1);}}
 private static void sqlDenied(java.sql.Connection c,String sql,String state,String reason){var e=assertThrows(java.sql.SQLException.class,()->c.createStatement().executeUpdate(sql));assertEquals(state,e.getSQLState());assertTrue(e.getMessage().contains(reason),e.getMessage());}
 private String sourceInsert(Fixture f,UUID defect,com.test365alm.server.execution.ExecutionService.RunDetail run,String actual){var a=run.currentAttempt();var s=a.steps().get(0);return "INSERT INTO defect_link(tenant_id,project_id,id,defect_id,kind,test_case_id,test_revision_id,run_id,attempt_id,manifest_id,step_key,actual_result,expected_result,source_conclusion,source_version,created_by) VALUES('"+f.tenant+"','"+f.project+"','"+UUID.randomUUID()+"','"+defect+"','RUN_STEP','"+run.manifest().sourceTestCaseId()+"','"+run.manifest().sourceRevisionId()+"','"+run.run().id()+"','"+a.id()+"','"+run.manifest().id()+"','"+s.stepKey()+"','"+actual+"','Expected','FAIL',"+s.rowVersion()+",'"+f.member+"')";}
 private void fault(String table,Runnable call){owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");try{var e=assertThrows(org.springframework.dao.DataAccessException.class,call::run);assertTrue(e.getMostSpecificCause().getMessage().contains("permission denied for table "+table));}finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}}
 private static void assertOneWinner(List<Object> result){assertEquals(1,result.stream().filter(v->v instanceof DefectService.DefectView).count());assertEquals(1,result.stream().filter(v->v instanceof ProjectAccessException e&&e.status().value()==412&&e.code().equals("STALE_VERSION")).count());}
 private List<Object> concurrent(java.util.concurrent.Callable<?> call)throws Exception{
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{var tasks=new ArrayList<java.util.concurrent.Future<Object>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));try{return call.call();}catch(ProjectAccessException e){return e;}}));start.countDown();return List.of(tasks.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }
 private com.test365alm.server.execution.ExecutionService.RunDetail failingRun(Fixture f){
  var req=requirements.create(f.member,f.project,new com.test365alm.server.requirement.RequirementService.CreateCommand("Requirement","body","HIGH"),key());
  var tc=tests.create(f.member,f.project,new com.test365alm.server.testcase.TestCaseService.CreateCommand("MANUAL","Source","","",List.of(new com.test365alm.server.testcase.TestCaseService.StepCommand(null,1,"Act","Expected"))),key());trace.create(f.member,f.project,req.id(),tc.id(),key());
  var set=execution.createSet(f.member,f.project,"R09 set","",key());var instance=execution.addInstance(f.member,f.project,set.id(),tc.id(),tc.currentRevision().id(),key());var run=execution.createRun(f.member,f.project,instance.id(),key());complete(f,run.run().id(),run.currentAttempt().id(),"FAIL");return execution.run(f.member,f.project,run.run().id());
 }
 private void complete(Fixture f,UUID run,UUID attempt,String outcome){var a=execution.attempt(f.member,f.project,run,attempt);for(var s:a.steps())execution.saveStep(f.member,f.project,run,attempt,s.stepKey(),"observed "+outcome,outcome,s.rowVersion(),key());execution.finish(f.member,f.project,run,attempt,execution.attempt(f.member,f.project,run,attempt).rowVersion(),key());}
 private java.sql.Connection runtimeConnection(Fixture f,UUID actor)throws java.sql.SQLException{var c=java.sql.DriverManager.getConnection(PG.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");for(var e:Map.of("tenant_id",f.tenant,"project_id",f.project,"principal_id",actor).entrySet())try(var s=c.prepareStatement("SELECT set_config(?,?,false)")){s.setString(1,"test365alm."+e.getKey());s.setString(2,e.getValue().toString());s.executeQuery().close();}return c;}
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
