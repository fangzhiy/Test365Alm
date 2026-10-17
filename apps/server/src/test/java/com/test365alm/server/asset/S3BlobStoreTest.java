package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class S3BlobStoreTest {
    @Test void privateAdapterSignsRequestsAndSeparatesQuarantineFromPublished() throws Exception {
        List<String> paths=new ArrayList<>();List<String> copies=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange->{
            assertNotNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestURI().getQuery()); // No presigned URLs.
            paths.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
            String source=exchange.getRequestHeaders().getFirst("x-amz-copy-source");
            byte[] body=exchange.getRequestBody().readAllBytes();
            byte[] response=new byte[0];
            if(source!=null){copies.add(source);response="<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ETag>\"evidence\"</ETag><LastModified>2026-10-17T00:00:00Z</LastModified></CopyObjectResult>".getBytes(StandardCharsets.UTF_8);}
            else if(exchange.getRequestMethod().equals("GET"))response="safe".getBytes(StandardCharsets.UTF_8);
            else if(exchange.getRequestMethod().equals("PUT"))assertEquals("safe",new String(body,StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("ETag","\"evidence\"");
            exchange.getResponseHeaders().set("Last-Modified","Sat, 17 Oct 2026 00:00:00 GMT");
            if(exchange.getRequestMethod().equals("HEAD"))exchange.getResponseHeaders().set("Content-Length","4");
            exchange.getResponseHeaders().set("Content-Type",source==null?"application/octet-stream":"application/xml");exchange.sendResponseHeaders(200,response.length==0?-1:response.length);
            if(response.length>0)exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var store=new S3BlobStore("http://127.0.0.1:"+server.getAddress().getPort(),"isolated-test-access","isolated-test-secret","quarantine","published",2000);
            store.putQuarantine("q/one",new ByteArrayInputStream("safe".getBytes(StandardCharsets.UTF_8)),4);
            try(var content=store.openQuarantine("q/one")){assertEquals("safe",new String(content.readAllBytes(),StandardCharsets.UTF_8));}
            store.publish("q/one","p/two");try(var content=store.openPublished("p/two")){assertEquals(4,content.readAllBytes().length);}
            store.deleteQuarantine("q/one");
            assertEquals(List.of("PUT /quarantine/q/one","GET /quarantine/q/one","HEAD /quarantine/q/one","PUT /published/p/two","GET /published/p/two","DELETE /quarantine/q/one"),paths);
            assertEquals(List.of("/quarantine/q/one"),copies);
        }finally{server.stop(0);}
    }
    @Test void disabledAdaptersAndUnavailableStorageNeverReturnCleanOrLeakConfiguration() {
        var config=new AssetConfiguration();var env=new MockEnvironment();
        assertThrows(AssetStorageException.class,()->config.blobStore(env).openPublished("unknown"));
        assertEquals(MalwareScanner.Status.ERROR,config.malwareScanner(env).scan(InputStream.nullInputStream()).status());
        var store=new S3BlobStore("http://127.0.0.1:1","sensitive-access","sensitive-secret","quarantine","published",100);
        var error=assertThrows(AssetStorageException.class,()->store.openPublished("q/one"));
        assertEquals("Private asset storage is unavailable",error.getMessage());assertNull(error.getCause());
    }
}
