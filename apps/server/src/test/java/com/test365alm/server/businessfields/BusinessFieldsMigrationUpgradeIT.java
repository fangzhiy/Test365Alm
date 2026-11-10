package com.test365alm.server.businessfields;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Nonempty V22 -> V23 upgrade, old columns unchanged, no production datasource. */
@Testcontainers @Timeout(180)
class BusinessFieldsMigrationUpgradeIT {
 @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(
  "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
  .withDatabaseName("business_fields_upgrade").withUsername("business_fields_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
 private Flyway flyway(String target){return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").target(target).load();}
 @Test void nonemptyV22UpgradePreservesAllHistoricalContentAndRuntimeCannotRewriteIt() throws Exception {
  assertEquals(22,flyway("22").migrate().migrationsExecuted);
  UUID actor=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID(),project=UUID.randomUUID(),test=UUID.randomUUID(),revision=UUID.randomUUID(),req=UUID.randomUUID(),reqRevision=UUID.randomUUID(),defect=UUID.randomUUID(),defectRevision=UUID.randomUUID();
  try(var c=owner()){
   c.setAutoCommit(false);
   sql(c,"INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,'https://business-fields-upgrade.example',?,'Upgrade member')",actor,actor.toString());
   sql(c,"INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,'Upgrade tenant',?)",tenant,tenant.toString(),actor);
   sql(c,"INSERT INTO domain(id,tenant_id,name) VALUES(?,?,'Upgrade domain')",domain,tenant);
   sql(c,"INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,ARRAY['MEMBER'])",tenant,actor);
   sql(c,"INSERT INTO project(id,tenant_id,domain_id,code,name,created_by) VALUES(?,?,?,?,'Upgrade project',?)",project,tenant,domain,project.toString(),actor);
   sql(c,"INSERT INTO project_member(tenant_id,project_id,principal_id,roles) VALUES(?,?,?,ARRAY['PROJECT_MEMBER'])",tenant,project,actor);
   sql(c,"INSERT INTO test_case(tenant_id,project_id,id,display_number,test_type,row_version,created_by) VALUES(?,?,?,1,'MANUAL',1,?)",tenant,project,test,actor);
   sql(c,"INSERT INTO test_revision(tenant_id,project_id,test_case_id,id,revision_no,title,description,preconditions,created_by,sealed_at) VALUES(?,?,?,?,1,'Legacy test','Original purpose remains description','Original precondition',?,CURRENT_TIMESTAMP)",tenant,project,test,revision,actor);
   sql(c,"UPDATE test_case SET current_revision_id=? WHERE id=?",revision,test);
   sql(c,"INSERT INTO requirement(tenant_id,project_id,id,display_number,row_version,created_by) VALUES(?,?,?,1,1,?)",tenant,project,req,actor);
   sql(c,"INSERT INTO requirement_revision(tenant_id,project_id,requirement_id,id,revision_no,title,body,priority,created_by) VALUES(?,?,?,?,1,'Legacy requirement','Original body','HIGH',?)",tenant,project,req,reqRevision,actor);
   sql(c,"UPDATE requirement SET current_revision_id=? WHERE id=?",reqRevision,req);
   sql(c,"INSERT INTO defect(tenant_id,project_id,id,display_number,row_version,title,status,severity,priority,current_revision_id,created_by) VALUES(?,?,?,1,1,'Legacy deviation','OPEN','HIGH','HIGH',?,?)",tenant,project,defect,defectRevision,actor);
   sql(c,"INSERT INTO defect_revision(tenant_id,project_id,defect_id,id,revision_no,title,description,status,severity,priority,created_by) VALUES(?,?,?,?,1,'Legacy deviation','Original details','OPEN','HIGH','HIGH',?)",tenant,project,defect,defectRevision,actor);
   c.commit();
   Map<String,String> before=snapshot(c);
   assertEquals(1,flyway("23").migrate().migrationsExecuted);
   assertEquals(before,snapshot(c));assertEquals(0,flyway("23").migrate().migrationsExecuted);
   for(String table:List.of("test_revision","requirement_revision","defect_revision"))try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table+" WHERE business_fields='{}'::jsonb")){r.next();assertEquals(1,r.getInt(1));}
  }
  try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only")){
   sqlContext(c,tenant,project,actor);
   for(String table:List.of("test_revision","requirement_revision","defect_revision"))assertThrows(SQLException.class,()->sql(c,"UPDATE "+table+" SET business_fields='{}'::jsonb"));
  }
 }
 private static Map<String,String> snapshot(Connection c)throws SQLException{var result=new LinkedHashMap<String,String>();for(String table:List.of("test_case","test_revision","requirement","requirement_revision","defect","defect_revision"))try(var s=c.createStatement();var r=s.executeQuery("SELECT string_agg((to_jsonb(t)-'business_fields')::text,'|' ORDER BY id) FROM "+table+" t")){r.next();result.put(table,r.getString(1));}return result;}
 private static Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
 private static void sqlContext(Connection c,UUID tenant,UUID project,UUID actor)throws SQLException{try(var s=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,false),set_config('test365alm.project_id',?,false),set_config('test365alm.principal_id',?,false)")){s.setString(1,tenant.toString());s.setString(2,project.toString());s.setString(3,actor.toString());s.execute();}}
 private static void sql(Connection c,String sql,Object...values)throws SQLException{try(var s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}}
}
