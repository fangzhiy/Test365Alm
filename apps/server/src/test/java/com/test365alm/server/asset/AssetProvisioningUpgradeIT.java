package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

/** Real V20 data and the repository's formal Python/psql operator entrypoint, not helper DDL. */
@Timeout(300)
class AssetProvisioningUpgradeIT {
    @Configuration @EnableTransactionManagement static class RuntimeConfig {
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
    private static final tools.jackson.databind.JsonNode EMPTY=CoreCommands.JSON.readTree("{}");
    private static final List<String> ASSET_TABLES=List.of("asset","asset_revision","upload_session","scan_result","attachment_link","asset_outbox_event","audit_event","core_command_idempotency");

    @Test void upgradedDatabaseUsesProvisionedRestrictedScannerLogin() throws Exception {
        try(var pg=ownedDatabase("upgrade")) {
            pg.start();var owner=owner(pg);
            assertScramHostAuthentication(owner);
            verifyLibpqEmptyServiceIsNotUnset(pg);
            assertEquals(20,migration(pg,"20").migrate().migrationsExecuted);
            String originalLogin=login(),originalSecret=secret();
            assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,originalLogin,originalSecret));
            var runtime=runtime(pg);var originalScanner=scanner(pg,originalLogin,originalSecret);
            assertScannerIdentity(originalScanner,originalLogin);
            AssetTestInfrastructure.start();
            Fixture fixture;AssetService.Upload first,second;AssetService.Attachment attachment;
            String firstKey=key();byte[] firstBytes=bytes("Original V20 evidence one"),secondBytes=bytes("Original V20 evidence two");
            try(var context=context(runtime,originalScanner)) {
                fixture=fixture(owner,context);var assets=context.getBean(AssetService.class);
                first=published(assets,fixture,firstBytes,null,firstKey);
                attachment=assets.createAttachment(fixture.member,fixture.project,CoreCommands.JSON.createObjectNode()
                    .put("kind","REQUIREMENT").put("requirementId",fixture.requirement.toString())
                    .put("assetId",first.assetId().toString()).put("assetRevisionId",first.revisionId().toString()),key());
                second=published(assets,fixture,secondBytes,first.assetId(),key());
                assertEquals(2,assets.get(fixture.member,fixture.project,first.assetId()).currentRevision().revisionNumber());
                assertEquals(first.revisionId(),attachment.assetRevisionId());
            }
            var tables=owner.query("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",(r,i)->r.getString(1));
            var before=snapshot(owner,tables);
            for(String table:ASSET_TABLES)assertFalse(before.get(table).isEmpty(),"V20 nonempty fixture: "+table);
            assertEquals(2,owner.queryForObject("SELECT count(*) FROM asset_revision WHERE asset_id=?",Integer.class,first.assetId()));
            assertEquals(3L,owner.queryForObject("SELECT next_revision_no FROM asset WHERE id=?",Long.class,first.assetId()));
            assertEquals(second.revisionId(),owner.queryForObject("SELECT current_revision_id FROM asset WHERE id=?",UUID.class,first.assetId()));
            var latest=migration(pg,"21");assertEquals(1,latest.migrate().migrationsExecuted);
            assertEquals(before,snapshot(owner,tables),"V21 must preserve every V20 row and field, including storage keys and frozen response JSON");
            assertEquals(0,latest.migrate().migrationsExecuted);assertEquals(before,snapshot(owner,tables));

