package com.test365alm.server.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformHealthControllerTest {
    @Test
    void liveRemainsUpWhenDatabaseIsDown() throws Exception {
        MockMvc mockMvc = mockMvcWith(() -> ReadinessResult.down());

        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readyReturns503WhenDatabaseIsDown() throws Exception {
        MockMvc mockMvc = mockMvcWith(() -> ReadinessResult.down());

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"))
                .andExpect(jsonPath("$.migration").value("UNKNOWN"));
    }

    @Test
    void readyReturns503WhenDatabaseIsConnectedButRequiredStructureIsMissing() throws Exception {
        MockMvc mockMvc = mockMvcWith(ReadinessResult::migrationNotApplied);

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.migration").value("NOT_APPLIED"));
    }

    @Test
    void readyRecoversWithoutRestartingController() throws Exception {
        AtomicReference<ReadinessResult> current = new AtomicReference<>(ReadinessResult.down());
        MockMvc mockMvc = mockMvcWith(current::get);

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable());

        current.set(ReadinessResult.up());
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.migration").value("APPLIED"));
    }

    private MockMvc mockMvcWith(ReadinessChecker checker) {
        return MockMvcBuilders.standaloneSetup(new PlatformHealthController(checker)).build();
    }
}
