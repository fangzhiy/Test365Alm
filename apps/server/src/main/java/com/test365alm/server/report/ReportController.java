package com.test365alm.server.report;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import com.test365alm.server.project.ProjectAccessResolver;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/reports")
public class ReportController {
    private final ReportService reports;private final ProjectAccessResolver actors;
    public ReportController(ReportService reports,ProjectAccessResolver actors){this.reports=reports;this.actors=actors;}
    @GetMapping("/runs/{runId}/attempts/{attemptId}")
    public ResponseEntity<ReportService.View> run(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID runId,@PathVariable UUID attemptId) {
        return response(reports.run(actors.requirePrincipal(auth),projectId,runId,attemptId));
    }
    @GetMapping("/test-sets/{setId}")
    public ResponseEntity<ReportService.View> testSet(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID setId,@RequestParam(required=false) String status) {
        return response(reports.testSet(actors.requirePrincipal(auth),projectId,setId,status));
    }
    @GetMapping("/defects")
    public ResponseEntity<ReportService.View> defects(Authentication auth,@PathVariable UUID projectId,@RequestParam(required=false) UUID testSetId,
            @RequestParam(required=false) String status,@RequestParam(required=false) String severity,@RequestParam(required=false) String q) {
        return response(reports.defects(actors.requirePrincipal(auth),projectId,testSetId,status,severity,q));
    }
    private ResponseEntity<ReportService.View> response(ReportService.View report){return ResponseEntity.ok().header("Cache-Control","no-store").body(report);}
}
