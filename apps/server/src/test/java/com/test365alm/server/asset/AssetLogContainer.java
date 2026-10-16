package com.test365alm.server.asset;

import java.util.Arrays;
import java.util.Comparator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;

/** Testcontainers dumps getLogs() on startup failure; redact before any logger/report sees it. */
class AssetLogContainer extends GenericContainer<AssetLogContainer> {
    private final String[] secrets;

    AssetLogContainer(DockerImageName image,String... secrets) {
        super(image);
        this.secrets=Arrays.stream(secrets).filter(value->value!=null&&!value.isBlank())
                .flatMap(value->value.length()>8?java.util.stream.Stream.of(value,value.substring(0,8)):java.util.stream.Stream.of(value))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toArray(String[]::new);
    }

    @Override public final String getLogs(){return getLogs(OutputFrame.OutputType.STDOUT,OutputFrame.OutputType.STDERR);}
    @Override public final String getLogs(OutputFrame.OutputType... types) {
        String safe=readLogs(types);
        for(String secret:secrets)safe=safe.replace(secret,"[REDACTED]");
        return safe;
    }
    protected String readLogs(OutputFrame.OutputType... types){return super.getLogs(types);}
}
