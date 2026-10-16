package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;

/** Real owned PostgreSQL/S3/clamd. The scanner counter delegates every call to the real adapter. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class AssetTerminalOutcomeIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("r11_fix_owned_terminal").withUsername("r11_terminal_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
        AssetTestInfrastructure.register(r);AssetTestInfrastructure.registerScannerDatabase(r,PG);
    }
    @Autowired AssetService assets; @Autowired ProjectService projects; @Autowired JdbcTemplate runtime;
    @Autowired CoreCommands commands; @Autowired PlatformTransactionManager transactions;
    @Autowired BlobStore blobs; @Autowired MalwareScanner scanner; @Autowired ScanResultWriter scanWriter;
    private static final JsonNode EMPTY=CoreCommands.JSON.readTree("{}");
    private static final byte[] EICAR=("X5O!P%@AP[4\\PZX54(P^)7CC)7}$"+"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);

    @Test void infectedCompleteSameKeyReplaysFrozenTerminalRejection() {
        var f=fixture();var u=uploaded(f,EICAR);String key=key();var calls=new AtomicInteger();
        var first=error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key));
        assertEquals(new Outcome(422,"FILE_INFECTED","Malware detected; upload rejected"),first);
        assertEquals("REJECTED",assets.getUpload(f.member,f.project,u.id()).state());assertEquals(1,calls.get());
        var before=snapshot(f.project);long version=assets.getUpload(f.member,f.project,u.id()).rowVersion();
        // A fresh service object cannot rely on an in-memory cached exception or the previous call.
        var replay=error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key));
        assertEquals(first,replay,"same key/content must keep the original HTTP status, code and safe message");
        assertEquals(before,snapshot(f.project),"replay must not change upload, scan, audit, Outbox or idempotency rows");
        assertEquals(version,assets.getUpload(f.member,f.project,u.id()).rowVersion());assertEquals(1,calls.get(),"terminal replay must not rescan");
        assertCandidateInaccessible(f,u);
    }

    @Test void infectedCompleteNewKeyReturnsAlreadyRejectedWithoutRescan() {
        var f=fixture();var u=uploaded(f,EICAR);var calls=new AtomicInteger();
        assertEquals(422,error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key())).status());
        var before=snapshot(f.project);
        var result=error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key()));
        assertEquals(409,result.status());assertEquals("UPLOAD_REJECTED",result.code());
        assertEquals(before,snapshot(f.project));assertEquals(1,calls.get());assertCandidateInaccessible(f,u);
    }

    @Test void infectedCompleteSameKeyDifferentRequestIsRejected() {
        var f=fixture();var u=uploaded(f,EICAR);var calls=new AtomicInteger();String key=key();
        assertEquals(422,error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key)).status());
        var before=snapshot(f.project);var different=CoreCommands.JSON.readTree("{\"unexpected\":true}");
        assertNotEquals(CoreCommands.hash(CoreCommands.canonical(EMPTY)),CoreCommands.hash(CoreCommands.canonical(different)));
        var reused=error(()->countedService(calls).complete(f.member,f.project,u.id(),different,key));
        assertEquals(409,reused.status());assertEquals("IDEMPOTENCY_KEY_REUSED",reused.code());
        // A known command checks its frozen hash; a new intent still obeys the closed {} contract.
        var invalid=error(()->countedService(calls).complete(f.member,f.project,u.id(),different,key()));
        assertEquals(400,invalid.status());assertEquals("INVALID_REQUEST",invalid.code());
        assertEquals(before,snapshot(f.project));assertEquals(1,calls.get());
    }

    @Test void terminalReplayReauthorizesRevokedDisabledAndExpiredPrincipals() {
        for(String loss:List.of("revoked","disabled","expired")) {
            var f=fixture();var u=uploaded(f,EICAR);var calls=new AtomicInteger();String key=key();
            assertEquals(422,error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key)).status());
            switch(loss) {
                case "revoked" -> projects.revokeMember(f.admin,f.project,f.member,1L);
                case "disabled" -> assertEquals(1,owner().update("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.member));
                case "expired" -> assertEquals(1,owner().update("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?",f.tenant,f.member));
                default -> throw new AssertionError(loss);
            }
            var before=snapshot(f.project);var denied=error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key));
            assertEquals(404,denied.status(),loss);assertEquals("NOT_FOUND",denied.code(),loss);
            assertEquals(before,snapshot(f.project));assertEquals(1,calls.get(),loss+" must be checked before reading the frozen outcome");
        }
    }

    @Test void scannerTransientFailureSameKeyRemainsRetryable() {
        var f=fixture();byte[] benign="R11 FIX safe transient recovery\n".getBytes(StandardCharsets.UTF_8);var u=uploaded(f,benign);String key=key();var calls=new AtomicInteger();
        AssetTestInfrastructure.pauseScanner();
        try {
            var failed=error(()->countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key));
            assertEquals(503,failed.status());assertEquals("SCAN_UNAVAILABLE",failed.code());
            assertEquals("SCAN_FAILED",assets.getUpload(f.member,f.project,u.id()).state());assertCandidateInaccessible(f,u);
            assertEquals(0,owner().queryForObject("SELECT count(*) FROM core_command_idempotency WHERE project_id=? AND route=? AND idempotency_key=?",Integer.class,f.project,"asset:upload:complete:"+u.id(),key),"transient 503 must not freeze the intent");
        } finally {AssetTestInfrastructure.resumeScanner();}
        var published=countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key);assertEquals("PUBLISHED",published.state());assertEquals(2,calls.get());
        var before=snapshot(f.project);assertEquals(published,countedService(calls).complete(f.member,f.project,u.id(),EMPTY,key));assertEquals(before,snapshot(f.project));assertEquals(2,calls.get());
        assertEquals(1,owner().queryForObject("SELECT count(*) FROM asset_revision WHERE project_id=?",Integer.class,f.project));
    }

    private AssetService countedService(AtomicInteger calls) {return new AssetService(runtime,commands,blobs,content->{calls.incrementAndGet();return scanner.scan(content);},scanWriter,transactions);}
    private record Fixture(UUID admin,UUID member,UUID tenant,UUID project) { }
    private Fixture fixture() {
        UUID admin=principal(),member=principal(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();var o=owner();
        o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"r11fix-"+tenant,"Terminal outcome",admin);
        o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"Terminal outcome");
        o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{TENANT_ADMIN}')",tenant,admin);
        o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,'{MEMBER}')",tenant,member);
        var project=projects.createProject(admin,tenant,domain,"Outcome-"+key(),"Outcome").id();projects.putMember(admin,project,member,List.of("PROJECT_MEMBER"),0L,false);
        assertEquals("test365alm_runtime",runtime.queryForObject("SELECT current_user",String.class));return new Fixture(admin,member,tenant,project);
    }
    private UUID principal(){UUID id=UUID.randomUUID();owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",id,"https://r11-terminal.example.invalid",id.toString(),"Terminal fixture");return id;}
    private AssetService.Upload uploaded(Fixture f,byte[] bytes) {
        var body=CoreCommands.JSON.createObjectNode().put("filename","terminal.txt").put("size",bytes.length).put("sha256",sha(bytes)).put("contentType","text/plain");
        var upload=assets.createUpload(f.member,f.project,body,key());assertEquals("UPLOADED",assets.putContent(f.member,f.project,upload.id(),new ByteArrayInputStream(bytes),"text/plain").state());return upload;
    }
    private void assertCandidateInaccessible(Fixture f,AssetService.Upload upload) {
        var ids=owner().queryForMap("SELECT candidate_asset_id,candidate_revision_id FROM upload_session WHERE id=?",upload.id());
        var denied=error(()->assets.download(f.member,f.project,(UUID)ids.get("candidate_asset_id"),(UUID)ids.get("candidate_revision_id")));
        assertEquals(404,denied.status());assertEquals("NOT_FOUND",denied.code());
    }
    private record Outcome(int status,String code,String message) { }
    private static Outcome error(Runnable action){var e=assertThrows(ProjectAccessException.class,action::run);return new Outcome(e.status().value(),e.code(),e.getMessage());}
    private JdbcTemplate owner(){return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));}
    private Map<String,List<String>> snapshot(UUID project){var result=new TreeMap<String,List<String>>();for(String table:List.of("upload_session","asset","asset_revision","scan_result","attachment_link","asset_outbox_event","audit_event","core_command_idempotency"))result.put(table,owner().query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1),project));return result;}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String key(){return UUID.randomUUID().toString();}
}
