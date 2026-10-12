package com.test365alm.server.platform.version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {
    private final String productName;
    private final String version;
    private final String commit;

    @Autowired
    public VersionController(ObjectProvider<BuildProperties> buildProperties, Environment environment) {
        BuildProperties properties = buildProperties.getIfAvailable();
        this.productName = properties != null
                ? properties.getName()
                : environment.getProperty("spring.application.name", "Test365Alm");
        this.version = properties != null
                ? properties.getVersion()
                : environment.getProperty("app.version", "unknown");
        String buildCommit = properties != null ? properties.get("buildCommit") : null;
        this.commit = buildCommit != null
                ? buildCommit
                : environment.getProperty("app.commit", "unknown");
    }

    VersionController(String productName, String version, String commit) {
        this.productName = productName;
        this.version = version;
        this.commit = commit;
    }

    @GetMapping("/api/v1/version")
    public VersionResponse version() {
        return new VersionResponse(productName, version, commit);
    }

    public record VersionResponse(String productName, String version, String commit) {
    }
}
