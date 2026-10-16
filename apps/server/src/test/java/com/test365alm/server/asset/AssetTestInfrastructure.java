package com.test365alm.server.asset;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import io.minio.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;

/** Real, test-owned, disposable S3 + scanner shared by IT classes in this JVM only. */
public final class AssetTestInfrastructure {
    public static final String S3_IMAGE="chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d";
    public static final String CLAMD_IMAGE="clamav/clamav:1.5.4-debian13-slim@sha256:9a96980b265cb2b51a20bdcca921dc80aa55ed0e56cdfb076198ee27a9171927";
    public static final String RUN_ID=UUID.randomUUID().toString();
    private static final String ACCESS="r11"+UUID.randomUUID().toString().replace("-","");
    private static final String SECRET=UUID.randomUUID().toString()+UUID.randomUUID();
    private static final String SCANNER_USER="r11_scanner_"+UUID.randomUUID().toString().replace("-","");
    private static final String SCANNER_PASSWORD=UUID.randomUUID().toString()+UUID.randomUUID();
    public static final String QUARANTINE="r11-quarantine",PUBLISHED="r11-published";
    private static final GenericContainer<?> S3=new AssetLogContainer(DockerImageName.parse(S3_IMAGE),ACCESS,SECRET)
            .withLabel("com.test365alm.r11.run",RUN_ID).withExposedPorts(8333)
            .withEnv("AWS_ACCESS_KEY_ID",ACCESS).withEnv("AWS_SECRET_ACCESS_KEY",SECRET)
            .withCommand("mini","-dir=/data","-ip=127.0.0.1","-ip.bind=0.0.0.0","-s3.port=8333",
                    "-bucket="+QUARANTINE+","+PUBLISHED,"-master.telemetry=false","-webdav=false","-admin.ui=false")
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403)).withStartupTimeout(Duration.ofMinutes(2));
    private static final GenericContainer<?> CLAMD=new GenericContainer<>(DockerImageName.parse(CLAMD_IMAGE))
            .withLabel("com.test365alm.r11.run",RUN_ID).withExposedPorts(3310)
            .withEnv("CLAMAV_NO_FRESHCLAMD","true").withEnv("CLAMAV_NO_MILTERD","true")
            .withEnv("CLAMD_CONF_StreamMaxLength","26M")
            .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(4));
    private static boolean started;
    private static ScannerIdentity pausedScanner;
    private AssetTestInfrastructure() { }
    public static synchronized void start() {
        if(started)return;
        S3.start();CLAMD.start();
        try {
            MinioClient client=client();
            if(!client.bucketExists(BucketExistsArgs.builder().bucket(QUARANTINE).build())
                    ||!client.bucketExists(BucketExistsArgs.builder().bucket(PUBLISHED).build()))
                throw new IllegalStateException("Owned S3 buckets were not provisioned");
            var verdict=scanner().scan(new ByteArrayInputStream("safe isolated evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(verdict.status()!=MalwareScanner.Status.CLEAN)throw new IllegalStateException("Real clamd did not accept benign control");
            started=true;
        }catch(Exception ex){throw new IllegalStateException("Owned asset infrastructure is not ready");}
    }
    public static void register(DynamicPropertyRegistry registry) {
        start();registry.add("test365alm.assets.enabled",()->true);
        registry.add("test365alm.assets.s3.endpoint",AssetTestInfrastructure::endpoint);
        registry.add("test365alm.assets.s3.access-key",()->ACCESS);registry.add("test365alm.assets.s3.secret-key",()->SECRET);
        registry.add("test365alm.assets.s3.quarantine-bucket",()->QUARANTINE);registry.add("test365alm.assets.s3.published-bucket",()->PUBLISHED);
        registry.add("test365alm.assets.clamd.host",CLAMD::getHost);registry.add("test365alm.assets.clamd.port",()->CLAMD.getMappedPort(3310));
        registry.add("test365alm.assets.clamd.timeout-ms",()->15000);
    }
    public static void registerScannerDatabase(DynamicPropertyRegistry registry,PostgreSQLContainer<?> pg) {
        provisionScannerDatabase(pg);
        registry.add("test365alm.assets.scanner-db.username",()->SCANNER_USER);
        registry.add("test365alm.assets.scanner-db.password",()->SCANNER_PASSWORD);
    }
    public static synchronized javax.sql.DataSource provisionScannerDatabase(PostgreSQLContainer<?> pg) {
        if(!pg.isRunning()||pg.getContainerId()==null)throw new IllegalStateException("A running owned Testcontainers database is required");
        var owner=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        if(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname='test365alm_scanner'",Integer.class)==0)
            owner.execute("CREATE ROLE test365alm_scanner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        if(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Integer.class,SCANNER_USER)==0)
            owner.execute("CREATE ROLE "+SCANNER_USER+" LOGIN PASSWORD '"+SCANNER_PASSWORD+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS IN ROLE test365alm_scanner");
        return new DriverManagerDataSource(pg.getJdbcUrl(),SCANNER_USER,SCANNER_PASSWORD);
    }
    public static String endpoint(){return "http://"+S3.getHost()+":"+S3.getMappedPort(8333);}
    public static BlobStore store(){return new S3BlobStore(endpoint(),ACCESS,SECRET,QUARANTINE,PUBLISHED,15000);}
    public static MalwareScanner scanner(){return new ClamdMalwareScanner(CLAMD.getHost(),CLAMD.getMappedPort(3310),2000,15000);}
    public static MinioClient client(){return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS,SECRET).region("us-east-1").build();}
    public static int anonymousStatus(String bucket,String key)throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                HttpRequest.newBuilder(URI.create(endpoint()+"/"+bucket+"/"+key)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    /** Freeze the real owned scanner without replacing the endpoint cached by the application bean. */
    public static synchronized void pauseScanner(){
        var before=owned(CLAMD);
        if(!Boolean.TRUE.equals(before.getState().getRunning())||Boolean.TRUE.equals(before.getState().getPaused())||pausedScanner!=null)
            throw new IllegalStateException("Owned scanner must be running and unpaused before fault injection");
        pausedScanner=scannerIdentity(before);
        CLAMD.getDockerClient().pauseContainerCmd(CLAMD.getContainerId()).exec();
        var paused=owned(CLAMD);
        if(!Boolean.TRUE.equals(paused.getState().getPaused())||!pausedScanner.equals(scannerIdentity(paused)))
            throw new IllegalStateException("Scanner pause changed its owned process or endpoint");
    }
    public static synchronized void resumeScanner(){
        var before=owned(CLAMD);
        if(pausedScanner==null||!pausedScanner.equals(scannerIdentity(before))||!Boolean.TRUE.equals(before.getState().getPaused()))
            throw new IllegalStateException("Refusing to resume an unexpected scanner process or endpoint");
        CLAMD.getDockerClient().unpauseContainerCmd(CLAMD.getContainerId()).exec();
        var resumed=owned(CLAMD);
        if(Boolean.TRUE.equals(resumed.getState().getPaused())||!Boolean.TRUE.equals(resumed.getState().getRunning())||!pausedScanner.equals(scannerIdentity(resumed)))
            throw new IllegalStateException("Scanner resume did not preserve its owned process and endpoint");
        pausedScanner=null;
        long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<end){if(scanner().scan(new ByteArrayInputStream(new byte[]{'a'})).status()==MalwareScanner.Status.CLEAN)return;
            try{Thread.sleep(250);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted during scanner recovery");}}
        throw new IllegalStateException("Owned scanner did not recover a real CLEAN verdict after unpause");
    }
    private record ScannerIdentity(String id,String startedAt,String ports) { }
    private static ScannerIdentity scannerIdentity(InspectContainerResponse actual) {
        var bindings=actual.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3310));
        if(bindings==null||bindings.length==0)throw new IllegalStateException("Owned scanner has no real published endpoint");
        String ports=java.util.Arrays.stream(bindings).map(b->b.getHostIp()+":"+b.getHostPortSpec()).sorted().collect(java.util.stream.Collectors.joining(","));
        return new ScannerIdentity(actual.getId(),actual.getState().getStartedAt(),ports);
    }
    private static InspectContainerResponse owned(GenericContainer<?> resource){
        var actual=resource.getDockerClient().inspectContainerCmd(resource.getContainerId()).exec();
        if(!RUN_ID.equals(actual.getConfig().getLabels().get("com.test365alm.r11.run")))throw new IllegalStateException("Refusing unowned container operation");
        return actual;
    }
}
