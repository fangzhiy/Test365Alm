package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectAccessResolver;

class ExecutionGridControllerTest {
    private final ExecutionService service = mock(ExecutionService.class);
    private final ProjectAccessResolver actors = mock(ProjectAccessResolver.class);
    private final ExecutionController controller = new ExecutionController(service, actors);
    private final Authentication auth = mock(Authentication.class);
    private final UUID actor = UUID.randomUUID(), project = UUID.randomUUID(), instance = UUID.randomUUID();

    @Test void gridForwardsFiltersAndOpaqueCursor() {
        when(actors.requirePrincipal(auth)).thenReturn(actor);
        var page = new ExecutionService.GridPage(List.of(), "opaque-next");
        when(service.executionGrid(actor, project, instance, "opaque", 1, "PAUSED", actor)).thenReturn(page);
        assertEquals(page, controller.executionGrid(auth, project, instance, "opaque", 1, "PAUSED", actor));
    }

    @Test void assignmentRequiresExplicitNullableTargetAndStrongVersion() {
        when(actors.requirePrincipal(auth)).thenReturn(actor);
        controller.assignInstance(auth, project, instance, json("{\"assigneeId\":null}"), "assignment-key", "\"3\"");
        verify(service).assignInstance(actor, project, instance, null, 3, "assignment-key");
        assertThrows(ProjectAccessException.class, () -> controller.assignInstance(auth, project, instance,
                json("{}"), "assignment-key", "\"3\""));
        assertThrows(ProjectAccessException.class, () -> controller.assignInstance(auth, project, instance,
                json("{\"assigneeId\":null,\"expectedVersion\":2}"), "assignment-key", "\"3\""));
        assertThrows(ProjectAccessException.class, () -> controller.assignInstance(auth, project, instance,
                json("{\"assigneeId\":null,\"assigneeName\":\"forged\"}"), "assignment-key", "\"3\""));
        assertThrows(ProjectAccessException.class, () -> controller.assignInstance(auth, project, instance,
                json("{\"assigneeId\":null}"), "assignment-key", "W/\"3\""));
    }

    @Test void gridRejectsInventedStatusAndVersion() {
        assertEquals("NOT_RUN", ExecutionService.gridStatus("NOT_RUN"));
        assertThrows(ProjectAccessException.class, () -> ExecutionService.gridStatus("PASS"));
        assertThrows(ProjectAccessException.class, () -> controller.assignInstance(auth, project, instance,
                json("{\"assigneeId\":null}"), "assignment-key", "\"0\""));
    }

    private static tools.jackson.databind.JsonNode json(String value) {
        return new tools.jackson.databind.json.JsonMapper().readTree(value);
    }
}
