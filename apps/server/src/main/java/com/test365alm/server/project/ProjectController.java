package com.test365alm.server.project;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Minimal HTTP surface for tenant, project and project membership administration. */
@RestController
@RequestMapping("/api/v1")
public class ProjectController {
    private final ProjectService service;
    private final ProjectAccessResolver actors;

    public ProjectController(ProjectService service, ProjectAccessResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/tenants")
    public List<ProjectService.TenantView> listTenants(Authentication authentication) {
        return service.listTenants(actors.requirePrincipal(authentication));
    }

    @GetMapping("/domains")
    public List<ProjectService.DomainView> listDomains(Authentication authentication, @RequestParam UUID tenantId) {
        return service.listDomains(actors.requirePrincipal(authentication), tenantId);
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectService.ProjectView> createProject(Authentication authentication,
            @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProject(actors.requirePrincipal(authentication),
                request.tenantId(), request.domainId(), request.code(), request.name()));
    }

    @GetMapping("/projects")
    public List<ProjectService.ProjectView> listProjects(Authentication authentication,
            @RequestParam UUID tenantId) {
        return service.listProjects(actors.requirePrincipal(authentication), tenantId);
    }

    @GetMapping("/projects/{projectId}")
    public ProjectService.ProjectView getProject(Authentication authentication, @PathVariable UUID projectId) {
        return service.getProject(actors.requirePrincipal(authentication), projectId);
    }

    @PatchMapping("/projects/{projectId}")
    public ProjectService.ProjectView updateProject(Authentication authentication, @PathVariable UUID projectId,
            @RequestBody ProjectUpdate request) {
        return service.updateProject(actors.requirePrincipal(authentication), projectId, request.name(), request.rowVersion());
    }

    @GetMapping("/projects/{projectId}/members")
    public List<ProjectService.MemberView> listMembers(Authentication authentication, @PathVariable UUID projectId) {
        return service.listMembers(actors.requirePrincipal(authentication), projectId);
    }

    @GetMapping("/projects/{projectId}/member-candidates")
    public List<ProjectService.MemberCandidateView> memberCandidates(Authentication authentication,
            @PathVariable UUID projectId) {
        return service.listCandidates(actors.requirePrincipal(authentication), projectId);
    }

    @PutMapping("/projects/{projectId}/members/{principalId}")
    public ProjectService.MemberView putMember(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID principalId, @RequestBody MemberRequest request) {
        return service.putMember(actors.requirePrincipal(authentication), projectId, principalId, request.roles(), request.rowVersion());
    }

    @DeleteMapping("/projects/{projectId}/members/{principalId}")
    public ProjectService.MemberView revokeMember(Authentication authentication, @PathVariable UUID projectId,
            @PathVariable UUID principalId) {
        return service.revokeMember(actors.requirePrincipal(authentication), projectId, principalId);
    }

    @GetMapping("/me/permissions")
    public ProjectService.PermissionView permissions(Authentication authentication, @RequestParam UUID projectId) {
        return service.permissions(actors.requirePrincipal(authentication), projectId);
    }

    public record ProjectRequest(UUID tenantId, UUID domainId, String code, String name) { }
    public record ProjectUpdate(String name, Long rowVersion) { }
    public record MemberRequest(Collection<String> roles, Long rowVersion) { }
}
