package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.defect.DefectService;

/** Real PostgreSQL/S3/clamd. Owner is used only for fixtures, fault injection and independent verification. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers @Timeout(180)
class AssetDatabaseIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("r11_owned_assets").withUsername("r11_owner").withPassword(UUID.randomUUID().toString()).withInitScript("r03-test-role.sql");
    @DynamicPropertySource static void ds(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"test365alm_runtime");r.add("spring.datasource.password",()->"r03_isolated_test_role_only");
        r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);r.add("spring.datasource.hikari.maximum-pool-size",()->3);
        AssetTestInfrastructure.register(r);
        AssetTestInfrastructure.registerScannerDatabase(r,PG);
    }
    @Autowired AssetService assets; @Autowired ProjectService projects; @Autowired RequirementService requirements;
    @Autowired TestCaseService tests; @Autowired DefectService defects; @Autowired JdbcTemplate runtime;
    @Autowired CoreCommands commands; @Autowired PlatformTransactionManager transactions; @Autowired BlobStore blobs; @Autowired MalwareScanner scanner;
    @Autowired ScanResultWriter scanWriter;
    static final byte[] TEXT="R11 harmless private text\n".getBytes(StandardCharsets.UTF_8);
    private static final JsonNode EMPTY=CoreCommands.JSON.readTree("{}");

    @Test void memberUploadUsesPrivateQuarantineAndServerVerifiedReceipt() throws Exception {
        var f=fixture();assertEquals("test365alm_runtime",runtime.queryForObject("SELECT current_user",String.class));
        assertEquals(20,owner().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));
        var body=body(TEXT,"../../report\r\n\".txt",null);String intent=key();var upload=assets.createUpload(f.member,f.project,body,intent);
        assertEquals(upload,assets.createUpload(f.member,f.project,body,intent));assertNull(upload.assetId());assertNull(upload.revisionId());assertEquals("CREATED",upload.state());
        String q=internal(upload.id(),"quarantine_key");assertEquals("quarantine/"+f.tenant+"/"+f.project+"/"+upload.id(),q);assertFalse(q.contains("report"));
        var receipt=put(f,upload,TEXT);assertEquals(TEXT.length,receipt.size());assertEquals(sha(TEXT),receipt.sha256());assertEquals("text/plain",receipt.mime());
        assertEquals(receipt,put(f,upload,TEXT));try(var in=blobs.openQuarantine(q)){assertArrayEquals(TEXT,in.readAllBytes());}
        assertEquals(403,AssetTestInfrastructure.anonymousStatus(AssetTestInfrastructure.QUARANTINE,q));
        assertEquals(0,count("asset_revision",f.project));assertEquals(2,count("asset_outbox_event",f.project));
        String complete=key();var result=assets.complete(f.member,f.project,upload.id(),EMPTY,complete);
        assertEquals("PUBLISHED",result.state());assertEquals(result,assets.complete(f.member,f.project,upload.id(),EMPTY,complete));
        assertEquals(receipt,put(f,upload,TEXT));assertArrayEquals(TEXT,download(f.viewer,f.project,result.assetId(),result.revisionId()));
        assertEquals(403,AssetTestInfrastructure.anonymousStatus(AssetTestInfrastructure.PUBLISHED,internal(upload.id(),"published_key")));
        assertEquals(1,count("asset",f.project));assertEquals(1,count("asset_revision",f.project));assertEquals(1,count("scan_result",f.project));
    }
    @Test void contentSizeHashMimeAndStreamBoundsRejectWithoutPublication() {
        var f=fixture();var valid=body(TEXT,"file.txt",null);
        for(JsonNode declaration:List.of(with(valid,"size",TEXT.length+1),with(valid,"sha256","0".repeat(64)),with(valid,"contentType","application/pdf"))) {
            var u=assets.createUpload(f.member,f.project,declaration,key());var before=snapshot(f.project);
            denied(422,"CONTENT_MISMATCH",()->assets.putContent(f.member,f.project,u.id(),new ByteArrayInputStream(TEXT),u.declaredContentType()));assertEquals(before,snapshot(f.project));
        }
        byte[] html="<!doctype html><html><script>1</script></html>".getBytes(StandardCharsets.UTF_8);var u=assets.createUpload(f.member,f.project,body(html,"text.txt",null),key());
        denied(415,"UNSUPPORTED_FILE_TYPE",()->put(f,u,html));
        var bounded=assets.createUpload(f.member,f.project,valid,key());byte[] large=new byte[(int)AssetRules.MAX_SIZE+1];Arrays.fill(large,(byte)'a');
        denied(413,"FILE_TOO_LARGE",()->put(f,bounded,large));
        put(f,bounded,TEXT);byte[] different=TEXT.clone();different[0]='X';var before=snapshot(f.project);
        denied(409,"UPLOAD_CONTENT_CONFLICT",()->put(f,bounded,different));assertEquals(before,snapshot(f.project));
        assertEquals(0,count("asset_revision",f.project));
    }
    @Test void realClamdCleanAndEicarProduceSafeTerminalStates() throws Exception {
        var f=fixture();var good=publish(f,TEXT,null);assertArrayEquals(TEXT,download(f.viewer,f.project,good.assetId(),good.revisionId()));
        // Standard harmless anti-virus test signature, only in this owned private quarantine.
        byte[] eicar=("X5O!P%@AP[4\\PZX54(P^)7CC)7}$"+"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
        var u=assets.createUpload(f.member,f.project,body(eicar,"antivirus-test.txt",null),key());put(f,u,eicar);
        denied(422,"FILE_INFECTED",()->assets.complete(f.member,f.project,u.id(),EMPTY,key()));assertEquals("REJECTED",assets.getUpload(f.member,f.project,u.id()).state());
        assertEquals("INFECTED",owner().queryForObject("SELECT status FROM scan_result WHERE upload_id=?",String.class,u.id()));
        var before=snapshot(f.project);denied(409,"UPLOAD_REJECTED",()->assets.complete(f.member,f.project,u.id(),EMPTY,key()));assertEquals(before,snapshot(f.project));
        inaccessibleCandidate(f,u);assertEquals(1,count("asset_revision",f.project));
        try(var c=connection(f,f.member)){var ex=assertThrows(SQLException.class,()->sql(c,"UPDATE upload_session SET state='UPLOADED',row_version=row_version+1 WHERE id=?",u.id()));assertEquals("55006",ex.getSQLState());assertTrue(ex.getMessage().contains("terminal upload cannot be reopened"));}
        // A trusted INFECTED journal survives a failed business audit and cannot be reinterpreted as CLEAN.
        var interrupted=assets.createUpload(f.member,f.project,body(eicar,"infection-journal.txt",null),key());put(f,interrupted,eicar);
        owner().execute("REVOKE INSERT ON audit_event FROM test365alm_runtime");
        try{fault("audit_event",()->assets.complete(f.member,f.project,interrupted.id(),EMPTY,key()));}finally{owner().execute("GRANT INSERT ON audit_event TO test365alm_runtime");}
        assertEquals("UPLOADED",assets.getUpload(f.member,f.project,interrupted.id()).state());
        assertEquals("INFECTED",owner().queryForObject("SELECT status FROM scan_result WHERE upload_id=?",String.class,interrupted.id()));
        var journalRecovery=service(blobs,ignored->{throw new AssertionError("durable infection must prevent a new scanner verdict");});
        denied(422,"FILE_INFECTED",()->journalRecovery.complete(f.member,f.project,interrupted.id(),EMPTY,key()));assertEquals("REJECTED",assets.getUpload(f.member,f.project,interrupted.id()).state());inaccessibleCandidate(f,interrupted);
    }
    @Test void scannerUnavailableAndTimeoutRemainRetryableWithoutPublication() throws Exception {
        var f=fixture();var u=assets.createUpload(f.member,f.project,body(TEXT,"retry.txt",null),key());put(f,u,TEXT);String intent=key();
        // A real scanner is frozen, not replaced; its process and published endpoint stay identical.
        AssetTestInfrastructure.pauseScanner();
        try{denied(503,"SCAN_UNAVAILABLE",()->assets.complete(f.member,f.project,u.id(),EMPTY,intent));assertEquals("SCAN_FAILED",assets.getUpload(f.member,f.project,u.id()).state());inaccessibleCandidate(f,u);}
        finally{AssetTestInfrastructure.resumeScanner();}
        var result=assets.complete(f.member,f.project,u.id(),EMPTY,intent);assertEquals("PUBLISHED",result.state());
        var refused=assets.createUpload(f.member,f.project,body(TEXT,"refused.txt",null),key());put(f,refused,TEXT);
        int closedPort;try(var ownedPort=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){closedPort=ownedPort.getLocalPort();}
        try(var probe=new Socket()){assertThrows(ConnectException.class,()->probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),closedPort),500));}
        var refusedService=service(blobs,new ClamdMalwareScanner(InetAddress.getLoopbackAddress().getHostAddress(),closedPort,100,200));
        int publications=count("asset_revision",f.project);String refusedIntent=key();
        assertTimeout(Duration.ofSeconds(3),()->denied(503,"SCAN_UNAVAILABLE",()->refusedService.complete(f.member,f.project,refused.id(),EMPTY,refusedIntent)));
        assertEquals("SCAN_FAILED",assets.getUpload(f.member,f.project,refused.id()).state());assertEquals(publications,count("asset_revision",f.project));inaccessibleCandidate(f,refused);
        assertEquals("PUBLISHED",assets.complete(f.member,f.project,refused.id(),EMPTY,refusedIntent).state());
        var second=assets.createUpload(f.member,f.project,body(TEXT,"timeout.txt",null),key());put(f,second,TEXT);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var accepted=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            try{var peer=pool.submit(()->{try(var socket=server.accept()){accepted.countDown();release.await(5,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}});
                var timeoutService=service(blobs,new ClamdMalwareScanner(server.getInetAddress().getHostAddress(),server.getLocalPort(),100,200));
                assertTimeout(Duration.ofSeconds(3),()->denied(503,"SCAN_UNAVAILABLE",()->timeoutService.complete(f.member,f.project,second.id(),EMPTY,key())));assertTrue(accepted.await(1,TimeUnit.SECONDS));inaccessibleCandidate(f,second);
                release.countDown();peer.get(3,TimeUnit.SECONDS);
            }finally{release.countDown();pool.shutdownNow();}
        }
        assertEquals("PUBLISHED",assets.complete(f.member,f.project,second.id(),EMPTY,key()).state());
    }
    @Test void newVersionsPreserveFrozenAttachmentBytesAndImmutableRows() throws Exception {
        var f=fixture();var first=publish(f,TEXT,null);var req=requirement(f);var link=assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,first),key());
        byte[] newer="Second distinct asset version\n".getBytes(StandardCharsets.UTF_8);var second=publish(f,newer,first.assetId());
        assertEquals(first.assetId(),second.assetId());assertEquals(second.revisionId(),assets.get(f.viewer,f.project,first.assetId()).currentRevisionId());
        assertEquals(2,assets.revision(f.viewer,f.project,second.assetId(),second.revisionId()).revisionNumber());
        assertEquals(link,assets.attachments(f.viewer,f.project,"REQUIREMENT",req,null,25).items().get(0));
        assertArrayEquals(TEXT,download(f.viewer,f.project,link.assetId(),link.assetRevisionId()));assertArrayEquals(newer,download(f.viewer,f.project,second.assetId(),second.revisionId()));
        var page=assets.revisions(f.viewer,f.project,first.assetId(),null,1);assertEquals(1,page.items().size());assertNotNull(page.nextCursor());
        var next=assets.revisions(f.viewer,f.project,first.assetId(),page.nextCursor(),1);assertEquals(1,next.items().size());assertNull(next.nextCursor());
        assertEquals(Set.of(first.revisionId(),second.revisionId()),Set.of(page.items().get(0).id(),next.items().get(0).id()));
        try(var c=connection(f,f.member)){for(String table:List.of("asset_revision","scan_result","attachment_link"))for(String sql:List.of("UPDATE "+table+" SET created_at=CURRENT_TIMESTAMP","DELETE FROM "+table,"TRUNCATE "+table))sqlDenied(c,sql,"42501");}
        assertEquals(link,assets.attachments(f.viewer,f.project,"REQUIREMENT",req,null,25).items().get(0));
    }
    @Test void typedLinksRejectForeignMissingAndDeletedTargets() {
        var f=fixture();var foreign=fixture();var sibling=sibling(f);var a=publish(f,TEXT,null);var foreignAsset=publish(foreign,TEXT,null);
        UUID req=requirement(f),test=test(f),defect=defects.create(f.member,f.project,json("{\"title\":\"Evidence\",\"description\":\"Defect\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}"),null,key()).id();
        for(var target:Map.of("REQUIREMENT",req,"TEST_CASE",test,"DEFECT",defect).entrySet())assertEquals(target.getKey(),assets.createAttachment(f.member,f.project,link(target.getKey(),target.getValue(),a),key()).kind());
        var before=snapshot(f.project);
        for(UUID target:List.of(UUID.randomUUID(),test,requirement(foreign),requirement(sibling)))denied(404,"NOT_FOUND",()->assets.createAttachment(f.member,f.project,link("REQUIREMENT",target,a),key()));
        denied(404,"NOT_FOUND",()->assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,foreignAsset),key()));assertEquals(before,snapshot(f.project));
        owner().update("UPDATE requirement SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",req);before=snapshot(f.project);
        denied(404,"NOT_FOUND",()->assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,a),key()));assertEquals(before,snapshot(f.project));
        assertEquals(1,assets.attachments(f.viewer,f.project,"REQUIREMENT",req,null,25).items().size(),"old frozen link remains without new writes");
    }
    @Test void runtimeRlsAndCurrentMembershipProtectEveryScope() throws Exception {
        var f=fixture();var sibling=sibling(f);var foreign=fixture();var a=publish(f,TEXT,null);publish(sibling,TEXT,null);publish(foreign,TEXT,null);var u=assets.createUpload(f.member,f.project,body(TEXT,"owned.txt",null),key());
        assets.createAttachment(f.member,f.project,link("REQUIREMENT",requirement(f),a),key());
        var before=snapshot(f.project);denied(403,"FORBIDDEN",()->assets.createUpload(f.viewer,f.project,body(TEXT,"no.txt",null),key()));
        denied(403,"FORBIDDEN",()->assets.createUpload(f.viewer,f.project,body(TEXT,"version.txt",a.assetId()),key()));
        denied(404,"NOT_FOUND",()->assets.get(f.outsider,f.project,a.assetId()));denied(404,"NOT_FOUND",()->assets.getUpload(f.admin,f.project,u.id()));assertEquals(before,snapshot(f.project));
        try(var c=connection(f,f.member)) {
            assertEquals("test365alm_runtime",scalar(c,"SELECT current_user"));assertEquals("false",scalar(c,"SELECT rolbypassrls::text FROM pg_roles WHERE rolname=current_user"));
            assertEquals("1",scalar(c,"SELECT count(*) FROM asset"));context(c,foreign,f.member);assertEquals("0",scalar(c,"SELECT count(*) FROM asset"));context(c,f,f.member);assertEquals("1",scalar(c,"SELECT count(*) FROM asset"));
        }
        try(var c=connection(f,f.viewer)){assertEquals("0",scalar(c,"SELECT count(*) FROM upload_session"));sqlDenied(c,"INSERT INTO asset(tenant_id,project_id,id,current_revision_id,created_by) VALUES(?,?,?,?,?)","42501",f.tenant,f.project,UUID.randomUUID(),UUID.randomUUID(),f.viewer);}
        projects.putMember(f.admin,f.project,f.member,List.of("PROJECT_VIEWER"),1L,false);assertArrayEquals(TEXT,download(f.member,f.project,a.assetId(),a.revisionId()));denied(403,"FORBIDDEN",()->put(f,u,TEXT));
        // Reuse each restricted connection with an unchanged, valid identity/scope. These queries do
        // not call service pre-authorization: the database itself must hide previously visible rows.
        try(var c=connection(f,f.member)) {
            for(String table:ASSET_TABLES)assertTrue(Long.parseLong(scalar(c,"SELECT count(*) FROM "+table))>0,table+" must be nonempty before revocation");
            projects.revokeMember(f.admin,f.project,f.member,2L);assertRuntimeScopeInvisible(c,f,f.member);
        }
        denied(404,"NOT_FOUND",()->assets.get(f.member,f.project,a.assetId()));denied(404,"NOT_FOUND",()->assets.download(f.member,f.project,a.assetId(),a.revisionId()));
        try(var c=connection(f,f.viewer)) {
            assertEquals("1",scalar(c,"SELECT count(*) FROM asset"));assertEquals("1",scalar(c,"SELECT count(*) FROM attachment_link"));
            assertEquals(1,owner().update("UPDATE principal SET disabled_at=CURRENT_TIMESTAMP WHERE id=?",f.viewer));assertRuntimeScopeInvisible(c,f,f.viewer);
        }
        denied(404,"NOT_FOUND",()->assets.get(f.viewer,f.project,a.assetId()));
        try(var c=connection(f,f.admin)) {
            assertEquals("1",scalar(c,"SELECT count(*) FROM asset"));assertEquals("1",scalar(c,"SELECT count(*) FROM attachment_link"));
            assertEquals(1,owner().update("UPDATE tenant_member SET valid_until=CURRENT_TIMESTAMP WHERE tenant_id=? AND principal_id=?",f.tenant,f.admin));assertRuntimeScopeInvisible(c,f,f.admin);
        }
        denied(404,"NOT_FOUND",()->assets.download(f.admin,f.project,a.assetId(),a.revisionId()));
    }
    @Test void concurrentSameIntentCreatesOneReceiptScanAndRevision() throws Exception {
        var f=fixture();String create=key();var pair=concurrent(i->assets.createUpload(f.member,f.project,body(TEXT,"concurrent.txt",null),create));assertEquals(pair.get(0),pair.get(1));var u=(AssetService.Upload)pair.get(0);
        pair=concurrent(i->put(f,u,TEXT));assertEquals(pair.get(0),pair.get(1));String complete=key();pair=concurrent(i->assets.complete(f.member,f.project,u.id(),EMPTY,complete));assertEquals(pair.get(0),pair.get(1));var a=(AssetService.Upload)pair.get(0);
        String linkKey=key();UUID req=requirement(f);pair=concurrent(i->assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,a),linkKey));assertEquals(pair.get(0),pair.get(1));
        assertEquals(1,count("upload_session",f.project));assertEquals(1,count("asset",f.project));assertEquals(1,count("asset_revision",f.project));assertEquals(1,count("scan_result",f.project));assertEquals(1,count("attachment_link",f.project));
        assertEquals(6,owner().queryForObject("SELECT count(*) FROM audit_event WHERE project_id=? AND object_type IN ('upload_session','attachment_link')",Integer.class,f.project),"same-intent concurrency must not duplicate asset audit events");
        assertEquals(6,count("asset_outbox_event",f.project),"session, content, scan, asset, revision and link each emit once");
        assertEquals(3,count("core_command_idempotency",f.project),"create, complete and link retain exactly one frozen result each");
        var before=snapshot(f.project);denied(409,"IDEMPOTENCY_KEY_REUSED",()->assets.createUpload(f.member,f.project,body(TEXT,"changed.txt",null),create));
        denied(409,"IDEMPOTENCY_KEY_REUSED",()->assets.createAttachment(f.member,f.project,link("REQUIREMENT",UUID.randomUUID(),a),linkKey));assertEquals(before,snapshot(f.project));
    }
    @Test void concurrentNewVersionsAllocateUniqueRevisionNumbers() throws Exception {
        var f=fixture();var first=publish(f,TEXT,null);var a=assets.createUpload(f.member,f.project,body(TEXT,"second.txt",first.assetId()),key());var b=assets.createUpload(f.member,f.project,body(TEXT,"third.txt",first.assetId()),key());put(f,a,TEXT);put(f,b,TEXT);
        var pair=concurrent(i->assets.complete(f.member,f.project,i==0?a.id():b.id(),EMPTY,key()));assertNotEquals(((AssetService.Upload)pair.get(0)).revisionId(),((AssetService.Upload)pair.get(1)).revisionId());
        assertEquals(List.of(1L,2L,3L),owner().query("SELECT revision_no FROM asset_revision WHERE asset_id=? ORDER BY revision_no",(r,i)->r.getLong(1),first.assetId()));
        assertEquals(3,assets.get(f.viewer,f.project,first.assetId()).currentRevision().revisionNumber());
    }
    @Test void quarantinePutThenReceiptFailureHasNoDownloadAndSafelyRetries() throws Exception {
        var f=fixture();var u=assets.createUpload(f.member,f.project,body(TEXT,"receipt-failure.txt",null),key());var before=snapshot(f.project);
        int unavailablePort;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){unavailablePort=socket.getLocalPort();}
        var unreachable=service(new S3BlobStore("http://127.0.0.1:"+unavailablePort,"isolated-access","isolated-not-production-secret","q-owned","p-owned",300),scanner);
        assertThrows(AssetStorageException.class,()->unreachable.putContent(f.member,f.project,u.id(),new ByteArrayInputStream(TEXT),"text/plain"));assertEquals(before,snapshot(f.project));inaccessibleCandidate(f,u);
        owner().execute("REVOKE INSERT ON audit_event FROM test365alm_runtime");
        try{fault("audit_event",()->put(f,u,TEXT));}finally{owner().execute("GRANT INSERT ON audit_event TO test365alm_runtime");}
        assertEquals(before,snapshot(f.project));try(var raw=blobs.openQuarantine(internal(u.id(),"quarantine_key"))){assertArrayEquals(TEXT,raw.readAllBytes());}inaccessibleCandidate(f,u);
        put(f,u,TEXT);assertEquals("PUBLISHED",assets.complete(f.member,f.project,u.id(),EMPTY,key()).state());assertEquals(1,count("asset_revision",f.project));
    }
    @Test void publishedObjectThenDatabaseFailureIsInaccessibleAndRecoverable() throws Exception {
        var f=fixture();
        for(String table:List.of("audit_event","asset_outbox_event")) {
            var u=assets.createUpload(f.member,f.project,body(TEXT,"publish-failure.txt",null),key());put(f,u,TEXT);String intent=key();var before=snapshot(f.project);
            owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");try{fault(table,()->assets.complete(f.member,f.project,u.id(),EMPTY,intent));}finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
            var after=snapshot(f.project);before.remove("scan_result");after.remove("scan_result");assertEquals(before,after,"business, revision, audit, Outbox and idempotency rollback; trusted scan is a durable independent attestation");
            assertEquals(1,owner().queryForObject("SELECT count(*) FROM scan_result WHERE upload_id=? AND status='CLEAN'",Integer.class,u.id()));
            try(var raw=blobs.openPublished(internal(u.id(),"published_key"))){assertArrayEquals(TEXT,raw.readAllBytes());}inaccessibleCandidate(f,u);
            var result=assets.complete(f.member,f.project,u.id(),EMPTY,intent);assertArrayEquals(TEXT,download(f.member,f.project,result.assetId(),result.revisionId()));assertEquals(result,assets.complete(f.member,f.project,u.id(),EMPTY,intent));
        }
        assertEquals(2,count("asset_revision",f.project));assertEquals(2,count("scan_result",f.project));
    }
    @Test void attachmentAuditAndOutboxFailuresRollbackAndRecover() {
        var f=fixture();var a=publish(f,TEXT,null);
        for(String table:List.of("audit_event","asset_outbox_event")) {
            UUID req=requirement(f);String intent=key();var before=snapshot(f.project);owner().execute("REVOKE INSERT ON "+table+" FROM test365alm_runtime");
            try{fault(table,()->assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,a),intent));}finally{owner().execute("GRANT INSERT ON "+table+" TO test365alm_runtime");}
            assertEquals(before,snapshot(f.project));var result=assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,a),intent);assertEquals(result,assets.createAttachment(f.member,f.project,link("REQUIREMENT",req,a),intent));
        }
        assertEquals(2,count("attachment_link",f.project));
    }
    @Test void postCommitCleanupFailureRetainsPublishedAccessAndPendingState() throws Exception {
        var f=fixture();var u=assets.createUpload(f.member,f.project,body(TEXT,"cleanup.txt",null),key());put(f,u,TEXT);String intent=key();
        var failedCleanup=service(new DelegateBlobStore(blobs){@Override public void deleteQuarantine(String key){throw new AssetStorageException();}},scanner);
        var result=failedCleanup.complete(f.member,f.project,u.id(),EMPTY,intent);assertEquals("PUBLISHED",result.state());assertTrue(assets.getUpload(f.member,f.project,u.id()).cleanupPending());
        assertArrayEquals(TEXT,download(f.viewer,f.project,result.assetId(),result.revisionId()));try(var raw=blobs.openQuarantine(internal(u.id(),"quarantine_key"))){assertArrayEquals(TEXT,raw.readAllBytes());}
        assertEquals(result,assets.complete(f.member,f.project,u.id(),EMPTY,intent));assertFalse(assets.getUpload(f.member,f.project,u.id()).cleanupPending());assertEquals(1,count("asset_revision",f.project));
    }
    @Test void boundedPagesAndSameHashTenantsRemainIndependent() throws Exception {
        var f=fixture();var other=fixture();var a=publish(f,TEXT,null);var b=publish(other,TEXT,null);assertNotEquals(a.assetId(),b.assetId());assertNotEquals(internal(a.id(),"published_key"),internal(b.id(),"published_key"));
        assertEquals(sha(TEXT),assets.get(f.viewer,f.project,a.assetId()).currentRevision().sha256());denied(404,"NOT_FOUND",()->assets.get(f.member,f.project,b.assetId()));
        for(int i=0;i<26;i++)assets.createUpload(f.member,f.project,body(TEXT,"Page "+i+".txt",null),key());
        var page=assets.uploads(f.member,f.project,null,25);assertEquals(25,page.items().size());assertNotNull(page.nextCursor());var next=assets.uploads(f.member,f.project,page.nextCursor(),25);assertEquals(2,next.items().size());assertNull(next.nextCursor());
        assertEquals(27,java.util.stream.Stream.concat(page.items().stream(),next.items().stream()).map(AssetService.Upload::id).distinct().count());
        assertEquals(1,assets.list(f.viewer,f.project,null,25).items().size());assertArrayEquals(TEXT,download(other.member,other.project,b.assetId(),b.revisionId()));
    }
    @Test void runtimeCannotForgeRevisionFromUnscannedOrRejectedUpload() throws Exception {
        var f=fixture();var u=assets.createUpload(f.member,f.project,body(TEXT,"unscanned.txt",null),key());put(f,u,TEXT);
        UUID a=UUID.fromString(internal(u.id(),"candidate_asset_id")),r=UUID.fromString(internal(u.id(),"candidate_revision_id")),scan=UUID.randomUUID();
        try(var c=connection(f,f.member)) {
            c.setAutoCommit(false);context(c,f,f.member);
            sql(c,"INSERT INTO asset(tenant_id,project_id,id,current_revision_id,created_by) VALUES(?,?,?,?,?)",f.tenant,f.project,a,r,f.member);
            sql(c,"UPDATE asset SET next_revision_no=2 WHERE id=?",a);
            var ex=assertThrows(SQLException.class,()->sql(c,"INSERT INTO asset_revision(tenant_id,project_id,asset_id,id,revision_no,upload_id,scan_result_id,object_key,size,sha256,mime,filename,created_by) VALUES(?,?,?,?,1,?,?,?,?,?,'text/plain','unscanned.txt',?)",f.tenant,f.project,a,r,u.id(),scan,internal(u.id(),"published_key"),TEXT.length,sha(TEXT),f.member));
            assertEquals("23514",ex.getSQLState());assertTrue(ex.getMessage().contains("requires owned confirmed CLEAN upload"));c.rollback();
        }
        try(var c=connection(f,f.member)){sqlDenied(c,"INSERT INTO scan_result(tenant_id,project_id,id,upload_id,status,engine,detail_code,sha256,created_by) VALUES(?,?,?,?,'CLEAN','forged','CLEAN',?,?)","42501",f.tenant,f.project,scan,u.id(),sha(TEXT),f.member);}
        assertEquals(0,count("asset_revision",f.project));var ready=assets.complete(f.member,f.project,u.id(),EMPTY,key());assertEquals("PUBLISHED",ready.state());
        var foreign=fixture();var foreignAsset=publish(foreign,TEXT,null);UUID req=requirement(f);
        try(var c=connection(f,f.member)) {var ex=assertThrows(SQLException.class,()->sql(c,"INSERT INTO attachment_link(tenant_id,project_id,id,kind,requirement_id,asset_id,asset_revision_id,created_by) VALUES(?,?,?,'REQUIREMENT',?,?,?,?)",f.tenant,f.project,UUID.randomUUID(),req,foreignAsset.assetId(),foreignAsset.revisionId(),f.member));assertEquals("23514",ex.getSQLState());assertTrue(ex.getMessage().contains("published CLEAN revision"));}
    }

    private record Fixture(UUID admin,UUID member,UUID viewer,UUID outsider,UUID tenant,UUID domain,UUID project) { }
    private Fixture fixture(){UUID admin=principal(),member=principal(),viewer=principal(),outsider=principal(),tenant=UUID.randomUUID(),domain=UUID.randomUUID();var o=owner();
        o.update("INSERT INTO tenant(id,code,name,created_by) VALUES(?,?,?,?)",tenant,"r11-"+tenant,"R11",admin);o.update("INSERT INTO domain(id,tenant_id,name) VALUES(?,?,?)",domain,tenant,"R11");
        for(UUID actor:List.of(admin,member,viewer,outsider))o.update("INSERT INTO tenant_member(tenant_id,principal_id,roles) VALUES(?,?,?::text[])",tenant,actor,actor.equals(admin)||actor.equals(outsider)?"{TENANT_ADMIN}":"{MEMBER}");
        UUID p=projects.createProject(admin,tenant,domain,"R11-"+key(),"R11").id();projects.putMember(admin,p,member,List.of("PROJECT_MEMBER"),0L,false);projects.putMember(admin,p,viewer,List.of("PROJECT_VIEWER"),0L,false);return new Fixture(admin,member,viewer,outsider,tenant,domain,p);}
    private Fixture sibling(Fixture f){UUID p=projects.createProject(f.admin,f.tenant,f.domain,"Sibling-"+key(),"Sibling").id();projects.putMember(f.admin,p,f.member,List.of("PROJECT_MEMBER"),0L,false);return new Fixture(f.admin,f.member,f.viewer,f.outsider,f.tenant,f.domain,p);}
    private UUID principal(){UUID id=UUID.randomUUID();owner().update("INSERT INTO principal(id,issuer,subject,display_name) VALUES(?,?,?,?)",id,"https://r11.example.invalid",id.toString(),"R11");return id;}
    private UUID requirement(Fixture f){return requirements.create(f.member,f.project,new RequirementService.CreateCommand("Attachment target","body","HIGH"),key()).id();}
    private UUID test(Fixture f){return tests.create(f.member,f.project,new TestCaseService.CreateCommand("MANUAL","Attachment target","","",List.of(new TestCaseService.StepCommand(null,1,"Act","Expected"))),key()).id();}
    private AssetService.Receipt put(Fixture f,AssetService.Upload u,byte[] bytes){return assets.putContent(f.member,f.project,u.id(),new ByteArrayInputStream(bytes),"text/plain");}
    private AssetService.Upload publish(Fixture f,byte[] bytes,UUID asset){var u=assets.createUpload(f.member,f.project,body(bytes,"evidence.txt",asset),key());put(f,u,bytes);return assets.complete(f.member,f.project,u.id(),EMPTY,key());}
    private byte[] download(UUID actor,UUID project,UUID asset,UUID rev)throws IOException {var d=assets.download(actor,project,asset,rev);try(var in=d.stream()){return in.readAllBytes();}}
    private AssetService service(BlobStore store,MalwareScanner scan){return new AssetService(runtime,commands,store,scan,scanWriter,transactions);}
    private void inaccessibleCandidate(Fixture f,AssetService.Upload u){UUID asset=UUID.fromString(internal(u.id(),"candidate_asset_id")),rev=UUID.fromString(internal(u.id(),"candidate_revision_id"));denied(404,"NOT_FOUND",()->assets.download(f.member,f.project,asset,rev));}
    private String internal(UUID id,String column){return owner().queryForObject("SELECT "+column+"::text FROM upload_session WHERE id=?",String.class,id);}
    private JdbcTemplate owner(){return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));}
    private int count(String table,UUID project){return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
    private Map<String,List<String>> snapshot(UUID p){var m=new TreeMap<String,List<String>>();for(String table:List.of("upload_session","asset","asset_revision","scan_result","attachment_link","asset_outbox_event","audit_event","core_command_idempotency"))m.put(table,owner().query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE project_id=? ORDER BY to_jsonb(t)::text",(r,i)->r.getString(1),p));return m;}
    private static void fault(String table,Runnable action){var e=assertThrows(org.springframework.dao.DataAccessException.class,action::run);assertTrue(e.getMostSpecificCause().getMessage().contains("permission denied for table "+table),e.getMostSpecificCause().getMessage());}
    private Connection connection(Fixture f,UUID actor)throws SQLException{var c=DriverManager.getConnection(PG.getJdbcUrl(),"test365alm_runtime","r03_isolated_test_role_only");context(c,f,actor);return c;}
    private static void context(Connection c,Fixture f,UUID actor)throws SQLException{for(var e:Map.of("tenant_id",f.tenant,"project_id",f.project,"principal_id",actor).entrySet())try(var s=c.prepareStatement("SELECT set_config(?,?,false)")){s.setString(1,"test365alm."+e.getKey());s.setString(2,e.getValue().toString());s.executeQuery().close();}}
    private static int sql(Connection c,String query,Object...args)throws SQLException{try(var s=c.prepareStatement(query)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();}}
    private static void sqlDenied(Connection c,String query,String state,Object...args){var e=assertThrows(SQLException.class,()->sql(c,query,args));assertEquals(state,e.getSQLState(),e.getMessage());}
    private static String scalar(Connection c,String query)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    private static final List<String> ASSET_TABLES=List.of("upload_session","asset","asset_revision","scan_result","attachment_link","asset_outbox_event");
    private static void assertRuntimeScopeInvisible(Connection c,Fixture f,UUID actor)throws SQLException {
        assertEquals("test365alm_runtime",scalar(c,"SELECT current_user"));
        assertEquals(f.tenant.toString(),scalar(c,"SELECT current_setting('test365alm.tenant_id')"));
        assertEquals(f.project.toString(),scalar(c,"SELECT current_setting('test365alm.project_id')"));
        assertEquals(actor.toString(),scalar(c,"SELECT current_setting('test365alm.principal_id')"));
        for(String table:ASSET_TABLES)assertEquals("0",scalar(c,"SELECT count(*) FROM "+table),table+" RLS must independently reject the now-invalid membership");
    }
    private static void denied(int status,String code,Runnable action){var e=assertThrows(ProjectAccessException.class,action::run);assertEquals(status,e.status().value());assertEquals(code,e.code());}
    private static JsonNode json(String s){return CoreCommands.JSON.readTree(s);}
    private static JsonNode body(byte[] bytes,String filename,UUID asset){var n=CoreCommands.JSON.createObjectNode().put("filename",filename).put("size",bytes.length).put("sha256",sha(bytes)).put("contentType","text/plain");if(asset!=null)n.put("assetId",asset.toString());return n;}
    private static JsonNode with(JsonNode body,String field,Object value){var n=(tools.jackson.databind.node.ObjectNode)body.deepCopy();n.set(field,CoreCommands.JSON.valueToTree(value));return n;}
    private static JsonNode link(String kind,UUID target,AssetService.Upload a){return CoreCommands.JSON.valueToTree(Map.of("kind",kind,AssetRules.targetField(kind),target,"assetId",a.assetId(),"assetRevisionId",a.revisionId()));}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String key(){return UUID.randomUUID().toString();}
    private static List<Object> concurrent(java.util.function.IntFunction<Object> call)throws Exception {var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{List<Future<Object>> result=new ArrayList<>();for(int i=0;i<2;i++){int index=i;result.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call.apply(index);}));}start.countDown();return List.of(result.get(0).get(90,TimeUnit.SECONDS),result.get(1).get(90,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private static class DelegateBlobStore implements BlobStore {
        final BlobStore delegate;DelegateBlobStore(BlobStore delegate){this.delegate=delegate;}
        public void putQuarantine(String key,InputStream data,long size){delegate.putQuarantine(key,data,size);}public InputStream openQuarantine(String key){return delegate.openQuarantine(key);}
        public void publish(String source,String target){delegate.publish(source,target);}public InputStream openPublished(String key){return delegate.openPublished(key);}public void deleteQuarantine(String key){delegate.deleteQuarantine(key);}
    }
}
