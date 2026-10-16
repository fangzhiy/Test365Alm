package com.test365alm.server.asset;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Limits socket read inactivity while consuming raw bodies; not an overall upload deadline. */
@Configuration
public class AssetUploadTimeoutConfiguration {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> assetUploadTimeout(Environment env) {
        int timeout=env.getProperty("test365alm.assets.upload-idle-timeout-ms",Integer.class,15000);
        if(timeout<100||timeout>120000)throw new IllegalArgumentException("Upload inactivity timeout must be within 100..120000 ms");
        return factory->factory.addConnectorCustomizers(connector->{
            if(!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol))
                throw new IllegalStateException("A bounded raw upload connector is required");
            protocol.setDisableUploadTimeout(false);protocol.setConnectionUploadTimeout(timeout);
        });
    }
}
