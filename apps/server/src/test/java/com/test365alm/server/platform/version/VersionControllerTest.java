package com.test365alm.server.platform.version;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionControllerTest {
    @Test
    void returnsBuildMetadataWithoutInventingACommit() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new VersionController("Test365Alm", "0.0.1-SNAPSHOT", "unknown"))
                .build();

        mockMvc.perform(get("/api/v1/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Test365Alm"))
                .andExpect(jsonPath("$.version").value("0.0.1-SNAPSHOT"))
                .andExpect(jsonPath("$.commit").value("unknown"));
    }
}
