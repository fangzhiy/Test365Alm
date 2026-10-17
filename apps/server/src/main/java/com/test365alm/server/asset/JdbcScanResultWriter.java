package com.test365alm.server.asset;

import java.util.UUID;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.test365alm.server.project.ProjectAccessException;

/** A scan journal commits independently of publication; no advisory locks or owner privileges. */
public final class JdbcScanResultWriter implements ScanResultWriter {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcScanResultWriter(DataSource dataSource) {
        this.dataSource=dataSource;this.jdbc=new JdbcTemplate(dataSource);this.jdbc.setQueryTimeout(8);
        this.transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transaction.setTimeout(10);
    }
    @Override public UUID record(UUID tenant,UUID project,UUID upload,UUID actor,String sha256,MalwareScanner.ScanResult result) {
        if(tenant==null||project==null||upload==null||actor==null||sha256==null||!sha256.matches("[a-f0-9]{64}")
                ||result==null||result.status()==null)throw unavailable();
        try {
            return transaction.execute(status->{
                Boolean restricted=jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND pg_has_role(current_user,'test365alm_scanner','MEMBER') AND NOT pg_has_role(current_user,'test365alm_runtime','MEMBER') FROM pg_roles WHERE rolname=current_user",Boolean.class);
                if(!Boolean.TRUE.equals(restricted))throw unavailable();
                jdbc.queryForObject("SELECT set_config('test365alm.tenant_id',?,true)",String.class,tenant.toString());
                jdbc.queryForObject("SELECT set_config('test365alm.project_id',?,true)",String.class,project.toString());
                jdbc.queryForObject("SELECT set_config('test365alm.principal_id',?,true)",String.class,actor.toString());
                jdbc.queryForObject("SELECT set_config('lock_timeout','5000ms',true)",String.class);
                jdbc.queryForObject("SELECT set_config('statement_timeout','8000ms',true)",String.class);
                UUID id=UUID.randomUUID();
                var inserted=jdbc.query("INSERT INTO scan_result(tenant_id,project_id,id,upload_id,status,engine,detail_code,sha256,created_by) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING id",
                        (rs,i)->rs.getObject(1,UUID.class),tenant,project,id,upload,result.status().name(),safe(result.engine()),safe(result.detail()),sha256,actor);
                if(!inserted.isEmpty())return inserted.get(0);
                var clean=jdbc.query("SELECT id FROM scan_result WHERE tenant_id=? AND project_id=? AND upload_id=? AND created_by=? AND sha256=? AND status='CLEAN'",
                        (rs,i)->rs.getObject(1,UUID.class),tenant,project,upload,actor,sha256);
                if(result.status()!=MalwareScanner.Status.CLEAN||clean.size()!=1)throw unavailable();
                return clean.get(0);
            });
        }catch(RuntimeException ex){throw unavailable();}
    }
    private static String safe(String value){return value!=null&&value.matches("[A-Za-z0-9._ -]{1,100}")?value:"unspecified";}
    public static ProjectAccessException unavailable(){return AssetRules.error("SCAN_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,"Trusted scan result storage is unavailable");}
    @Override public void close(){if(dataSource instanceof HikariDataSource hikari)hikari.close();}
}
