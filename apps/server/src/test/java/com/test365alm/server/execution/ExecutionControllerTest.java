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

    @Test
    void transitionBodiesRejectUnknownFieldsAndNonObjects() {
        assertThrows(ProjectAccessException.class, () -> controller.pause(authentication, project, run, attempt,
                json("{\"expectedVersion\":1,\"unexpected\":true}"), "pause-key-001", "\"1\""));
        assertThrows(ProjectAccessException.class, () -> controller.resume(authentication, project, run, attempt,
                json("null"), "resume-key-001", "\"1\""));
        assertThrows(ProjectAccessException.class, () -> controller.finish(authentication, project, run, attempt,
                json("{\"expectedVersion\":1,\"reason\":\"not accepted\"}"), "finish-key-001", "\"1\""));
        assertThrows(ProjectAccessException.class, () -> controller.rerun(authentication, project, run,
                json("{\"reason\":\"not accepted\"}"), "rerun-key-001"));
    }

    @Test
    void transitionBodiesAcceptOnlyTheVersionContract() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        when(service.pause(eq(actor), eq(project), eq(run), eq(attempt), eq(1L), eq("pause-key-002")))
                .thenReturn(attemptView());
        controller.pause(authentication, project, run, attempt, json("{\"expectedVersion\":1}"),
                "pause-key-002", "\"1\"");
        verify(service).pause(actor, project, run, attempt, 1L, "pause-key-002");
    }

    @Test
    void pagedReadEndpointsAreAdditiveAndUseOpaqueCursor() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        ExecutionService.TestSetPage sets = new ExecutionService.TestSetPage(List.of(), "next-set");
        ExecutionService.InstancePage instances = new ExecutionService.InstancePage(List.of(), "next-instance");
        ExecutionService.RunPage runs = new ExecutionService.RunPage(List.of(), "next-run");
        ExecutionService.AttemptPage attempts = new ExecutionService.AttemptPage(List.of(), "next-attempt");
        when(service.pageSets(actor, project, "cursor", 1)).thenReturn(sets);
        when(service.pageInstances(actor, project, project, "cursor", 1)).thenReturn(instances);
        when(service.pageRuns(actor, project, "cursor", 1)).thenReturn(runs);
        when(service.pageAttempts(actor, project, run, "cursor", 1)).thenReturn(attempts);
        assertEquals("next-set", controller.pageSets(authentication, project, "cursor", 1).nextCursor());
        assertEquals("next-instance", controller.pageInstances(authentication, project, project, "cursor", 1).nextCursor());
        assertEquals("next-run", controller.pageRuns(authentication, project, "cursor", 1).nextCursor());
        assertEquals("next-attempt", controller.pageAttempts(authentication, project, run, "cursor", 1).nextCursor());
        verify(service).pageSets(actor, project, "cursor", 1);
        verify(service).pageInstances(actor, project, project, "cursor", 1);
        verify(service).pageRuns(actor, project, "cursor", 1);
        verify(service).pageAttempts(actor, project, run, "cursor", 1);
    }

    @Test
    void runSummaryIsReadThroughTheProjectScopedService() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        ExecutionService.RunSummary summary = new ExecutionService.RunSummary(3, 1, 1, 1, 0, 1);
        when(service.summary(actor, project)).thenReturn(summary);
        assertEquals(summary, controller.summary(authentication, project));
        verify(service).summary(actor, project);
    }

    @Test
    void pagedRunsAndSummaryForwardAnOptionalTestSetFilter() {
        when(actors.requirePrincipal(authentication)).thenReturn(actor);
        UUID set = UUID.randomUUID();
        ExecutionService.RunPage runs = new ExecutionService.RunPage(List.of(), null);
        ExecutionService.RunSummary summary = new ExecutionService.RunSummary(1, 0, 0, 1, 0, 0);
        when(service.pageRuns(actor, project, set, "cursor", 1)).thenReturn(runs);
        when(service.summary(actor, project, set)).thenReturn(summary);
        assertEquals(runs, controller.pageRuns(authentication, project, set, "cursor", 1));
        assertEquals(summary, controller.summary(authentication, project, set));
        verify(service).pageRuns(actor, project, set, "cursor", 1);
        verify(service).summary(actor, project, set);
    }

    private ExecutionService.RunDetail detail(UUID instance) {
        ExecutionService.RunView runView = new ExecutionService.RunView(run, project, instance, UUID.randomUUID(), "RUNNING", 1, null);
        return new ExecutionService.RunDetail(runView, null, null, List.of());
    }

    private ExecutionService.AttemptView attemptView() {
        return new ExecutionService.AttemptView(attempt, run, 1, "PAUSED", null, 2, actor, null, null, List.of());
    }

    private static tools.jackson.databind.JsonNode json(String value) {
        try { return new tools.jackson.databind.json.JsonMapper().readTree(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
