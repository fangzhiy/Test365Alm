package com.test365alm.server.history;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessResolver;

/** 只读审计历史；objectType 仅 test_case | defect | run。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/history")
public class HistoryController {
    private final HistoryService history;
    private final ProjectAccessResolver actors;

    public HistoryController(HistoryService history, ProjectAccessResolver actors) {
        this.history = history;
        this.actors = actors;
    }

    @GetMapping("/{objectType}/{objectId}")
    public CoreCommands.Page<HistoryService.HistoryEntry> history(Authentication a, @PathVariable UUID projectId,
            @PathVariable String objectType, @PathVariable UUID objectId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return history.history(actors.requirePrincipal(a), projectId, objectType, objectId, cursor, limit);
    }
}
