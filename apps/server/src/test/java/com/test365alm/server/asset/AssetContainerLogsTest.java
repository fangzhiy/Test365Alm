package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;

class AssetContainerLogsTest {
    @Test void automaticFailureLogPathRemovesCredentialsButPreservesFailureEvidence() {
        String access="synthetic-access-identifier",secret="private-secret-fixture";
        String raw="name=admin-"+access.substring(0,8)+", accessKey="+access+"\nsecret="+secret
                +"\ntruncated="+secret.substring(0,8)+"\nERROR bucket provisioning timed out; exit=1";
        var container=new AssetLogContainer(DockerImageName.parse("example.invalid/owned-fixture:1"),access,secret){
            @Override protected String readLogs(OutputFrame.OutputType... types){return raw;}
        };
        for(String logs:new String[]{container.getLogs(),container.getLogs(OutputFrame.OutputType.STDERR)}) {
            for(String forbidden:new String[]{access,secret,access.substring(0,8),secret.substring(0,8)})assertFalse(logs.contains(forbidden));
            assertTrue(logs.contains("ERROR bucket provisioning timed out; exit=1"));
            assertTrue(logs.contains("[REDACTED]"));
        }
    }
}
