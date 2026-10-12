package com.test365alm.server.project;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProjectApiAdvice {
    @ExceptionHandler(ProjectAccessException.class)
    ResponseEntity<Map<String, String>> projectError(ProjectAccessException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage(),
                "requestId", UUID.randomUUID().toString()));
    }
}
