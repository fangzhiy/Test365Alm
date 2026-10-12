package com.test365alm.server.project;

import org.springframework.http.HttpStatus;

/** Structured, safe errors for project authorization commands. */
public final class ProjectAccessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ProjectAccessException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static ProjectAccessException unauthenticated() {
        return new ProjectAccessException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "Authentication is required");
    }

    public static ProjectAccessException forbidden() {
        return new ProjectAccessException("FORBIDDEN", HttpStatus.FORBIDDEN, "The principal is not authorized");
    }

    public static ProjectAccessException notFound() {
        return new ProjectAccessException("NOT_FOUND", HttpStatus.NOT_FOUND, "The requested resource was not found");
    }

    public static ProjectAccessException conflict(String code, String message) {
        return new ProjectAccessException(code, HttpStatus.CONFLICT, message);
    }

    public static ProjectAccessException invalid(String message) {
        return new ProjectAccessException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message);
    }

    public static ProjectAccessException preconditionRequired(String message) {
        return new ProjectAccessException("PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED, message);
    }

    public static ProjectAccessException preconditionFailed(String code, String message) {
        return new ProjectAccessException(code, HttpStatus.PRECONDITION_FAILED, message);
    }

    public static ProjectAccessException unprocessable(String message) {
        return new ProjectAccessException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
