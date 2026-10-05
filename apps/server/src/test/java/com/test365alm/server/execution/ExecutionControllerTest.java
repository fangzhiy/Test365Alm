package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectAccessResolver;

class ExecutionControllerTest {
    private final ExecutionService service = mock(ExecutionService.class);
    private final ProjectAccessResolver actors = mock(ProjectAccessResolver.class);
    private final ExecutionController controller = new ExecutionController(service, actors);
    private final Authentication authentication = mock(Authentication.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final UUID step = UUID.randomUUID();

    @Test
    void createRunAcceptsTheControlledFrontendAliases() throws Exception {
        UUID instance = UUID.randomUUID();
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.createRun(actor, project, instance, "run-key-001")).thenReturn(detail(instance));
        var response = controller.createRun(authentication, project,
                json("{\"instanceId\":\"" + instance + "\",\"mode\":\"MANUAL\"}"), "run-key-001");
        assertEquals(201, response.getStatusCode().value());
        verify(service).createRun(actor, project, instance, "run-key-001");
    }

    @Test
    void saveStepAcceptsActualAndOutcomeAliasesWithStrongVersion() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.saveStep(eq(actor), eq(project), eq(run), eq(attempt), eq(step), eq("done"), eq("PASS"), eq(2L), eq("step-key-001")))
                .thenReturn(new ExecutionService.AttemptView(attempt, run, 1, "RUNNING", null, 3, actor, null, null, List.of()));
        controller.saveStep(authentication, project, run, attempt, step,
                json("{\"actual\":\"done\",\"outcome\":\"PASS\"}"), "step-key-001", "\"2\"");
        verify(service).saveStep(actor, project, run, attempt, step, "done", "PASS", 2L, "step-key-001");
    }

    @Test
    void unknownFieldsAndUnsupportedRunModeAreRejected() {
        assertThrows(ProjectAccessException.class, () -> controller.createSet(authentication, project,
                json("{\"name\":\"Smoke\",\"unexpected\":true}"), "set-key-001"));
        assertThrows(ProjectAccessException.class, () -> controller.createRun(authentication, project,
                json("{\"testInstanceId\":\"" + UUID.randomUUID() + "\",\"mode\":\"AUTOMATED\"}"), "run-key-002"));
    }

    @Test
    void expectedVersionMustMatchStrongIfMatchAndRejectOverflow() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        assertThrows(ProjectAccessException.class, () -> controller.saveStep(authentication, project, run, attempt, step,
                json("{\"actualResult\":\"done\",\"conclusion\":\"PASS\",\"expectedVersion\":1}"),
                "step-key-002", "\"2\""));
        assertThrows(ProjectAccessException.class, () -> controller.saveStep(authentication, project, run, attempt, step,
                json("{\"actualResult\":\"done\",\"conclusion\":\"PASS\"}"),
                "step-key-003", "\"999999999999999999999999999999\""));
    }

    private ExecutionService.RunDetail detail(UUID instance) {
        ExecutionService.RunView runView = new ExecutionService.RunView(run, project, instance, UUID.randomUUID(), "RUNNING", 1, null);
        return new ExecutionService.RunDetail(runView, null, null, List.of());
    }

    private static tools.jackson.databind.JsonNode json(String value) {
        try { return new tools.jackson.databind.json.JsonMapper().readTree(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
