package com.item.aisummary.web.file.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.auth.mock.enabled=false")
@AutoConfigureMockMvc
@Import(FileStorageTestConfiguration.class)
class FileAuthenticationHttpTest {
    @Autowired MockMvc mvc;
    @Test void allFileEndpointsRequireServerIdentity() throws Exception {
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", null, new byte[] {97}))
                .param("storageType", "minio").param("actorUserId", "owner").header("Authorization", "Bearer fake"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/files/id/download-url")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/files/id")).andExpect(status().isUnauthorized());
    }
}
