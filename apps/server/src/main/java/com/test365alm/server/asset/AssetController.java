package com.test365alm.server.asset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AssetController {
    private final AssetService assets;private final ProjectAccessResolver actors;
    public AssetController(AssetService assets,ProjectAccessResolver actors){this.assets=assets;this.actors=actors;}
    @PostMapping("/uploads") public ResponseEntity<AssetService.Upload> create(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){return ResponseEntity.status(201).body(assets.createUpload(actors.requirePrincipal(a),projectId,body,key));}
    @GetMapping("/uploads") public CoreCommands.Page<AssetService.Upload> uploads(Authentication a,@PathVariable UUID projectId,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return assets.uploads(actors.requirePrincipal(a),projectId,cursor,limit);}
    @GetMapping("/uploads/{id}") public AssetService.Upload upload(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id){return assets.getUpload(actors.requirePrincipal(a),projectId,id);}
    @PutMapping("/uploads/{id}/content") public AssetService.Receipt content(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,HttpServletRequest request)throws IOException {
        // Raw stream: no multipart buffering and no trust in Content-Length.
        return assets.putContent(actors.requirePrincipal(a),projectId,id,request.getInputStream(),request.getContentType());
    }
    @PostMapping("/uploads/{id}/complete") public AssetService.Upload complete(Authentication a,@PathVariable UUID projectId,@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){return assets.complete(actors.requirePrincipal(a),projectId,id,body,key);}
    @GetMapping("/assets") public CoreCommands.Page<AssetService.Asset> list(Authentication a,@PathVariable UUID projectId,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return assets.list(actors.requirePrincipal(a),projectId,cursor,limit);}
    @GetMapping("/assets/{asset}") public AssetService.Asset get(Authentication a,@PathVariable UUID projectId,@PathVariable UUID asset){return assets.get(actors.requirePrincipal(a),projectId,asset);}
    @GetMapping("/assets/{asset}/revisions") public CoreCommands.Page<AssetService.Revision> revisions(Authentication a,@PathVariable UUID projectId,@PathVariable UUID asset,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return assets.revisions(actors.requirePrincipal(a),projectId,asset,cursor,limit);}
    @GetMapping("/assets/{asset}/revisions/{revision}") public AssetService.Revision revision(Authentication a,@PathVariable UUID projectId,@PathVariable UUID asset,@PathVariable UUID revision){return assets.revision(actors.requirePrincipal(a),projectId,asset,revision);}
    @GetMapping("/assets/{asset}/revisions/{revision}/download") public ResponseEntity<InputStreamResource> download(Authentication a,@PathVariable UUID projectId,@PathVariable UUID asset,@PathVariable UUID revision){
        var result=assets.download(actors.requirePrincipal(a),projectId,asset,revision);var r=result.revision();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.mime())).contentLength(r.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(r.filename(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(new InputStreamResource(result.stream()));
    }
    @PostMapping("/attachments") public ResponseEntity<AssetService.Attachment> attachment(Authentication a,@PathVariable UUID projectId,@RequestBody JsonNode body,@RequestHeader(name="Idempotency-Key",required=false)String key){return ResponseEntity.status(201).body(assets.createAttachment(actors.requirePrincipal(a),projectId,body,key));}
    @GetMapping("/attachments") public CoreCommands.Page<AssetService.Attachment> attachments(Authentication a,@PathVariable UUID projectId,@RequestParam Map<String,String> parameters){
        UUID actor=actors.requirePrincipal(a);String kind=parameters.get("kind"),field=AssetRules.targetField(kind);
        Set<String> allowed=Set.of("kind",field,"cursor","limit");if(!allowed.containsAll(parameters.keySet()))throw ProjectAccessException.invalid("Only the matching typed target is allowed");
        UUID target=AssetRules.uuid(CoreCommands.JSON.valueToTree(parameters.get(field)));Integer limit=null;
        if(parameters.containsKey("limit")){try{limit=Integer.valueOf(parameters.get("limit"));}catch(NumberFormatException ex){throw ProjectAccessException.invalid("Invalid limit");}}
        return assets.attachments(actor,projectId,kind,target,parameters.get("cursor"),limit);
    }
    @ExceptionHandler(AssetStorageException.class) public ResponseEntity<Map<String,String>> storageFailure(){
        return ResponseEntity.status(503).body(Map.of("code","STORAGE_UNAVAILABLE","message","Storage unavailable; retry the same upload intent","requestId",UUID.randomUUID().toString()));
    }
}
