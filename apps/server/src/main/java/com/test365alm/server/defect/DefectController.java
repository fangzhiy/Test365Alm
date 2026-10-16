package com.test365alm.server.defect;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.*;

@RestController @RequestMapping("/api/v1/projects/{projectId}/defects")
public class DefectController {
 private final DefectService defects;private final ProjectAccessResolver actors;
 public DefectController(DefectService defects,ProjectAccessResolver actors){this.defects=defects;this.actors=actors;}
 @GetMapping public CoreCommands.Page<DefectService.DefectView> list(Authentication a,@PathVariable UUID projectId,@RequestParam(required=false) String q,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return defects.list(actors.requirePrincipal(a),projectId,q,cursor,limit);}
 @GetMapping("/{id}") public ResponseEntity<DefectService.DefectView> get(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id){return response(200,defects.get(actors.requirePrincipal(a),projectId,id));}
 @PostMapping public ResponseEntity<DefectService.DefectView> create(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){
  UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of("title","description","severity","priority","requirementId"));
  UUID requirement=body.has("requirementId")?uuid(body.get("requirementId")):null;
  return response(201,defects.create(actor,projectId,content(body),requirement,key));
 }
 @PostMapping("/from-step") public ResponseEntity<DefectService.DefectView> fromStep(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){
  UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of("title","description","severity","priority","runId","attemptId","stepKey"));
  return response(201,defects.fromStep(actor,projectId,content(body),uuid(body.get("runId")),uuid(body.get("attemptId")),uuid(body.get("stepKey")),key));
 }
 @PatchMapping("/{id}") public ResponseEntity<DefectService.DefectView> edit(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key){return response(200,defects.edit(actors.requirePrincipal(a),projectId,id,body,tag,key));}
 @PostMapping("/{id}/transitions") public ResponseEntity<DefectService.DefectView> transition(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key){
  UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of("status","reason"));return response(200,defects.transition(actor,projectId,id,FieldRules.text(body.get("status")),FieldRules.text(body.get("reason")),tag,key));
 }
 @GetMapping("/{id}/revisions") public CoreCommands.Page<DefectService.Revision> history(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return defects.history(actors.requirePrincipal(a),projectId,id,cursor,limit);}
 @GetMapping("/{id}/revisions/{revision}") public DefectService.Revision revision(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID revision){return defects.revision(actors.requirePrincipal(a),projectId,id,revision);}
 @GetMapping("/{id}/verification") public DefectEvidence.Verification verification(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id){return defects.verification(actors.requirePrincipal(a),projectId,id);}
 @GetMapping("/{id}/retests") public CoreCommands.Page<DefectEvidence.Retest> retests(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return defects.retests(actors.requirePrincipal(a),projectId,id,cursor,limit);}
 @PostMapping("/{id}/retests") public DefectEvidence.RetestStart retest(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key){UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of());return defects.retest(actor,projectId,id,tag,key);}
 private static UUID uuid(JsonNode n){try{return UUID.fromString(FieldRules.text(n));}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("UUID reference required");}}
 private static JsonNode content(JsonNode body){var n=CoreCommands.JSON.createObjectNode();for(String field:List.of("title","description","severity","priority")){if(body.has(field))n.set(field,body.get(field));}return n;}
 private static ResponseEntity<DefectService.DefectView> response(int status,DefectService.DefectView d){return ResponseEntity.status(status).eTag("\""+d.rowVersion()+"\"").body(d);}
}
