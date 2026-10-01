package com.test365alm.server.requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import com.test365alm.server.project.ProjectAccessResolver;

class RequirementControllerTest {
    private final RequirementService service = mock(RequirementService.class);
    private final ProjectAccessResolver actors = mock(ProjectAccessResolver.class);
    private final RequirementController controller = new RequirementController(service, actors);
    private final Authentication authentication = mock(Authentication.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID requirement = UUID.randomUUID();

    @Test
    void createReturnsCreatedAndEntityEtag() {
        RequirementService.RequirementView view = view(1);
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.create(eq(actor), eq(project), any(), eq("create-key-1"))).thenReturn(view);

        var response = controller.create(authentication, project,
                new RequirementController.CreateRequest("Title", "Body", "HIGH"), "create-key-1");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals(requirement, response.getBody().id());
    }

    @Test
    void updateReturnsCurrentEtagForOptimisticVersion() {
        RequirementService.RequirementView view = view(2);
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.update(eq(actor), eq(project), eq(requirement), any(), eq("\"1\""), eq("update-key-1")))
                .thenReturn(view);

        var response = controller.update(authentication, project, requirement,
                new RequirementController.PatchRequest("Changed", null, null), "\"1\"", "update-key-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("\"2\"", response.getHeaders().getETag());
        assertEquals(2, response.getBody().rowVersion());
    }

    private RequirementService.RequirementView view(long version) {
        UUID revision = UUID.randomUUID();
        return new RequirementService.RequirementView(requirement, project, 1, version,
                OffsetDateTime.now(), actor, revision, version, "Title", "Body", "MEDIUM");
    }
}
