package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

class AssetUploadTimeoutTest {
    @Test void rawServletUploadStopsWhenClientLeavesBodyIncomplete() throws Exception {
        var factory=new TomcatServletWebServerFactory(0);factory.setAddress(InetAddress.getLoopbackAddress());
        new AssetUploadTimeoutConfiguration().assetUploadTimeout(new MockEnvironment().withProperty("test365alm.assets.upload-idle-timeout-ms","250")).customize(factory);
        CountDownLatch started=new CountDownLatch(1),failed=new CountDownLatch(1);AtomicReference<IOException> error=new AtomicReference<>();
        var server=factory.getWebServer(context->{var servlet=context.addServlet("slow-upload-probe",new HttpServlet(){
            @Override protected void doPut(HttpServletRequest request,HttpServletResponse response)throws IOException {
                try{assertEquals('x',request.getInputStream().read());started.countDown();request.getInputStream().readNBytes(9);}
                catch(IOException ex){error.set(ex);failed.countDown();response.setStatus(408);}
            }});servlet.addMapping("/probe");});
        try {
            server.start();
            try(Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getPort())) {
                client.setSoTimeout(4000);client.getOutputStream().write(("PUT /probe HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\nConnection: close\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
                assertTrue(started.await(3,TimeUnit.SECONDS),"request must actually enter the raw ServletInputStream read");
                assertTrue(failed.await(3,TimeUnit.SECONDS),"an incomplete upload must hit the configured inactivity deadline");
                Throwable cause=error.get();while(cause!=null&&!(cause instanceof SocketTimeoutException))cause=cause.getCause();
                assertInstanceOf(SocketTimeoutException.class,cause,"must fail from the read timeout, not another startup or HTTP error");
            }
        }finally{server.stop();}
    }
    @Test void uploadTimeoutCannotBeInfiniteOrUnbounded() {
        for(String value:new String[]{"0","-1","120001"})assertThrows(IllegalArgumentException.class,()->new AssetUploadTimeoutConfiguration().assetUploadTimeout(new MockEnvironment().withProperty("test365alm.assets.upload-idle-timeout-ms",value)));
    }
}
