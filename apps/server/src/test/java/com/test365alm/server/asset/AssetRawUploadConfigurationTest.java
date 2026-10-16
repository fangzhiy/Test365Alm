package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AssetRawUploadConfigurationTest {
    @Test void actualApplicationConfigurationDoesNotRegisterMultipartParsing() {
        new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.properties")
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("multipartResolver"),
                    "raw upload must not first enter Boot's multipart parser");
                assertTrue(context.getBeansOfType(MultipartConfigElement.class).isEmpty(),
                    "raw-only application must not enable Servlet multipart parsing");
            });
    }
}
