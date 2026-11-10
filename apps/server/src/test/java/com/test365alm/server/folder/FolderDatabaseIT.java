package com.test365alm.server.folder;

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
class FolderDatabaseIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("folder_it").withUsername("folder_owner").withPassword("folder_owner_test_password")
        .withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",()->"test365alm_runtime");
        r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);
        r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired FolderService folders;
    @Autowired ProjectService projects;
    @Autowired TestCaseService tests;
    @Autowired ExecutionService executions;

    @Test void memberCreatesRenamesMovesReordersAndDeletesEmptyFolders() throws Exception {
        Fixture f=fixture(); var a=create(f,"TEST_PLAN",null,"OQ");var b=create(f,"TEST_PLAN",a.id(),"Batch");
        var updated=folders.update(f.member,f.project,b.id(),null,"PQ",9,"\"1\"");
        assertEquals(2,updated.rowVersion());assertNull(updated.parentId());assertEquals(9,updated.sortOrder());
        assertEquals(List.of(a.id(),b.id()),folders.tree(f.viewer,f.project,"TEST_PLAN").stream().map(FolderService.Folder::id).toList());
        folders.delete(f.member,f.project,a.id(),"\"1\"");folders.delete(f.member,f.project,b.id(),"\"2\"");
        assertTrue(folders.tree(f.member,f.project,"TEST_PLAN").isEmpty());
        assertEquals(5,count("folder_outbox_event",f.project));assertEquals(5,count("audit_event",f.project," AND object_type='organization'"));
    }
    @Test void cyclesDuplicateSiblingNamesAndNonemptyFoldersAreRejected() throws Exception {
        Fixture f=fixture();var a=create(f,"TEST_PLAN",null,"OQ");var b=create(f,"TEST_PLAN",a.id(),"Batch");
        assertCode("FOLDER_CYCLE",()->folders.update(f.member,f.project,a.id(),b.id(),"OQ",0,"\"1\""));
        assertCode("FOLDER_CYCLE",()->folders.update(f.member,f.project,a.id(),a.id(),"OQ",0,"\"1\""));
        assertCode("FOLDER_NOT_EMPTY",()->folders.delete(f.member,f.project,a.id(),"\"1\""));
        assertCode("FOLDER_NAME_EXISTS",()->create(f,"TEST_PLAN",null," OQ "));
        create(f,"TEST_LAB",null,"OQ");create(f,"TEST_PLAN",b.id(),"OQ");
    }
    @Test void placementsFilterBeforePaginationAndPreserveSavedRevisions() throws Exception {
        Fixture f=fixture();var a=create(f,"TEST_PLAN",null,"OQ");var b=create(f,"TEST_PLAN",null,"PQ");
        var one=test(f,"One");var two=test(f,"Two");var three=test(f,"Three");
        assertEquals(1,folders.location(f.viewer,f.project,"TEST_PLAN",one.id()).rowVersion());
        var moved=folders.place(f.member,f.project,"TEST_PLAN",one.id(),a.id(),"\"1\"");
        folders.place(f.member,f.project,"TEST_PLAN",three.id(),a.id(),"\"1\"");
        assertCode("FOLDER_NOT_EMPTY",()->folders.delete(f.member,f.project,a.id(),"\"1\""));
        var page=tests.list(f.viewer,f.project,null,null,1,a.id().toString());
        assertEquals(one.id(),page.items().get(0).id());assertNotNull(page.nextCursor());
        assertEquals(three.id(),tests.list(f.viewer,f.project,null,page.nextCursor(),1,a.id().toString()).items().get(0).id());
        assertEquals(List.of(two.id()),tests.list(f.viewer,f.project,null,null,10,"root").items().stream().map(TestCaseService.TestCaseSummary::id).toList());
        folders.place(f.member,f.project,"TEST_PLAN",one.id(),b.id(),"\""+moved.rowVersion()+"\"");
        assertEquals(one,tests.getAuthorized(f.viewer,f.project,one.id()));
        assertCode("STALE_VERSION",()->folders.place(f.member,f.project,"TEST_PLAN",one.id(),null,"\"2\""));
        folders.place(f.member,f.project,"TEST_PLAN",one.id(),null,"\"3\"");
        assertEquals(2,tests.list(f.viewer,f.project,null,null,10,"root").items().size());
    }
    @Test void labPlacementAndRootPaginationUseTheSameModel() throws Exception {
        Fixture f=fixture();var a=create(f,"TEST_LAB",null,"OQ");
        var one=executions.createSet(f.member,f.project,"one","",key());
        var two=executions.createSet(f.member,f.project,"two","",key());
        folders.place(f.member,f.project,"TEST_LAB",one.id(),a.id(),"\"1\"");
        assertEquals(one.id(),executions.pageSets(f.viewer,f.project,null,1,a.id().toString()).items().get(0).id());
        assertEquals(two.id(),executions.pageSets(f.viewer,f.project,null,1,"root").items().get(0).id());
        folders.place(f.member,f.project,"TEST_LAB",one.id(),null,"\"2\"");folders.delete(f.member,f.project,a.id(),"\"1\"");
    }
    @Test void viewerAndTenantAdminWithoutProjectGrantCannotWrite() throws Exception {
        Fixture f=fixture();var a=create(f,"TEST_PLAN",null,"OQ");var one=test(f,"One");
        assertThrows(ProjectAccessException.class,()->folders.create(f.viewer,f.project,"TEST_PLAN",null,"x",0,key()));
        assertThrows(ProjectAccessException.class,()->folders.update(f.viewer,f.project,a.id(),null,"x",0,"\"1\""));
        assertThrows(ProjectAccessException.class,()->folders.delete(f.viewer,f.project,a.id(),"\"1\""));
        assertThrows(ProjectAccessException.class,()->folders.place(f.viewer,f.project,"TEST_PLAN",one.id(),a.id(),"\"1\""));
        try (Connection c=owner()) { sql(c,"DELETE FROM project_member WHERE project_id=? AND principal_id=?",f.project,f.admin); }
        assertThrows(ProjectAccessException.class,()->folders.tree(f.admin,f.project,"TEST_PLAN"));
        try (Connection c=runtime(f,f.viewer)) {
            SQLException e=assertThrows(SQLException.class,()->sql(c,"INSERT INTO project_folder (tenant_id,project_id,id,kind,name) VALUES (?,?,?,'TEST_PLAN','forged')",f.tenant,f.project,UUID.randomUUID()));
            assertEquals("42501",e.getSQLState());
            assertEquals(0,sql(c,"UPDATE project_folder SET name='forged' WHERE id=?",a.id()));
            assertEquals(0,sql(c,"DELETE FROM project_folder WHERE id=?",a.id()));
        }
    }
    @Test void crossProjectAndCrossTreeReferencesAreRejectedByServiceAndDatabase() throws Exception {
        Fixture f=fixture(),other=fixture();var foreign=create(other,"TEST_PLAN",null,"Other");var lab=create(f,"TEST_LAB",null,"Lab");var one=test(f,"One");
        assertThrows(ProjectAccessException.class,()->create(f,"TEST_PLAN",foreign.id(),"bad"));
        assertThrows(ProjectAccessException.class,()->create(f,"TEST_PLAN",lab.id(),"bad"));
        assertThrows(ProjectAccessException.class,()->folders.place(f.member,f.project,"TEST_PLAN",one.id(),foreign.id(),"\"1\""));
        assertThrows(ProjectAccessException.class,()->folders.place(f.member,f.project,"TEST_PLAN",one.id(),lab.id(),"\"1\""));
        assertThrows(ProjectAccessException.class,()->folders.place(f.member,f.project,"TEST_LAB",one.id(),null,"\"1\""));
        try (Connection c=runtime(f,f.member)) {
            assertEquals("23503",assertThrows(SQLException.class,()->sql(c,"INSERT INTO project_folder (tenant_id,project_id,id,kind,parent_id,name) VALUES (?,?,?,'TEST_PLAN',?,'cross')",f.tenant,f.project,UUID.randomUUID(),foreign.id())).getSQLState());
        }
    }
    @Test void createReplayFreezesResponseAndReauthorizesAfterDeletion() throws Exception {
        Fixture f=fixture();String key=key();var a=folders.create(f.member,f.project,"TEST_PLAN",null,"OQ",0,key);
        folders.update(f.member,f.project,a.id(),null,"Renamed",0,"\"1\"");
        assertEquals(a,folders.create(f.member,f.project,"TEST_PLAN",null,"OQ",0,key));
        folders.delete(f.member,f.project,a.id(),"\"2\"");assertEquals(a,folders.create(f.member,f.project,"TEST_PLAN",null,"OQ",0,key));
        assertCode("IDEMPOTENCY_CONFLICT",()->folders.create(f.member,f.project,"TEST_PLAN",null,"different",0,key));
        projects.putMember(f.admin,f.project,f.member,List.of("PROJECT_VIEWER"),1L,false);
        assertThrows(ProjectAccessException.class,()->folders.create(f.member,f.project,"TEST_PLAN",null,"OQ",0,key));
    }
    @Test void outboxFailureRollsBackFolderAuditAndIdempotency() throws Exception {
        Fixture f=fixture();
        try (Connection c=owner()) { sql(c,"CREATE FUNCTION reject_folder_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+f.project+"'::uuid THEN RAISE EXCEPTION 'isolated test failure'; END IF; RETURN NEW; END $$");sql(c,"CREATE TRIGGER reject_folder_test BEFORE INSERT ON folder_outbox_event FOR EACH ROW EXECUTE FUNCTION reject_folder_test()"); }
        try { assertThrows(RuntimeException.class,()->create(f,"TEST_PLAN",null,"fail"));
            assertEquals(0,count("project_folder",f.project));assertEquals(0,count("folder_idempotency",f.project));assertEquals(0,count("audit_event",f.project," AND object_type='organization'"));
        } finally { try (Connection c=owner()) { sql(c,"DROP TRIGGER reject_folder_test ON folder_outbox_event");sql(c,"DROP FUNCTION reject_folder_test()"); } }
    }
    @Test void concurrentOppositeMovesCannotCreateCycleAndStaleWritesFail() throws Exception {
        Fixture f=fixture();var a=create(f,"TEST_PLAN",null,"A");var b=create(f,"TEST_PLAN",null,"B");
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);
        try {
            Callable<Boolean> first=()->{barrier.await();try {folders.update(f.member,f.project,a.id(),b.id(),"A",0,"\"1\"");return true;}catch(ProjectAccessException e){assertEquals("FOLDER_CYCLE",e.code());return false;}};
            Callable<Boolean> second=()->{barrier.await();try {folders.update(f.member,f.project,b.id(),a.id(),"B",0,"\"1\"");return true;}catch(ProjectAccessException e){assertEquals("FOLDER_CYCLE",e.code());return false;}};
            Future<Boolean> x=pool.submit(first),y=pool.submit(second);assertNotEquals(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        var changed=folders.tree(f.member,f.project,"TEST_PLAN").stream().filter(n->n.rowVersion()==2).findFirst().orElseThrow();
        assertCode("STALE_VERSION",()->folders.update(f.member,f.project,changed.id(),null,"stale",0,"\"1\""));
        try (Connection c=runtime(f,f.member)) {
            assertEquals("23514",assertThrows(SQLException.class,()->sql(c,"UPDATE project_folder SET parent_id=id WHERE id=?",a.id())).getSQLState());
        }
    }

    private FolderService.Folder create(Fixture f,String kind,UUID parent,String name) { return folders.create(f.member,f.project,kind,parent,name,0,key()); }
    private TestCaseService.TestCaseView test(Fixture f,String title) { return tests.create(f.member,f.project,new TestCaseService.CreateCommand("MANUAL",title,"","",List.of(new TestCaseService.StepCommand(null,1,"Open","Opened"))),key()); }
    private static String key() { return "folder-"+UUID.randomUUID(); }
    private static void assertCode(String code,org.junit.jupiter.api.function.Executable call) { assertEquals(code,assertThrows(ProjectAccessException.class,call).code()); }
    private int count(String table,UUID project) throws Exception { return count(table,project,""); }
    private int count(String table,UUID project,String suffix) throws Exception { try(Connection c=owner();var s=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE project_id=?"+suffix)){s.setObject(1,project);try(var r=s.executeQuery()){r.next();return r.getInt(1);}} }
    private static Connection owner() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static Connection runtime(Fixture f,UUID actor) throws SQLException { Connection c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");try(var s=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,false),set_config('test365alm.project_id',?,false),set_config('test365alm.principal_id',?,false)")){s.setString(1,f.tenant.toString());s.setString(2,f.project.toString());s.setString(3,actor.toString());s.execute();}return c; }
    private static int sql(Connection c,String sql,Object... args) throws SQLException { try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();} }
    private Fixture fixture() throws Exception {
        UUID admin=UUID.randomUUID(),member=UUID.randomUUID(),viewer=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
        try(Connection c=owner()) {
            for(UUID p:List.of(admin,member,viewer))sql(c,"INSERT INTO principal (id,issuer,subject,display_name) VALUES (?,'https://folder-it.example',?,'Folder test')",p,p.toString());
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
