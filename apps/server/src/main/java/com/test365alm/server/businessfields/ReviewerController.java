package com.test365alm.server.businessfields;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessResolver;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/reviewer-candidates")
public class ReviewerController {
    private final ReviewerService service;
    private final ProjectAccessResolver actors;
    public ReviewerController(ReviewerService service,ProjectAccessResolver actors){this.service=service;this.actors=actors;}
    @GetMapping public CoreCommands.Page<ReviewerService.Candidate> list(Authentication authentication,@PathVariable UUID projectId,
        @RequestParam(required=false) String search,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit,
        @RequestParam(required=false) UUID principalId) {
        return service.list(actors.requirePrincipal(authentication),projectId,search,cursor,limit,principalId);
    }
}
