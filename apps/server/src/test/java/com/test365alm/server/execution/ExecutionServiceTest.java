package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
