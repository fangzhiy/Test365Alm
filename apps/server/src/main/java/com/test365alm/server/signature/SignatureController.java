package com.test365alm.server.signature;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.test365alm.server.project.ProjectAccessResolver;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/signatures/{kind}/{objectId}/{snapshotId}")
public class SignatureController {
    private final SignatureService service;
    private final ProjectAccessResolver actors;
    private final SignatureLoginFlow login;
    public SignatureController(SignatureService service,ProjectAccessResolver actors,SignatureLoginFlow login) {
        this.service=service;this.actors=actors;this.login=login;
    }
    @GetMapping public SignatureService.View get(Authentication auth,@PathVariable UUID projectId,@PathVariable String kind,
            @PathVariable UUID objectId,@PathVariable UUID snapshotId) {
        return service.get(actors.requirePrincipal(auth),projectId,SignatureRules.kind(kind),objectId,snapshotId);
    }
    @PostMapping("/challenges") public SignatureService.Challenge prepare(Authentication auth,HttpServletRequest request,
            @PathVariable UUID projectId,@PathVariable String kind,@PathVariable UUID objectId,@PathVariable UUID snapshotId,
            @RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false) String tag,@RequestHeader(name="Idempotency-Key",required=false) String key) {
        return service.prepare(actors.requirePrincipal(auth),projectId,SignatureRules.kind(kind),objectId,snapshotId,body,tag,key,login.binding(request.getSession()));
    }
    @GetMapping("/challenges/{challengeId}/authenticate") public ResponseEntity<Void> authenticate(Authentication auth,HttpServletRequest request,
            @PathVariable UUID projectId,@PathVariable String kind,@PathVariable UUID objectId,@PathVariable UUID snapshotId,@PathVariable UUID challengeId) {
        var pending=service.authenticate(actors.requirePrincipal(auth),projectId,SignatureRules.kind(kind),objectId,snapshotId,challengeId,login.binding(request.getSession()));
        login.begin(request.getSession(),pending);
        return ResponseEntity.status(303).header("Location","/oauth2/authorization/test365alm").build();
    }
    @PostMapping public SignatureService.View sign(Authentication auth,HttpServletRequest request,@PathVariable UUID projectId,@PathVariable String kind,
            @PathVariable UUID objectId,@PathVariable UUID snapshotId,@RequestBody JsonNode body,@RequestHeader(name="If-Match",required=false) String tag,
            @RequestHeader(name="Idempotency-Key",required=false) String key) {
        UUID actor=actors.requirePrincipal(auth);UUID challengeId=SignatureRules.challenge(body);
        return service.sign(actor,projectId,SignatureRules.kind(kind),objectId,snapshotId,body,tag,key,login.binding(request.getSession()),login.proof(request.getSession(),challengeId));
    }
}