            String newLogin=login(),newSecret=secret();
            assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,newLogin,newSecret));
            String roleBefore=roleSnapshot(owner);
            assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,newLogin,newSecret));
            assertTrue(roleBefore.equals(roleSnapshot(owner)),"safe repeat must preserve role flags, credentials, memberships, settings and privileges");
            var scanner=scanner(pg,newLogin,newSecret);assertScannerIdentity(scanner,newLogin);
            try(var context=context(runtime,scanner)) {
                var assets=context.getBean(AssetService.class);
                assertEquals(first,assets.complete(fixture.member,fixture.project,first.id(),EMPTY,firstKey),"old successful key keeps its frozen revision 1 response");
                assertEquals(2,assets.get(fixture.member,fixture.project,first.assetId()).currentRevision().revisionNumber());
                assertDownloaded(assets,fixture,first,firstBytes);assertDownloaded(assets,fixture,second,secondBytes);
                var links=assets.attachments(fixture.member,fixture.project,"REQUIREMENT",fixture.requirement,null,25);
                assertEquals(attachment,links.items().get(0),"frozen link stays on revision 1 rather than following current revision");
                var third=published(assets,fixture,bytes("New V21 evidence three"),first.assetId(),key());
                assertEquals(3,assets.revision(fixture.member,fixture.project,third.assetId(),third.revisionId()).revisionNumber());
                assertEquals(4L,owner.queryForObject("SELECT next_revision_no FROM asset WHERE id=?",Long.class,first.assetId()));
                assertEquals(third.revisionId(),owner.queryForObject("SELECT current_revision_id FROM asset WHERE id=?",UUID.class,first.assetId()));
                for(String table:List.of("asset_revision","upload_session","scan_result","attachment_link","asset_outbox_event","audit_event","core_command_idempotency"))
                    assertTrue(snapshot(owner,List.of(table)).get(table).containsAll(before.get(table)),"old committed rows stay unchanged after publication: "+table);
                assertDownloaded(assets,fixture,first,firstBytes);assertDownloaded(assets,fixture,second,secondBytes);
                assertScannerTargetRejections(owner,context,scanner,fixture);
            }
            var counts=new TreeMap<String,Integer>();for(String table:ASSET_TABLES)counts.put(table,before.get(table).size());
            System.out.println("R11 scanner provision: V20 -> V21 preserved nonempty rows "+counts+"; real CLEAN publication appended revision 3");
            assertFalse(pg.getLogs().contains("SCRAM-SHA-256$"),"owned PostgreSQL logs must not expose any provisioning verifier");
            assertFalse(pg.getLogs().contains(originalSecret));assertFalse(pg.getLogs().contains(newSecret));
        }
    }

    @Test void scannerProvisioningPreservesExistingGroupLoginAndRejectsUnsafeRoles() throws Exception {
        try(var pg=ownedDatabase("roles")) {
            pg.start();assertEquals(20,migration(pg,"20").migrate().migrationsExecuted);var owner=owner(pg);
            assertScramHostAuthentication(owner);
            String existing=login(),password=secret();assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,existing,password));
            // Legacy development wiring makes the capability role itself a LOGIN. Preserve it.
            owner.execute("DO $fixture$ DECLARE verifier text; BEGIN SELECT rolpassword INTO verifier FROM pg_authid WHERE rolname='"+existing+"'; EXECUTE format('ALTER ROLE test365alm_scanner LOGIN PASSWORD %L',verifier); END $fixture$");
            String groupBefore=groupSnapshot(owner);String child=login(),childSecret=secret();
            assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,child,childSecret));
            assertEquals("test365alm_scanner",new JdbcTemplate(scanner(pg,"test365alm_scanner",password)).queryForObject("SELECT current_user",String.class));
            String repeated=roleSnapshot(owner);assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,child,childSecret));
            assertTrue(repeated.equals(roleSnapshot(owner)),"idempotent repeat must not modify credentials or grants");
            assertEquals(1,owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='test365alm_scanner' AND rolcanlogin",Integer.class));
            assertTrue(groupBefore.equals(groupSnapshot(owner)),"legacy group flags, credential and direct privileges must be preserved");
            assertNotEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,child,secret()),"wrong repeat password must not be silently rotated");
            assertTrue(repeated.equals(roleSnapshot(owner)),"failed password verification changes no preexisting identity");
            assertEquals(child,new JdbcTemplate(scanner(pg,child,childSecret)).queryForObject("SELECT current_user",String.class));

            for(String mutation:List.of("ALTER ROLE "+child+" CREATEDB", "GRANT test365alm_runtime TO "+child,
                    "GRANT SELECT ON public.principal TO "+child, "ALTER ROLE "+child+" SET search_path TO public")) {
                owner.execute(mutation);String unsafe=roleSnapshot(owner);
                assertNotEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,child,childSecret),"unsafe existing child must be refused");
                assertTrue(unsafe.equals(roleSnapshot(owner)),"refusal must not mutate the unsafe role or any existing grants");
                owner.execute("ALTER ROLE "+child+" NOCREATEDB");owner.execute("REVOKE test365alm_runtime FROM "+child);
                owner.execute("REVOKE SELECT ON public.principal FROM "+child);owner.execute("ALTER ROLE "+child+" RESET ALL");
            }
            for(String mutation:List.of("GRANT UPDATE ON public.scan_result TO test365alm_scanner", "GRANT SELECT ON public.principal TO test365alm_scanner",
                    "ALTER ROLE test365alm_scanner SET search_path TO public")) {
                owner.execute(mutation);String unsafe=roleSnapshot(owner);String unused=login();
                assertNotEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,unused,secret()),"expanded capability must not be inherited by a new login");
                assertTrue(unsafe.equals(roleSnapshot(owner)));assertEquals(0,owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Integer.class,unused));
                owner.execute("REVOKE UPDATE ON public.scan_result FROM test365alm_scanner");owner.execute("REVOKE SELECT ON public.principal FROM test365alm_scanner");owner.execute("ALTER ROLE test365alm_scanner RESET ALL");
            }
            assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,child,childSecret));assertScannerIdentity(scanner(pg,child,childSecret),child);
            assertEquals("test365alm_scanner",new JdbcTemplate(scanner(pg,"test365alm_scanner",password)).queryForObject("SELECT current_user",String.class));
            // With verbose statement logging configured for this operator only, the formal
            // entrypoint disables it locally before sending verifier-bearing SQL.
            owner.execute("ALTER ROLE "+pg.getUsername()+" SET log_statement='all'");
            String logged=login(),loggedSecret=secret();assertEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,logged,loggedSecret));
            assertFalse(pg.getLogs().contains("SCRAM-SHA-256$"));assertFalse(pg.getLogs().contains(loggedSecret));
            owner.execute("ALTER ROLE "+pg.getUsername()+" RESET log_statement");
            // A non-superuser owner without log-control SET privileges fails before creating
            // an identity or transmitting any verifier, even though it has CREATEROLE.
            String limited=login();owner.execute("CREATE ROLE "+limited+" LOGIN CREATEROLE");
            owner.execute("DO $fixture$ DECLARE verifier text; BEGIN SELECT rolpassword INTO verifier FROM pg_authid WHERE rolname='"+existing+"'; EXECUTE format('ALTER ROLE "+limited+" PASSWORD %L',verifier); END $fixture$");
            String denied=login();String safeBefore=roleSnapshot(owner);
            assertNotEquals(0,AssetTestInfrastructure.repositoryScannerProvision(pg,denied,secret(),limited,password));
            assertTrue(safeBefore.equals(roleSnapshot(owner)),"logging guard refusal must precede CREATE ROLE");
            assertFalse(pg.getLogs().contains("SCRAM-SHA-256$"));
        }
    }

    private static void assertScannerTargetRejections(JdbcTemplate owner,AnnotationConfigApplicationContext context,DataSource scanner,Fixture f) throws Exception {
        var assets=context.getBean(AssetService.class);byte[] content=bytes("Real clean confirmation before restricted target checks");
        var unconfirmed=assets.createUpload(f.member,f.project,declaration(content,null),key());
        var verdict=AssetTestInfrastructure.scanner().scan(new ByteArrayInputStream(content));assertEquals(MalwareScanner.Status.CLEAN,verdict.status());
        var before=snapshot(owner,ASSET_TABLES);
        try(var connection=scanner.getConnection()) {
            connection.setAutoCommit(false);scope(connection,f.tenant,f.project,f.member);
            SQLException denied=assertThrows(SQLException.class,()->insertScan(connection,f.tenant,f.project,unconfirmed.id(),f.member,sha(content),verdict));
            assertEquals("23514",denied.getSQLState());assertTrue(denied.getMessage().contains("scan attestation requires owned confirmed upload bytes"));connection.rollback();
        }
        assertEquals(before,snapshot(owner,ASSET_TABLES),"unconfirmed CLEAN must have no side effects");
        UUID foreign=context.getBean(ProjectService.class).createProject(f.admin,f.tenant,f.domain,"Foreign-"+key(),"Foreign scope").id();
        context.getBean(ProjectService.class).putMember(f.admin,foreign,f.member,List.of("PROJECT_MEMBER"),0L,false);
        var confirmed=assets.createUpload(f.member,foreign,declaration(content,null),key());assets.putContent(f.member,foreign,confirmed.id(),new ByteArrayInputStream(content),"text/plain");
        var foreignBefore=snapshot(owner,ASSET_TABLES);
        try(var connection=scanner.getConnection()) {
            connection.setAutoCommit(false);scope(connection,f.tenant,f.project,f.member);
            SQLException denied=assertThrows(SQLException.class,()->insertScan(connection,f.tenant,foreign,confirmed.id(),f.member,sha(content),verdict));
            assertEquals("23514",denied.getSQLState());assertTrue(denied.getMessage().contains("scan attestation requires owned confirmed upload bytes"));connection.rollback();
        }
        assertEquals(foreignBefore,snapshot(owner,ASSET_TABLES),"scanner scope cannot attest another project's valid upload");
        // The exact same confirmed upload and real verdict are legal in its proper scope.
        UUID scan=new JdbcScanResultWriter(scanner).record(f.tenant,foreign,confirmed.id(),f.member,sha(content),verdict);
        assertNotNull(scan);assertEquals(1,owner.queryForObject("SELECT count(*) FROM scan_result WHERE id=? AND status='CLEAN'",Integer.class,scan));
    }
    private static void insertScan(Connection c,UUID tenant,UUID project,UUID upload,UUID actor,String sha,MalwareScanner.ScanResult result) throws SQLException {
        try(var statement=c.prepareStatement("INSERT INTO scan_result(tenant_id,project_id,id,upload_id,status,engine,detail_code,sha256,created_by) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setObject(1,tenant);statement.setObject(2,project);statement.setObject(3,UUID.randomUUID());statement.setObject(4,upload);
            statement.setString(5,result.status().name());statement.setString(6,result.engine());statement.setString(7,result.detail());statement.setString(8,sha);statement.setObject(9,actor);statement.executeUpdate();
        }
    }
    private static void scope(Connection c,UUID tenant,UUID project,UUID actor) throws SQLException {
        try(var s=c.prepareStatement("SELECT set_config('test365alm.tenant_id',?,true),set_config('test365alm.project_id',?,true),set_config('test365alm.principal_id',?,true)")) {
            s.setString(1,tenant.toString());s.setString(2,project.toString());s.setString(3,actor.toString());s.execute();
        }
    }
    private static void assertScannerIdentity(DataSource source,String login) {
        var row=new JdbcTemplate(source).queryForMap("SELECT current_user,rolcanlogin,rolinherit,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,pg_has_role(current_user,'test365alm_scanner','MEMBER') scanner,pg_has_role(current_user,'test365alm_runtime','MEMBER') runtime FROM pg_roles WHERE rolname=current_user");
        assertEquals(login,row.get("current_user"));for(String flag:List.of("rolsuper","rolbypassrls","rolcreatedb","rolcreaterole","rolreplication","runtime"))assertEquals(false,row.get(flag),flag);
        for(String flag:List.of("rolcanlogin","rolinherit","scanner"))assertEquals(true,row.get(flag),flag);
        System.out.println("R11 formal scanner identity "+row);
    }
    /** Exact PG17 client control, only SELECT current_user on this newly owned container. */
    private static void verifyLibpqEmptyServiceIsNotUnset(PostgreSQLContainer<?> pg) throws Exception {
        var actual=pg.getDockerClient().inspectContainerCmd(pg.getContainerId()).exec();
        assertEquals(AssetTestInfrastructure.RUN_ID,actual.getConfig().getLabels().get("com.test365alm.r11.provision"));
        for(int mode:List.of(0,1,2)) {
            var args=new ArrayList<String>(List.of("env","PGSERVICE=","PGSERVICEFILE=","PGCONNECT_TIMEOUT=3"));
            if(mode>0)args.addAll(List.of("env","-u","PGSERVICE","-u","PGSERVICEFILE","-u","PGOPTIONS","-u","PGHOSTADDR"));
            if(mode==2)args.add("PGREQUIREAUTH=scram-sha-256");
            args.addAll(List.of("psql","-X","-w","-qAt","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-c","SELECT current_user"));
            var exec=pg.getDockerClient().execCreateCmd(pg.getContainerId()).withAttachStdout(true).withAttachStderr(true).withCmd(args.toArray(String[]::new)).exec();
            var output=new StringBuilder();
            try(var completion=new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                @Override public void onNext(com.github.dockerjava.api.model.Frame frame) { output.append(new String(frame.getPayload(),StandardCharsets.UTF_8)); }
            }) {
                pg.getDockerClient().execStartCmd(exec.getId()).exec(completion);
                assertTrue(completion.awaitCompletion(15,java.util.concurrent.TimeUnit.SECONDS),"owned libpq control must finish within its bound");
            }
            var exit=pg.getDockerClient().inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            if(mode==1){assertEquals(0L,exit);assertEquals(pg.getUsername(),output.toString().trim());}
            else {
                assertNotEquals(0L,exit,mode==0?"empty PGSERVICE/PGSERVICEFILE must reproduce the real pre-connection failure":"requiring SCRAM must reject the same local trust connection");
                assertTrue(output.toString().contains(mode==0?"service file \"\" not found":"server did not complete authentication"),"reject for the targeted libpq condition, not an unrelated connection failure");
            }
        }
        System.out.println("R11 libpq isolation: empty service variables fail before SQL; env -u succeeds; require_auth rejects local trust on the same owned PostgreSQL");
    }
    private static void assertScramHostAuthentication(JdbcTemplate owner) {
        var methods=owner.queryForList("SELECT auth_method FROM pg_hba_file_rules WHERE type LIKE 'host%' ORDER BY rule_number",String.class);
        assertFalse(methods.isEmpty());assertTrue(methods.stream().allMatch("scram-sha-256"::equals),"owned host authentication must demand actual SCRAM, including loopback");
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM pg_hba_file_rules WHERE type='local' AND database=ARRAY['all']::text[] AND user_name=ARRAY['all']::text[] AND auth_method='trust'",Integer.class),"local trust is retained only for the explicit no-secret libpq control");
    }
    private record Fixture(UUID admin,UUID member,UUID tenant,UUID domain,UUID project,UUID requirement) { }
    private static Fixture fixture(JdbcTemplate owner,AnnotationConfigApplicationContext c) {
        UUID admin=UUID.randomUUID(),member=UUID.randomUUID(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();
        for(UUID actor:List.of(admin,member))owner.update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",actor,"https://r11-provision.example.invalid",actor.toString(),"Owned upgrade fixture");
        owner.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"owned-"+tenant,"Owned upgrade",admin);owner.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"Owned upgrade");
        owner.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{TENANT_ADMIN}')",tenant,admin);owner.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{MEMBER}')",tenant,member);
        var projects=c.getBean(ProjectService.class);UUID project=projects.createProject(admin,tenant,domain,"Upgrade-"+key(),"Owned upgrade").id();projects.putMember(admin,project,member,List.of("PROJECT_MEMBER"),0L,false);
        UUID requirement=c.getBean(RequirementService.class).create(member,project,new RequirementService.CreateCommand("Frozen attachment target","Original V20 body","HIGH"),key()).id();
        assertEquals("test365alm_runtime",c.getBean(JdbcTemplate.class).queryForObject("SELECT current_user",String.class));
        return new Fixture(admin,member,tenant,domain,project,requirement);
    }
    private static AssetService.Upload published(AssetService assets,Fixture f,byte[] content,UUID asset,String completeKey) {
        var upload=assets.createUpload(f.member,f.project,declaration(content,asset),key());
        assertEquals("UPLOADED",assets.putContent(f.member,f.project,upload.id(),new ByteArrayInputStream(content),"text/plain").state());
        var result=assets.complete(f.member,f.project,upload.id(),EMPTY,completeKey);assertEquals("PUBLISHED",result.state());return result;
    }
    private static tools.jackson.databind.JsonNode declaration(byte[] content,UUID asset) {
        var body=CoreCommands.JSON.createObjectNode().put("filename","owned-upgrade.txt").put("size",content.length).put("sha256",sha(content)).put("contentType","text/plain");
        if(asset!=null)body.put("assetId",asset.toString());return body;
    }
    private static void assertDownloaded(AssetService assets,Fixture f,AssetService.Upload upload,byte[] content) throws Exception {
        try(var download=assets.download(f.member,f.project,upload.assetId(),upload.revisionId()).stream()){assertArrayEquals(content,download.readAllBytes());}
    }
    private static AnnotationConfigApplicationContext context(DataSource runtime,DataSource scanner) {
        var c=new AnnotationConfigApplicationContext();c.registerBean(DataSource.class,()->runtime);c.register(RuntimeConfig.class,ProjectService.class,CoreCommands.class,RequirementService.class,AssetService.class);
        c.registerBean(BlobStore.class,AssetTestInfrastructure::store);c.registerBean(MalwareScanner.class,AssetTestInfrastructure::scanner);c.registerBean(ScanResultWriter.class,()->new JdbcScanResultWriter(scanner));c.refresh();return c;
    }
    private static PostgreSQLContainer<?> ownedDatabase(String suffix) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("r11_provision_"+suffix).withUsername("r11_provision_owner").withPassword(secret()).withInitScript("r03-test-role.sql")
            .withEnv("POSTGRES_INITDB_ARGS","--auth-host=scram-sha-256")
            .withLabel("com.test365alm.r11.provision",AssetTestInfrastructure.RUN_ID);
    }
    private static Flyway migration(PostgreSQLContainer<?> pg,String target){return Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target(target).load();}
    private static JdbcTemplate owner(PostgreSQLContainer<?> pg){return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));}
    private static DataSource runtime(PostgreSQLContainer<?> pg){return new DriverManagerDataSource(pg.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");}
    private static DataSource scanner(PostgreSQLContainer<?> pg,String user,String password){return new DriverManagerDataSource(pg.getJdbcUrl(),user,password);}
    private static Map<String,List<String>> snapshot(JdbcTemplate db,List<String> tables){var result=new TreeMap<String,List<String>>();for(String table:tables)result.put(table,db.query("SELECT to_jsonb(t)::text FROM \""+table+"\" t ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1)));return result;}
    /** Kept in memory for equality only. Never interpolate catalog credentials into assertion output. */
    private static String roleSnapshot(JdbcTemplate db){return db.queryForObject("SELECT jsonb_build_object('roles',(SELECT jsonb_agg(to_jsonb(r) ORDER BY oid) FROM pg_authid r),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY oid) FROM pg_auth_members m),'settings',(SELECT jsonb_agg(to_jsonb(s) ORDER BY setdatabase,setrole) FROM pg_db_role_setting s),'dependencies',(SELECT jsonb_agg(to_jsonb(d) ORDER BY dbid,classid,objid,objsubid,refclassid,refobjid,deptype) FROM pg_shdepend d))::text",String.class);}
    private static String groupSnapshot(JdbcTemplate db){return db.queryForObject("SELECT to_jsonb(r)::text FROM pg_authid r WHERE rolname='test365alm_scanner'",String.class);}
    private static byte[] bytes(String text){return (text+"\n").getBytes(StandardCharsets.UTF_8);}
    private static String sha(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(Exception e){throw new AssertionError(e);}}
    private static String login(){return "r11_fix_scanner_"+UUID.randomUUID().toString().replace("-","");}
    private static String secret(){return UUID.randomUUID().toString()+UUID.randomUUID();}
    private static String key(){return UUID.randomUUID().toString();}
}
