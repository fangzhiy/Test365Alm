package com.test365alm.server.requirement;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.test365alm.server.project.ProjectAccessResolver;

/** HTTP boundary for the first requirement identity/revision slice. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirements")
public class RequirementController {
    private final RequirementService requirements;
    private final ProjectAccessResolver actors;

    public RequirementController(RequirementService requirements, ProjectAccessResolver actors) {
        this.requirements = requirements;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<RequirementService.RequirementView> create(Authentication authentication,
            @PathVariable UUID projectId, @RequestBody CreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID actor = actors.requirePrincipal(authentication);
        RequirementService.RequirementView result = requirements.create(actor, projectId,
                new RequirementService.CreateCommand(request.title(), request.body(), request.priority()), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, "\"" + result.rowVersion() + "\"").body(result);
    }

    @GetMapping
    public RequirementService.RequirementPage list(Authentication authentication, @PathVariable UUID projectId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return requirements.list(actors.requirePrincipal(authentication), projectId, query, cursor, limit);
    }

    @GetMapping("/{requirementId}")
    public ResponseEntity<RequirementService.RequirementView> get(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID requirementId) {
        RequirementService.RequirementView result = requirements.getAuthorized(actors.requirePrincipal(authentication),
                projectId, requirementId);
        return withEtag(result);
    }

    @PatchMapping("/{requirementId}")
    public ResponseEntity<RequirementService.RequirementView> update(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestBody PatchRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RequirementService.RequirementView result = requirements.update(actors.requirePrincipal(authentication),
                projectId, requirementId,
                new RequirementService.UpdateCommand(request.title(), request.body(), request.priority()),
                ifMatch, idempotencyKey);
        return withEtag(result);
    }

    @GetMapping("/{requirementId}/revisions")
    public List<RequirementService.RevisionView> revisions(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID requirementId) {
        return requirements.revisions(actors.requirePrincipal(authentication), projectId, requirementId);
    }

    @GetMapping("/{requirementId}/revisions/{revisionId}")
    public RequirementService.RevisionView revision(Authentication authentication,
            @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID revisionId) {
        return requirements.revision(actors.requirePrincipal(authentication), projectId, requirementId, revisionId);
    }

    private static ResponseEntity<RequirementService.RequirementView> withEtag(
            RequirementService.RequirementView result) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + result.rowVersion() + "\"").body(result);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRequest(String title, String body, String priority) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PatchRequest(String title, String body, String priority) { }
}
