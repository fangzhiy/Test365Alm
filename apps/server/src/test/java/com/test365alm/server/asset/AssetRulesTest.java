package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

class AssetRulesTest {
    @Test void uploadDeclarationsAreStrictBoundedAndFilenameIsOnlySafeMetadata() {
        var body=CoreCommands.JSON.readTree("{\"filename\":\"../safe\\r\\n\\u0000\\\".txt\",\"size\":5,\"sha256\":\""+"a".repeat(64)+"\",\"contentType\":\"text/plain\"}");
        var c=AssetRules.upload(body);assertEquals("..safe.txt",c.filename());assertEquals(5,c.size());
        for(String field:new String[]{"bucket","key","createdBy","clean"}) {
            var invalid=body.deepCopy();((tools.jackson.databind.node.ObjectNode)invalid).put(field,"unsafe");
            assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->AssetRules.upload(invalid)).code());
        }
        for(String size:new String[]{"0","-1","26214401","1.5","\"5\"","null"}) {
            var invalid=CoreCommands.JSON.readTree("{\"filename\":\"x\",\"size\":"+size+",\"sha256\":\""+"a".repeat(64)+"\",\"contentType\":\"text/plain\"}");
            assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->AssetRules.upload(invalid)).code());
        }
        assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->AssetRules.upload(CoreCommands.JSON.readTree("{\"filename\":\"x\",\"size\":1,\"sha256\":\"bad\",\"contentType\":\"text/plain\"}"))).code());
    }
    @Test void typedAttachmentCannotMixTargetsOrAcceptUntrustedRevisionMetadata() {
        String id="00000000-0000-0000-0000-000000000001";
        var body=CoreCommands.JSON.readTree("{\"kind\":\"REQUIREMENT\",\"requirementId\":\""+id+"\",\"assetId\":\""+id+"\",\"assetRevisionId\":\""+id+"\"}");
        assertEquals("REQUIREMENT",AssetRules.link(body).kind());
        var wrong=body.deepCopy();((tools.jackson.databind.node.ObjectNode)wrong).put("testCaseId",id);
        assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->AssetRules.link(wrong)).code());
        assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->AssetRules.link(CoreCommands.JSON.readTree("null"))).code());
    }
    @Test void actualStreamLimitDoesNotTrustDeclaredOrHttpLength() throws Exception {
        var stream=new InputStream(){long remaining=AssetRules.MAX_SIZE+1;public int read(){return remaining-->0?'a':-1;}public int read(byte[] b,int o,int len){if(remaining==0)return -1;int n=(int)Math.min(len,remaining);java.util.Arrays.fill(b,o,o+n,(byte)'a');remaining-=n;return n;}};
        assertEquals("FILE_TOO_LARGE",assertThrows(ProjectAccessException.class,()->AssetRules.readBounded(stream,OutputStream.nullOutputStream())).code());
        var output=new ByteArrayOutputStream();assertEquals(3,AssetRules.readBounded(new ByteArrayInputStream(new byte[]{1,2,3}),output));assertArrayEquals(new byte[]{1,2,3},output.toByteArray());
    }
}
