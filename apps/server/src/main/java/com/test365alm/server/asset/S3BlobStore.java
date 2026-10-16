package com.test365alm.server.asset;

import java.io.InputStream;
import java.time.Duration;
import io.minio.*;
import okhttp3.OkHttpClient;

/** Private S3 adapter: bucket provisioning/policy is operational configuration, not a request side effect. */
public final class S3BlobStore implements BlobStore {
    private final MinioClient client;
    private final String quarantineBucket,publishedBucket;
    public S3BlobStore(String endpoint,String accessKey,String secretKey,String quarantineBucket,String publishedBucket,int timeoutMs) {
        if(endpoint==null||endpoint.isBlank()||accessKey==null||accessKey.isBlank()||secretKey==null||secretKey.isBlank()
                ||quarantineBucket==null||publishedBucket==null||quarantineBucket.equals(publishedBucket)||timeoutMs<1||timeoutMs>120000)
            throw new IllegalArgumentException("Invalid private storage configuration");
        OkHttpClient http=new OkHttpClient.Builder().connectTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs)).writeTimeout(Duration.ofMillis(timeoutMs))
                .callTimeout(Duration.ofMillis(timeoutMs)).followRedirects(false).followSslRedirects(false).build();
        client=MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).region("us-east-1").httpClient(http).build();
        this.quarantineBucket=quarantineBucket;this.publishedBucket=publishedBucket;
    }
    public void putQuarantine(String key,InputStream content,long size) {
        key(key);if(size<1||size>AssetRules.MAX_SIZE)throw new AssetStorageException();
        try {client.putObject(PutObjectArgs.builder().bucket(quarantineBucket).object(key).stream(content,size,-1L).contentType("application/octet-stream").build());}
        catch(Exception ex){throw new AssetStorageException();}
    }
    public InputStream openQuarantine(String key){return open(quarantineBucket,key);}
    public InputStream openPublished(String key){return open(publishedBucket,key);}
    private InputStream open(String bucket,String key){key(key);try{return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());}catch(Exception ex){throw new AssetStorageException();}}
    public void publish(String quarantineKey,String publishedKey) {
        key(quarantineKey);key(publishedKey);
        try{client.copyObject(CopyObjectArgs.builder().bucket(publishedBucket).object(publishedKey)
                .source(SourceObject.builder().bucket(quarantineBucket).object(quarantineKey).build()).build());}
        catch(Exception ex){throw new AssetStorageException();}
    }
    public void deleteQuarantine(String key){key(key);try{client.removeObject(RemoveObjectArgs.builder().bucket(quarantineBucket).object(key).build());}catch(Exception ex){throw new AssetStorageException();}}
    private static void key(String key){if(key==null||!key.matches("[a-zA-Z0-9/_-]{1,200}"))throw new AssetStorageException();}
}
