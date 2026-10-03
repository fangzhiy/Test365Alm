package com.test365alm.server.testcase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.test365alm.server.project.ProjectAccessResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/** HTTP boundary for the first MANUAL test-case/revision slice. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tests")
public class TestCaseController {
    private final TestCaseService tests;
    private final ProjectAccessResolver actors;

    public TestCaseController(TestCaseService tests, ProjectAccessResolver actors) {
        this.tests = tests;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<TestCaseService.TestCaseView> create(Authentication authentication,
            @PathVariable UUID projectId, @RequestBody JsonNode requestBody,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        CreateRequest request = CreateRequest.from(requestBody);
        UUID actor = actors.requirePrincipal(authentication);
        TestCaseService.TestCaseView result = tests.create(actor, projectId, request.command(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.ETAG, etag(result.rowVersion())).body(result);
    }

    @GetMapping
    public TestCaseService.TestCasePage list(Authentication authentication, @PathVariable UUID projectId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return tests.list(actors.requirePrincipal(authentication), projectId, query, cursor, limit);
    }

    @GetMapping("/{testCaseId}")
    public ResponseEntity<TestCaseService.TestCaseView> get(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID testCaseId) {
        TestCaseService.TestCaseView result = tests.getAuthorized(actors.requirePrincipal(authentication), projectId, testCaseId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(result.rowVersion())).body(result);
    }

    @PostMapping("/{testCaseId}/revisions")
    public ResponseEntity<TestCaseService.TestCaseView> appendRevision(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID testCaseId, @RequestBody JsonNode requestBody,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RevisionRequest request = RevisionRequest.from(requestBody);
        TestCaseService.TestCaseView result = tests.appendRevision(actors.requirePrincipal(authentication), projectId,
                testCaseId, request.command(), ifMatch, idempotencyKey);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(result.rowVersion())).body(result);
    }

    @GetMapping("/{testCaseId}/revisions")
    public List<TestCaseService.RevisionView> revisions(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID testCaseId) {
        return tests.revisions(actors.requirePrincipal(authentication), projectId, testCaseId);
    }

    @GetMapping("/{testCaseId}/revisions/{revisionId}")
    public TestCaseService.RevisionView revision(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID testCaseId, @PathVariable UUID revisionId) {
        return tests.revision(actors.requirePrincipal(authentication), projectId, testCaseId, revisionId);
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    static final class CreateRequest {
        private static final Set<String> FIELDS = Set.of("testType", "title", "description", "preconditions", "steps");
        private final TestCaseService.CreateCommand command;

        private CreateRequest(TestCaseService.CreateCommand command) { this.command = command; }

        static CreateRequest from(JsonNode body) {
            validateObject(body, FIELDS);
            JsonNode steps = body.get("steps");
            if (body.get("testType") == null || body.get("testType").isNull()) throw new IllegalArgumentException("testType is required");
            if (steps == null || steps.isNull()) throw new IllegalArgumentException("steps is required");
            return new CreateRequest(new TestCaseService.CreateCommand(
                    requiredText(body.get("testType"), "testType"), text(body.get("title"), "title"),
                    text(body.get("description"), "description"), text(body.get("preconditions"), "preconditions"),
                    parseSteps(steps, false)));
        }

        TestCaseService.CreateCommand command() { return command; }
    }

    static final class RevisionRequest {
        private static final Set<String> FIELDS = Set.of("title", "description", "preconditions", "steps");
        private final TestCaseService.RevisionCommand command;

        private RevisionRequest(TestCaseService.RevisionCommand command) { this.command = command; }

        static RevisionRequest from(JsonNode body) {
            validateObject(body, FIELDS);
            JsonNode steps = body.get("steps");
            if (steps == null || steps.isNull()) throw new IllegalArgumentException("steps is required for a revision");
            return new RevisionRequest(new TestCaseService.RevisionCommand(
                    requiredText(body.get("title"), "title"), requiredText(body.get("description"), "description"),
                    requiredText(body.get("preconditions"), "preconditions"), parseSteps(steps, true)));
        }

        TestCaseService.RevisionCommand command() { return command; }
    }

    private static List<TestCaseService.StepCommand> parseSteps(JsonNode value, boolean allowStepKey) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new IllegalArgumentException("steps must be an array");
        List<TestCaseService.StepCommand> steps = new ArrayList<>();
        for (JsonNode node : value) {
            Set<String> fields = allowStepKey ? Set.of("stepKey", "ordinal", "action", "expected")
                    : Set.of("ordinal", "action", "expected");
            validateObject(node, fields);
            JsonNode ordinal = node.get("ordinal");
            if (ordinal != null && (!ordinal.isIntegralNumber() || !ordinal.canConvertToInt() || ordinal.intValue() < 1)) {
                throw new IllegalArgumentException("ordinal must be a positive integer");
            }
            UUID stepKey = null;
            if (allowStepKey && node.get("stepKey") != null && !node.get("stepKey").isNull()) {
                String raw = text(node.get("stepKey"), "stepKey");
                try {
                    stepKey = UUID.fromString(raw);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("stepKey must be a UUID");
                }
            }
            steps.add(new TestCaseService.StepCommand(stepKey, ordinal == null ? null : ordinal.intValue(), text(node.get("action"), "action"),
                    text(node.get("expected"), "expected")));
        }
        return steps;
    }

    private static String text(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.textValue();
    }

    private static String requiredText(JsonNode value, String field) {
        if (value == null || value.isNull() || !value.isTextual()) throw new IllegalArgumentException(field + " is required and must be a string");
        return value.textValue();
    }

    private static void validateObject(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Request body must be an object");
        for (String name : body.propertyNames()) {
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }
}
