package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import com.test365alm.server.project.ProjectAccessResolver;

class TestCaseControllerTest {
    private final TestCaseService service = mock(TestCaseService.class);
    private final ProjectAccessResolver actors = mock(ProjectAccessResolver.class);
    private final TestCaseController controller = new TestCaseController(service, actors);
    private final Authentication authentication = mock(Authentication.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID testCase = UUID.randomUUID();

    @Test
    void detailReturnsStrongEntityTag() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.getAuthorized(actor, project, testCase)).thenReturn(view(7));

        var response = controller.get(authentication, project, testCase);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("\"7\"", response.getHeaders().getETag());
        assertEquals(testCase, response.getBody().id());
        assertEquals(1, response.getBody().currentRevision().steps().size());
    }

    private TestCaseService.TestCaseView view(long version) {
        UUID revision = UUID.randomUUID();
        TestCaseService.StepView step = new TestCaseService.StepView(UUID.randomUUID(), 1, "Open", "Opened");
        TestCaseService.RevisionView revisionView = new TestCaseService.RevisionView(revision, testCase, 1,
                "Login", "", "", OffsetDateTime.now(), actor, List.of(step));
        return new TestCaseService.TestCaseView(testCase, project, 1, "MANUAL", version,
                OffsetDateTime.now(), actor, revisionView);
    }
}

