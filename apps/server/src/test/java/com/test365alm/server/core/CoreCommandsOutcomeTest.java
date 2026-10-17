package com.test365alm.server.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.test365alm.server.project.ProjectAccessException;

class CoreCommandsOutcomeTest {
    record Success(String id,long rowVersion) { }

    @Test void existingSuccessfulReplayKeepsItsOriginalJsonShape() {
        var original=new Success("old-success",7);
        String json=CoreCommands.JSON.writeValueAsString(original);
        assertEquals(original,CoreCommands.decodeReplay(json,Success.class));
        assertFalse(CoreCommands.JSON.readTree(json).has("_coreOutcome"));
    }

    @Test void terminalErrorReplayPreservesOnlySafeStatusCodeAndMessage() {
        var error=new ProjectAccessException("FILE_INFECTED",HttpStatus.UNPROCESSABLE_ENTITY,"Malware detected; upload rejected");
        var frozen=CoreCommands.freezeTerminalError(error);
        String json=CoreCommands.JSON.writeValueAsString(frozen);
        assertEquals(4,CoreCommands.JSON.readTree(json).size());
        assertFalse(json.contains("stackTrace"));assertFalse(json.contains("cause"));
        var replay=assertThrows(ProjectAccessException.class,()->CoreCommands.decodeReplay(json,Success.class));
        assertEquals(error.status().value(),replay.status().value());assertEquals(error.code(),replay.code());assertEquals(error.getMessage(),replay.getMessage());
        assertThrows(IllegalArgumentException.class,()->CoreCommands.freezeTerminalError(new ProjectAccessException("SCAN_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,"retry")),"a transient 503 cannot be persisted as a terminal command outcome");
    }

    @Test void malformedTerminalEnvelopeCannotMasqueradeAsSuccessOrUnsafeError() {
        for(String json:new String[]{
            "{\"_coreOutcome\":\"UNKNOWN\",\"status\":422,\"code\":\"FILE_INFECTED\",\"message\":\"safe\"}",
            "{\"_coreOutcome\":\"TERMINAL_ERROR_V1\",\"status\":\"422\",\"code\":\"FILE_INFECTED\",\"message\":\"safe\"}",
            "{\"_coreOutcome\":\"TERMINAL_ERROR_V1\",\"status\":503,\"code\":\"SCAN_UNAVAILABLE\",\"message\":\"retry\"}",
            "{\"_coreOutcome\":\"TERMINAL_ERROR_V1\",\"status\":422,\"code\":\"bad code\",\"message\":\"safe\"}",
            "{\"_coreOutcome\":\"TERMINAL_ERROR_V1\",\"status\":422,\"code\":\"FILE_INFECTED\",\"message\":null}",
            "{\"_coreOutcome\":\"TERMINAL_ERROR_V1\",\"status\":422,\"code\":\"FILE_INFECTED\",\"message\":\"safe\",\"stackTrace\":\"unexpected\"}"})
            assertThrows(IllegalStateException.class,()->CoreCommands.decodeReplay(json,Success.class));
    }
}
