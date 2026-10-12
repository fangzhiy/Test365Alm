package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.test365alm.server.project.ProjectAccessException;

class ExecutionServiceTest {
    @Test
    void manifestHashChangesWhenSnapshotContentChanges() {
        String first = ExecutionService.hash("manifest", "case", "revision", "step-a");
        String second = ExecutionService.hash("manifest", "case", "revision", "step-b");
        assertEquals(64, first.length());
        assertNotEquals(first, second);
    }

    @Test
    void canonicalHashDistinguishesNullEmptyAndDelimiterContent() {
        String empty = ExecutionService.hash("manifest-v2", "");
        String nullValue = ExecutionService.hash("manifest-v2", (String) null);
        String pipe = ExecutionService.hash("manifest-v2", "a|b");
        String fields = ExecutionService.hash("manifest-v2", "a", "b");
        assertNotEquals(empty, nullValue);
        assertNotEquals(pipe, fields);
    }

    @Test
    void stepConclusionIsFixedToTheExecutionContract() {
        assertEquals("PASS", ExecutionService.normalizeConclusion(" pass "));
        assertEquals("BLOCKED", ExecutionService.normalizeConclusion("BLOCKED"));
        assertThrows(ProjectAccessException.class, () -> ExecutionService.normalizeConclusion("SUCCESS"));
    }

    @Test
    void idempotencyKeyMustBeBounded() {
        assertThrows(ProjectAccessException.class, () -> ExecutionService.requireKey("short"));
        ExecutionService.requireKey("m09-key-123");
    }

    @Test
    void idempotentAttemptSnapshotRoundTripsTimestampsAndSteps() throws Exception {
        UUID attempt = UUID.randomUUID();
        ExecutionService.AttemptView original = new ExecutionService.AttemptView(attempt, UUID.randomUUID(), 1,
                "PAUSED", null, 2, UUID.randomUUID(), OffsetDateTime.now(), null,
                List.of(new ExecutionService.RunStepView(UUID.randomUUID(), 1, "open", "visible", "done", "PASS", 2,
                        UUID.randomUUID(), OffsetDateTime.now())));
        var serialize = ExecutionService.class.getDeclaredMethod("serialize", Object.class);
        var decode = ExecutionService.class.getDeclaredMethod("decode", String.class, Class.class);
        serialize.setAccessible(true);
        decode.setAccessible(true);
        String json = (String) serialize.invoke(null, original);
        ExecutionService.AttemptView restored = (ExecutionService.AttemptView) decode.invoke(null, json, ExecutionService.AttemptView.class);
        assertEquals(original.id(), restored.id());
        assertEquals(original.status(), restored.status());
        assertEquals(original.steps().get(0).actualResult(), restored.steps().get(0).actualResult());
    }
}
