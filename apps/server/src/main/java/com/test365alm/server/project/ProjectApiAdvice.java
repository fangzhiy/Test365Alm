package com.test365alm.server.project;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProjectApiAdvice {
    @ExceptionHandler(ProjectAccessException.class)
    ResponseEntity<Map<String, String>> projectError(ProjectAccessException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage(),
                "requestId", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> invalidRequest(HttpMessageNotReadableException ex) {
        // Keep malformed/unknown JSON on the API contract's 400 path instead
        // of forwarding to the protected /error endpoint (which would turn
        // the parser failure into a misleading 403).
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST",
                "message", "Request body is invalid", "requestId", UUID.randomUUID().toString()));
    }
}
