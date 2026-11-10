package com.test365alm.server.review;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.test365alm.server.project.ProjectAccessResolver;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tests/{testCaseId}/revisions/{revisionId}/review")
public class TestReviewController {
    private final TestReviewService service;
    private final ProjectAccessResolver actors;
    public TestReviewController(TestReviewService service,ProjectAccessResolver actors) {this.service=service;this.actors=actors;}
    @GetMapping
    public ResponseEntity<TestReviewService.ReviewView> get(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID testCaseId,
        @PathVariable UUID revisionId,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return response(service.get(actors.requirePrincipal(auth),projectId,testCaseId,revisionId,cursor,limit));
    }
    @PostMapping
    public ResponseEntity<TestReviewService.ReviewView> act(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID testCaseId,
        @PathVariable UUID revisionId,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false) String tag,
        @RequestHeader(name="Idempotency-Key",required=false) String key) {
        return response(service.act(actors.requirePrincipal(auth),projectId,testCaseId,revisionId,body,tag,key));
    }
    private static ResponseEntity<TestReviewService.ReviewView> response(TestReviewService.ReviewView view) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG,"\""+view.rowVersion()+"\"").body(view);
    }
}
