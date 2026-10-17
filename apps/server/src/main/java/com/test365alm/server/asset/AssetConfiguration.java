package com.test365alm.server.asset;

import java.io.InputStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class AssetConfiguration {
    @Bean(destroyMethod="close") ScanResultWriter scanResultWriter(Environment env) {
        if(!env.getProperty("test365alm.assets.enabled",Boolean.class,false))return (tenant,project,upload,actor,hash,result)->{throw JdbcScanResultWriter.unavailable();};
        String username=env.getRequiredProperty("test365alm.assets.scanner-db.username"),password=env.getRequiredProperty("test365alm.assets.scanner-db.password");
        if(username.isBlank()||password.isBlank())throw new IllegalArgumentException("Trusted scanner database credentials are required");
        HikariConfig config=new HikariConfig();config.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        config.setUsername(username);config.setPassword(password);config.setMaximumPoolSize(2);config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);config.setValidationTimeout(2000);config.setInitializationFailTimeout(-1);
        config.setPoolName("asset-scan-journal");
        // Deliberately not a Spring DataSource bean: never replace the application's runtime datasource.
        return new JdbcScanResultWriter(new HikariDataSource(config));
    }
    @Bean BlobStore blobStore(Environment env) {
        if(!env.getProperty("test365alm.assets.enabled",Boolean.class,false))return new BlobStore(){
            public void putQuarantine(String key,InputStream body,long size){throw new AssetStorageException();}
            public InputStream openQuarantine(String key){throw new AssetStorageException();}
            public void publish(String source,String target){throw new AssetStorageException();}
            public InputStream openPublished(String key){throw new AssetStorageException();}
            public void deleteQuarantine(String key){throw new AssetStorageException();}
        };
        return new S3BlobStore(env.getRequiredProperty("test365alm.assets.s3.endpoint"),env.getRequiredProperty("test365alm.assets.s3.access-key"),
                env.getRequiredProperty("test365alm.assets.s3.secret-key"),env.getRequiredProperty("test365alm.assets.s3.quarantine-bucket"),
                env.getRequiredProperty("test365alm.assets.s3.published-bucket"),env.getProperty("test365alm.assets.s3.timeout-ms",Integer.class,15000));
    }
    @Bean MalwareScanner malwareScanner(Environment env) {
        if(!env.getProperty("test365alm.assets.enabled",Boolean.class,false))return stream->new MalwareScanner.ScanResult(MalwareScanner.Status.ERROR,"disabled","SCAN_UNAVAILABLE");
        return new ClamdMalwareScanner(env.getRequiredProperty("test365alm.assets.clamd.host"),env.getProperty("test365alm.assets.clamd.port",Integer.class,3310),
                env.getProperty("test365alm.assets.clamd.connect-timeout-ms",Integer.class,2000),env.getProperty("test365alm.assets.clamd.timeout-ms",Integer.class,30000));
    }
}
