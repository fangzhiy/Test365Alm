package com.test365alm.server.execution;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectAccessResolver;

import tools.jackson.databind.JsonNode;

/** HTTP boundary for the M09 manual execution slice. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ExecutionController {
    private final ExecutionService executions;
    private final ProjectAccessResolver actors;

    public ExecutionController(ExecutionService executions, ProjectAccessResolver actors) {
        this.executions = executions;
        this.actors = actors;
    }

    @PostMapping("/test-sets")
    public ResponseEntity<ExecutionService.TestSetView> createSet(Authentication authentication,
            @PathVariable UUID projectId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        fields(body, Set.of("name", "description"));
        UUID actor = actors.requirePrincipal(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(executions.createSet(actor, projectId,
                requiredText(body.get("name"), "name"), text(body.get("description")), key));
    }

    @GetMapping("/test-sets")
    public List<ExecutionService.TestSetView> listSets(Authentication authentication, @PathVariable UUID projectId,
            @RequestParam(name="limit", required=false) Integer limit) {
        return executions.listSets(actors.requirePrincipal(authentication), projectId, limit);
    }

    @GetMapping("/test-sets/{setId}")
    public ExecutionService.TestSetDetail getSet(Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID setId) {
        return executions.getSet(actors.requirePrincipal(authentication), projectId, setId);
    }

    @PostMapping("/test-sets/{setId}/instances")
    public ResponseEntity<ExecutionService.InstanceView> addInstance(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID setId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        fields(body, Set.of("testCaseId", "testRevisionId"));
        UUID actor = actors.requirePrincipal(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(executions.addInstance(actor, projectId, setId,
                uuid(body.get("testCaseId"), "testCaseId"), uuid(body.get("testRevisionId"), "testRevisionId"), key));
    }

    @GetMapping("/test-sets/{setId}/instances")
    public List<ExecutionService.InstanceView> listInstances(Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID setId,
            @RequestParam(name="limit", required=false) Integer limit) {
        return executions.listInstances(actors.requirePrincipal(authentication), projectId, setId, limit);
    }

    @PostMapping("/runs")
    public ResponseEntity<ExecutionService.RunDetail> createRun(Authentication authentication,
            @PathVariable UUID projectId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        fields(body, Set.of("testInstanceId", "instanceId", "mode"));
        UUID actor = actors.requirePrincipal(authentication);
        JsonNode instance = first(body, "testInstanceId", "instanceId");
        if (body.get("mode") != null && !"MANUAL".equalsIgnoreCase(requiredText(body.get("mode"), "mode"))) {
            throw ProjectAccessException.invalid("Only MANUAL runs are supported");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(executions.createRun(actor, projectId,
                uuid(instance, "testInstanceId"), key));
    }

    @GetMapping("/runs")
    public List<ExecutionService.RunView> listRuns(Authentication authentication, @PathVariable UUID projectId,
            @RequestParam(name="limit", required=false) Integer limit) {
        return executions.listRuns(actors.requirePrincipal(authentication), projectId, limit);
    }

    @GetMapping("/runs/{runId}")
    public ExecutionService.RunDetail getRun(Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID runId) {
        return executions.run(actors.requirePrincipal(authentication), projectId, runId);
    }

    @GetMapping("/runs/{runId}/attempts")
    public List<ExecutionService.AttemptView> attempts(Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID runId,
            @RequestParam(name="limit", required=false) Integer limit) {
        return executions.attempts(actors.requirePrincipal(authentication), projectId, runId, limit);
    }

    @GetMapping("/runs/{runId}/attempts/{attemptId}")
    public ExecutionService.AttemptView attempt(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID attemptId) {
        return executions.attempt(actors.requirePrincipal(authentication), projectId, runId, attemptId);
    }

    @PutMapping("/runs/{runId}/attempts/{attemptId}/steps/{stepKey}")
    public ExecutionService.AttemptView saveStep(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID attemptId, @PathVariable UUID stepKey,
            @RequestBody JsonNode body, @RequestHeader(name="Idempotency-Key", required=false) String key,
            @RequestHeader(name="If-Match", required=false) String ifMatch) {
        fields(body, Set.of("actualResult", "actual", "conclusion", "outcome", "expectedVersion"));
        long version = expectedVersion(body, ifMatch);
        JsonNode actual = first(body, "actualResult", "actual");
        JsonNode result = first(body, "conclusion", "outcome");
        return executions.saveStep(actors.requirePrincipal(authentication), projectId, runId, attemptId, stepKey,
                text(actual), requiredText(result, "conclusion"), version, key);
    }

    @PostMapping("/runs/{runId}/attempts/{attemptId}/pause")
    public ExecutionService.AttemptView pause(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID attemptId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key,
            @RequestHeader(name="If-Match", required=false) String ifMatch) {
        fields(body, Set.of("expectedVersion"));
        return executions.pause(actors.requirePrincipal(authentication), projectId, runId, attemptId, expectedVersion(body, ifMatch), key);
    }

    @PostMapping("/runs/{runId}/attempts/{attemptId}/resume")
    public ExecutionService.AttemptView resume(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID attemptId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key,
            @RequestHeader(name="If-Match", required=false) String ifMatch) {
        fields(body, Set.of("expectedVersion"));
        return executions.resume(actors.requirePrincipal(authentication), projectId, runId, attemptId, expectedVersion(body, ifMatch), key);
    }

    @PostMapping("/runs/{runId}/attempts/{attemptId}/finish")
    public ExecutionService.AttemptView finish(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID attemptId, @RequestBody JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key,
            @RequestHeader(name="If-Match", required=false) String ifMatch) {
        fields(body, Set.of("expectedVersion"));
        return executions.finish(actors.requirePrincipal(authentication), projectId, runId, attemptId, expectedVersion(body, ifMatch), key);
    }

    @PostMapping("/runs/{runId}/attempts")
    public ExecutionService.AttemptView rerun(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID runId, @RequestBody(required=false) JsonNode body,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        fields(body, Set.of());
        return executions.rerun(actors.requirePrincipal(authentication), projectId, runId, key);
    }

    private static long number(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw ProjectAccessException.invalid(field + " must be a positive integer");
        }
        return value.longValue();
    }
    private static long expectedVersion(JsonNode body, String ifMatch) {
        JsonNode supplied = body == null ? null : body.get("expectedVersion");
        Long jsonVersion = supplied == null ? null : number(supplied, "expectedVersion");
        Long headerVersion = ifMatch == null ? null : parseIfMatch(ifMatch);
        if (jsonVersion == null && headerVersion == null) {
            throw ProjectAccessException.preconditionRequired("If-Match or expectedVersion is required");
        }
        if (jsonVersion != null && headerVersion != null && !jsonVersion.equals(headerVersion)) {
            throw ProjectAccessException.invalid("If-Match and expectedVersion must match");
        }
        return jsonVersion != null ? jsonVersion : headerVersion;
    }
    private static UUID uuid(JsonNode value, String field) {
        String raw = requiredText(value, field);
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ex) { throw ProjectAccessException.invalid(field + " must be a UUID"); }
    }
    private static String requiredText(JsonNode value, String field) {
        String result = text(value);
        if (result == null || result.isBlank()) throw ProjectAccessException.invalid(field + " is required");
        return result.trim();
    }
    private static String text(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw ProjectAccessException.invalid("Request field must be a string");
        return value.textValue();
    }
    private static JsonNode first(JsonNode body, String primary, String alias) {
        JsonNode value = body == null ? null : body.get(primary);
        JsonNode alternate = body == null ? null : body.get(alias);
        if (value != null && alternate != null && !value.equals(alternate)) {
            throw ProjectAccessException.invalid(primary + " and " + alias + " must match");
        }
        return value != null ? value : alternate;
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw ProjectAccessException.invalid("Request body must be an object");
        for (String field : body.propertyNames()) if (!allowed.contains(field)) throw ProjectAccessException.invalid("Unknown request field: " + field);
    }
    private static long parseIfMatch(String value) {
        if (value == null || !value.matches("\\\"[1-9][0-9]*\\\"")) throw ProjectAccessException.preconditionRequired("If-Match is required");
        try {
            long parsed = Long.parseLong(value.substring(1, value.length() - 1));
            if (parsed < 1) throw ProjectAccessException.invalid("If-Match must be a positive integer");
            return parsed;
        } catch (NumberFormatException ex) {
            throw ProjectAccessException.invalid("If-Match must be a positive integer");
        }
    }
}

