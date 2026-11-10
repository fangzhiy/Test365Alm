package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import com.github.dockerjava.api.command.CreateContainerCmd;
import org.junit.jupiter.api.Test;

class LinuxRunLabelsTest {
    @Test void absentProvenanceLeavesOrdinaryRunsUntouched() {
        var cmd=mock(CreateContainerCmd.class);
        assertSame(cmd,LinuxRunLabels.label(cmd,null,null));
        verifyNoInteractions(cmd);
    }
    @Test void labelsPreserveExistingOwnershipAndRyukSession() {
        var cmd=mock(CreateContainerCmd.class,RETURNS_SELF);
        when(cmd.getLabels()).thenReturn(Map.of("org.testcontainers.sessionId","session","com.test365alm.r11.run","asset"));
        LinuxRunLabels.label(cmd,"a".repeat(32),"b".repeat(40));
        verify(cmd).withLabels(Map.of("org.testcontainers.sessionId","session","com.test365alm.r11.run","asset",
                "com.test365alm.linux.run","a".repeat(32),"com.test365alm.linux.source","b".repeat(40)));
    }
    @Test void invalidOrConflictingProvenanceRefusesCreation() {
        var cmd=mock(CreateContainerCmd.class);
        assertThrows(IllegalArgumentException.class,()->LinuxRunLabels.label(cmd,"bad","b".repeat(40)));
        when(cmd.getLabels()).thenReturn(Map.of("com.test365alm.linux.run","c".repeat(32)));
        assertThrows(IllegalArgumentException.class,()->LinuxRunLabels.label(cmd,"a".repeat(32),"b".repeat(40)));
        verify(cmd,never()).withLabels(anyMap());
    }
}
