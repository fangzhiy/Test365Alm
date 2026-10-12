package com.test365alm.server.metadata;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectAccessResolver;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/field-definitions")
public class FieldDefinitionController {
    private final FieldDefinitionService fields;private final ProjectAccessResolver actors;
    public FieldDefinitionController(FieldDefinitionService fields,ProjectAccessResolver actors){this.fields=fields;this.actors=actors;}
    @GetMapping public CoreCommands.Page<FieldDefinitionService.FieldView> list(Authentication a,@PathVariable UUID projectId,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit){return fields.list(actors.requirePrincipal(a),projectId,cursor,limit);}
    @GetMapping("/system") public java.util.List<FieldDefinitionService.SystemField> system(Authentication a,@PathVariable UUID projectId){return fields.system(actors.requirePrincipal(a),projectId);}
    @PostMapping public ResponseEntity<FieldDefinitionService.FieldView> create(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){return response(201,fields.create(actors.requirePrincipal(a),projectId,body,key));}
    @GetMapping("/{id}") public ResponseEntity<FieldDefinitionService.FieldView> get(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id){return response(200,fields.get(actors.requirePrincipal(a),projectId,id,null));}
    @GetMapping("/{id}/revisions/{revision}") public ResponseEntity<FieldDefinitionService.FieldView> revision(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable long revision){return response(200,fields.get(actors.requirePrincipal(a),projectId,id,revision));}
    @PatchMapping("/{id}") public ResponseEntity<FieldDefinitionService.FieldView> revise(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false)String tag,@RequestHeader(name="Idempotency-Key",required=false)String key){return response(200,fields.revise(actors.requirePrincipal(a),projectId,id,body,tag,key));}
    @PostMapping("/{id}/validate") public FieldDefinitionService.Validation validate(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body){
        UUID actor=actors.requirePrincipal(a);FieldRules.fields(body,Set.of("revision","value"));JsonNode n=body.get("revision");
        if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<1)throw ProjectAccessException.invalid("Positive revision required");
        return fields.validate(actor,projectId,id,n.longValue(),body.get("value"));
    }
    private static ResponseEntity<FieldDefinitionService.FieldView> response(int status,FieldDefinitionService.FieldView v){return ResponseEntity.status(status).eTag("\""+v.rowVersion()+"\"").body(v);}
}
