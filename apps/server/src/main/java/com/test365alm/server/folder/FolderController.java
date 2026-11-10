package com.test365alm.server.folder;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectAccessResolver;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class FolderController {
    private final FolderService folders;
    private final ProjectAccessResolver actors;
    public FolderController(FolderService folders,ProjectAccessResolver actors) { this.folders=folders;this.actors=actors; }
    @GetMapping("/folders")
    public List<FolderService.Folder> tree(Authentication auth,@PathVariable UUID projectId,@RequestParam String kind) {
        return folders.tree(actors.requirePrincipal(auth),projectId,kind);
    }
    @PostMapping("/folders")
    public ResponseEntity<FolderService.Folder> create(Authentication auth,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false) String key) {
        fields(body,Set.of("kind","parentId","name","sortOrder"));
        var result=folders.create(actors.requirePrincipal(auth),projectId,text(body,"kind"),uuid(body,"parentId"),text(body,"name"),order(body),key);
        return ResponseEntity.status(201).eTag("\""+result.rowVersion()+"\"").body(result);
    }
    @PutMapping("/folders/{id}")
    public ResponseEntity<FolderService.Folder> update(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false) String match) {
        fields(body,Set.of("parentId","name","sortOrder"));
        var result=folders.update(actors.requirePrincipal(auth),projectId,id,uuid(body,"parentId"),text(body,"name"),order(body),match);
        return ResponseEntity.ok().eTag("\""+result.rowVersion()+"\"").body(result);
    }
    @DeleteMapping("/folders/{id}")
    public java.util.Map<String,Boolean> delete(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID id,@RequestHeader(name="If-Match",required=false) String match) {
        folders.delete(actors.requirePrincipal(auth),projectId,id,match);return java.util.Map.of("deleted",true);
    }
    @GetMapping("/folder-placements/{kind}/{id}")
    public FolderService.Placement location(Authentication auth,@PathVariable UUID projectId,@PathVariable String kind,@PathVariable UUID id) {
        return folders.location(actors.requirePrincipal(auth),projectId,kind,id);
    }
    @PutMapping("/folder-placements/{kind}/{id}")
    public FolderService.Placement place(Authentication auth,@PathVariable UUID projectId,@PathVariable String kind,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false) String match) {
        fields(body,Set.of("folderId"));if (!body.has("folderId")) throw ProjectAccessException.invalid("folderId is required (null means root)");
        return folders.place(actors.requirePrincipal(auth),projectId,kind,id,uuid(body,"folderId"),match);
    }
    private static void fields(JsonNode b,Set<String> allowed) {
        if (b==null || !b.isObject()) throw ProjectAccessException.invalid("An object body is required");
        b.propertyNames().forEach(n->{if (!allowed.contains(n)) throw ProjectAccessException.invalid("Unknown field: "+n);});
    }
    private static String text(JsonNode b,String field) { if (!b.has(field) || !b.get(field).isTextual()) throw ProjectAccessException.invalid(field+" must be text");return b.get(field).asText(); }
    private static UUID uuid(JsonNode b,String field) { if (!b.has(field) || b.get(field).isNull()) return null;try { return UUID.fromString(text(b,field)); } catch (IllegalArgumentException e) { throw ProjectAccessException.invalid(field+" must be a UUID"); } }
    private static int order(JsonNode b) { if (!b.has("sortOrder")) return 0;if (!b.get("sortOrder").isIntegralNumber() || !b.get("sortOrder").canConvertToInt()) throw ProjectAccessException.invalid("sortOrder must be an integer");return b.get("sortOrder").intValue(); }
}
