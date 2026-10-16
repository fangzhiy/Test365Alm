package com.test365alm.server.release;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessResolver;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/releases")
public class ReleaseController {
    private final ReleaseService releases;
    private final ProjectAccessResolver actors;
    public ReleaseController(ReleaseService releases,ProjectAccessResolver actors) {this.releases=releases;this.actors=actors;}
    @PostMapping public ResponseEntity<ReleaseService.ReleaseView> create(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return response(201,releases.create(actors.requirePrincipal(a),projectId,body,key));
    }
    @GetMapping public CoreCommands.Page<ReleaseService.ReleaseView> list(Authentication a,@PathVariable UUID projectId,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit) {
        return releases.list(actors.requirePrincipal(a),projectId,cursor,limit);
    }
    @GetMapping("/{id}") public ResponseEntity<ReleaseService.ReleaseView> get(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id) {
        return response(200,releases.get(actors.requirePrincipal(a),projectId,id));
    }
    @PatchMapping("/{id}") public ResponseEntity<ReleaseService.ReleaseView> edit(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return response(200,releases.edit(actors.requirePrincipal(a),projectId,id,body,tag,key));
    }
    @PostMapping("/{id}/cycles") public ResponseEntity<ReleaseService.CycleView> createCycle(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return cycleResponse(201,releases.createCycle(actors.requirePrincipal(a),projectId,id,body,key));
    }
    @GetMapping("/{id}/cycles") public CoreCommands.Page<ReleaseService.CycleView> cycles(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit) {
        return releases.cycles(actors.requirePrincipal(a),projectId,id,cursor,limit);
    }
    @GetMapping("/{id}/cycles/{cycle}") public ResponseEntity<ReleaseService.CycleView> cycle(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID cycle) {
        return cycleResponse(200,releases.getCycle(actors.requirePrincipal(a),projectId,id,cycle));
    }
    @PatchMapping("/{id}/cycles/{cycle}") public ResponseEntity<ReleaseService.CycleView> editCycle(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID cycle,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return cycleResponse(200,releases.editCycle(actors.requirePrincipal(a),projectId,id,cycle,body,tag,key));
    }
    @GetMapping("/{id}/scope") public CoreCommands.Page<ReleaseService.ScopeView> scope(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit) {
        return releases.scope(actors.requirePrincipal(a),projectId,id,cursor,limit);
    }
    @PostMapping("/{id}/scope") public ResponseEntity<ReleaseService.ScopeView> addScope(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return ResponseEntity.status(201).body(releases.addScope(actors.requirePrincipal(a),projectId,id,body,key));
    }
    @DeleteMapping("/{id}/scope/{scope}") public ReleaseService.Removed removeScope(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID scope,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        return releases.removeScope(actors.requirePrincipal(a),projectId,id,scope,key);
    }
    @PostMapping("/{id}/milestones/evaluate") public ResponseEntity<ReleaseService.Snapshot> evaluate(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of());
        return ResponseEntity.status(201).body(releases.evaluate(actor,projectId,id,key));
    }
    @GetMapping("/{id}/snapshots") public CoreCommands.Page<ReleaseService.SnapshotSummary> snapshots(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit) {
        return releases.snapshots(actors.requirePrincipal(a),projectId,id,cursor,limit);
    }
    @GetMapping("/{id}/snapshots/{snapshot}") public ReleaseService.Snapshot snapshot(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID snapshot) {
        return releases.snapshot(actors.requirePrincipal(a),projectId,id,snapshot);
    }
    private static ResponseEntity<ReleaseService.ReleaseView> response(int status,ReleaseService.ReleaseView view) {return ResponseEntity.status(status).eTag("\""+view.rowVersion()+"\"").body(view);}
    private static ResponseEntity<ReleaseService.CycleView> cycleResponse(int status,ReleaseService.CycleView view) {return ResponseEntity.status(status).eTag("\""+view.rowVersion()+"\"").body(view);}
}
