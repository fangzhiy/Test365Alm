package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import com.test365alm.server.project.ProjectAccessException;

class FileRulesTest {
    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    @Test void detectsActualTextAndDeletesOwnedSpool() throws Exception {
        byte[] bytes="ordinary evidence text\n".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Path path;
        try(var content=FileRules.inspect(new ByteArrayInputStream(bytes),bytes.length,hash(bytes),"text/plain")) {
            assertEquals("text/plain",content.mime()); assertEquals(bytes.length,content.size());
            assertEquals(hash(bytes),content.sha256()); path=content.path(); assertArrayEquals(bytes,Files.readAllBytes(path));
        }
        assertFalse(Files.exists(path));
    }
    @Test void interruptedReadDeletesOnlyTheOwnedSpoolBeforePropagatingFailure() throws Exception {
        var directory=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        java.util.Set<java.nio.file.Path> prior;
        try(var files=Files.list(directory)){prior=files.collect(java.util.stream.Collectors.toSet());}
        byte[] marker=("owned-spool-"+java.util.UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var owned=new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
        InputStream broken=new InputStream(){
            private boolean emitted;
            @Override public int read(){throw new UnsupportedOperationException("Bulk read expected");}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                if(!emitted){emitted=true;assertTrue(length>=marker.length);System.arraycopy(marker,0,bytes,offset,marker.length);return marker.length;}
                java.util.List<java.nio.file.Path> matching=new java.util.ArrayList<>();
                try(var files=Files.list(directory)) {
                    for(var path:files.filter(p->!prior.contains(p)&&p.getFileName().toString().startsWith("test365alm-upload-")&&p.getFileName().toString().endsWith(".bin")).toList()) {
                        if(Files.isRegularFile(path)&&Files.size(path)==marker.length&&java.util.Arrays.equals(marker,Files.readAllBytes(path)))matching.add(path);
                    }
                }
                assertEquals(1,matching.size(),"Identify only this call's new spool by its unique synthetic bytes");
                owned.set(matching.get(0));
                throw new IOException("Synthetic request stream interrupted");
            }
        };
        var failure=assertThrows(IOException.class,()->FileRules.inspect(broken,marker.length,"0".repeat(64),"text/plain"));
        assertEquals("Synthetic request stream interrupted",failure.getMessage());
        assertNotNull(owned.get());assertFalse(Files.exists(owned.get()),"The production finally must delete its owned interrupted spool");
    }
    @Test void rejectsSizeHashAndMimeMismatch() throws Exception {
        byte[] b="ordinary evidence".getBytes(StandardCharsets.UTF_8); String h=hash(b);
        assertEquals("CONTENT_MISMATCH",assertThrows(ProjectAccessException.class,()->FileRules.inspect(new ByteArrayInputStream(b),b.length+1,h,"text/plain")).code());
        assertEquals("CONTENT_MISMATCH",assertThrows(ProjectAccessException.class,()->FileRules.inspect(new ByteArrayInputStream(b),b.length,"0".repeat(64),"text/plain")).code());
        assertEquals("CONTENT_MISMATCH",assertThrows(ProjectAccessException.class,()->FileRules.inspect(new ByteArrayInputStream(b),b.length,h,"image/png")).code());
    }
    @Test void rejectsActiveAndUnknownContentWithoutFilenameHint() throws Exception {
        for(String value:new String[]{"<html><script>alert(1)</script></html>","<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>","#!/bin/sh\necho unsafe","<?php echo 1; ?>","MZ\u0000\u0000binary"}) {
            byte[] b=value.getBytes(StandardCharsets.UTF_8);
            assertEquals("UNSUPPORTED_FILE_TYPE",assertThrows(ProjectAccessException.class,()->FileRules.inspect(new ByteArrayInputStream(b),b.length,hash(b),"text/plain")).code());
        }
    }
    @Test void countsActualStreamBeyondDeclaredLength() {
        InputStream huge=new InputStream(){long left=AssetRules.MAX_SIZE+1;public int read(){return left-->0?'a':-1;} public int read(byte[] b,int off,int len){if(left<=0)return -1;int n=(int)Math.min(left,len);java.util.Arrays.fill(b,off,off+n,(byte)'a');left-=n;return n;}};
        assertEquals("FILE_TOO_LARGE",assertThrows(ProjectAccessException.class,()->FileRules.inspect(huge,1,"0".repeat(64),"text/plain")).code());
    }
    @Test void eicarTextDetectionAllowsRealScannerBoundary() throws Exception {
        byte[] b=("X5O!P%@AP[4\\PZX54(P^)7CC)7}$"+"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
        // Detector-only portability check does not write an AV signature on the developer
        // host. AssetDatabaseIT performs the full EICAR spool/storage/real-clamd path.
        assertEquals("text/plain",new org.apache.tika.Tika().detect(new ByteArrayInputStream(b)));
    }
}
