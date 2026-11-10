package com.test365alm.server.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.springframework.test.util.ReflectionTestUtils;

class AssetTestInfrastructureTest {
    private final GenericContainer<?> container=mock(GenericContainer.class);
    private final DockerClient docker=mock(DockerClient.class,RETURNS_DEEP_STUBS);

    AssetTestInfrastructureTest() {
        when(container.getContainerId()).thenReturn("owned-scanner");
        when(container.getDockerClient()).thenReturn(docker);
    }

    @AfterEach void clearFaultState() {
        ReflectionTestUtils.setField(AssetTestInfrastructure.class,"pausedScanner",null);
    }

    @Test void failedPostPauseInspectionRecoversOriginalProcessAndEndpointBeforeRethrowing() {
        var running=state(false,12345,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        var paused=state(true,null,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        var failure=new IllegalStateException("inspect failed after pause");
        when(docker.inspectContainerCmd("owned-scanner").exec())
                .thenReturn(running).thenThrow(failure).thenReturn(paused,running);
        assertSame(failure,assertThrows(IllegalStateException.class,()->AssetTestInfrastructure.pauseScanner(container)));
        verify(docker.pauseContainerCmd("owned-scanner")).exec();
        verify(docker.unpauseContainerCmd("owned-scanner")).exec();
        assertNull(ReflectionTestUtils.getField(AssetTestInfrastructure.class,"pausedScanner"));
    }

    @Test void unknownOwnershipNeverPauses() {
        var foreign=state(false,12345,0,"owned-scanner","foreign-run");
        when(docker.inspectContainerCmd("owned-scanner").exec())
                .thenReturn(foreign);
        assertThrows(IllegalStateException.class,()->AssetTestInfrastructure.pauseScanner(container));
        verify(docker,never()).pauseContainerCmd(anyString());
        verify(docker,never()).unpauseContainerCmd(anyString());
    }

    @Test void replacedProcessIsNeverUnpaused() {
        rejectPaused(state(true,null,0,"replacement",AssetTestInfrastructure.RUN_ID));
    }

    @Test void changedConfiguredBindingsAreNeverUnpaused() {
        rejectPaused(state(true,null,9999,"owned-scanner",AssetTestInfrastructure.RUN_ID));
    }

    @Test void restartedProcessIsNeverUnpaused() {
        var restarted=state(true,null,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        when(restarted.getState().getStartedAt()).thenReturn("2026-11-11T00:01:00Z");
        rejectPaused(restarted);
    }

    @Test void lostOwnershipAfterPauseIsNeverUnpaused() {
        rejectPaused(state(true,null,0,"owned-scanner","foreign-run"));
    }

    @Test void changedPublishedEndpointAfterResumeStillFails() {
        var running=state(false,12345,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        var paused=state(true,null,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        var changed=state(false,54321,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        when(docker.inspectContainerCmd("owned-scanner").exec())
                .thenReturn(running,paused,paused,changed);
        AssetTestInfrastructure.pauseScanner(container);
        assertThrows(IllegalStateException.class,()->AssetTestInfrastructure.resumeScanner(container));
        verify(docker.unpauseContainerCmd("owned-scanner")).exec();
    }

    private void rejectPaused(InspectContainerResponse unexpected) {
        var running=state(false,12345,0,"owned-scanner",AssetTestInfrastructure.RUN_ID);
        when(docker.inspectContainerCmd("owned-scanner").exec()).thenReturn(running,unexpected);
        assertThrows(IllegalStateException.class,()->AssetTestInfrastructure.pauseScanner(container));
        verify(docker,never()).unpauseContainerCmd(anyString());
    }

    private InspectContainerResponse state(boolean paused,Integer published,int configured,String id,String run) {
        var actual=mock(InspectContainerResponse.class,RETURNS_DEEP_STUBS);
        when(actual.getId()).thenReturn(id);
        when(actual.getConfig().getLabels()).thenReturn(Map.of("com.test365alm.r11.run",run));
        when(actual.getState().getRunning()).thenReturn(true);
        when(actual.getState().getPaused()).thenReturn(paused);
        when(actual.getState().getStartedAt()).thenReturn("2026-11-11T00:00:00Z");
        var ports=new Ports();
        if(published!=null)ports.bind(ExposedPort.tcp(3310),Ports.Binding.bindIpAndPort("127.0.0.1",published));
        when(actual.getNetworkSettings().getPorts()).thenReturn(ports);
        var bindings=new Ports();
        bindings.bind(ExposedPort.tcp(3310),Ports.Binding.bindIpAndPort("127.0.0.1",configured));
        when(actual.getHostConfig().getPortBindings()).thenReturn(bindings);
        return actual;
    }
}
