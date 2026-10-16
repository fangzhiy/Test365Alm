package com.test365alm.server.trace;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessResolver;
import com.test365alm.server.project.ProjectAccessException;

@RestController @RequestMapping("/api/v1/projects/{projectId}")
public class TraceLinkController {
    private final TraceLinkService links;private final ProjectAccessResolver actors;
    public TraceLinkController(TraceLinkService links,ProjectAccessResolver actors){this.links=links;this.actors=actors;}
    @PostMapping("/trace-links") public ResponseEntity<TraceLinkService.LinkView> create(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){
        UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of("requirementId","testCaseId"));return response(201,links.create(actor,projectId,id(body.get("requirementId")),id(body.get("testCaseId")),key));}
    @GetMapping("/trace-links") public CoreCommands.Page<TraceLinkService.LinkView> list(Authentication a,@PathVariable UUID projectId,@RequestParam UUID requirementId,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit){return links.list(actors.requirePrincipal(a),projectId,requirementId,cursor,limit);}
    @PostMapping("/trace-links/{id}/invalidate") public ResponseEntity<TraceLinkService.LinkView> invalidate(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key){
        UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of());return response(200,links.invalidate(actor,projectId,id,tag,key));}
    @GetMapping("/coverage") public TraceLinkService.Coverage coverage(Authentication a,@PathVariable UUID projectId,@RequestParam UUID requirementId){return links.coverage(actors.requirePrincipal(a),projectId,requirementId);}
    private static UUID id(JsonNode n){try{return UUID.fromString(FieldRules.text(n));}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("UUID endpoint required");}}
    private static ResponseEntity<TraceLinkService.LinkView> response(int status,TraceLinkService.LinkView v){return ResponseEntity.status(status).eTag("\""+v.rowVersion()+"\"").body(v);}
}
