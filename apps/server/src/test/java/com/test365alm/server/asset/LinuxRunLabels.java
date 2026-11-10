package com.test365alm.server.asset;

import java.util.HashMap;
import com.github.dockerjava.api.command.CreateContainerCmd;
import org.testcontainers.core.CreateContainerCmdModifier;

/** Optional test-only provenance; these labels do not restrict Docker socket authority. */
public final class LinuxRunLabels implements CreateContainerCmdModifier {
    public CreateContainerCmd modify(CreateContainerCmd command) {
        return label(command,System.getenv("TEST365ALM_LINUX_RUN_ID"),System.getenv("TEST365ALM_LINUX_SOURCE_SHA"));
    }
    static CreateContainerCmd label(CreateContainerCmd command,String run,String source) {
        if(run==null&&source==null)return command;
        if(run==null||!run.matches("[0-9a-f]{32}")||source==null||!source.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Invalid isolated Linux test provenance");
        var labels=new HashMap<String,String>();
        if(command.getLabels()!=null)labels.putAll(command.getLabels());
        for(var entry:java.util.Map.of("com.test365alm.linux.run",run,"com.test365alm.linux.source",source).entrySet()) {
            String previous=labels.putIfAbsent(entry.getKey(),entry.getValue());
            if(previous!=null&&!previous.equals(entry.getValue()))throw new IllegalArgumentException("Conflicting Linux test provenance");
        }
        return command.withLabels(labels);
    }
}
