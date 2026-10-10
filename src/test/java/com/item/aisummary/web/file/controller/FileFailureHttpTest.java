package com.item.aisummary.web.file.controller;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.domain.file.model.FileAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 明确不承诺补偿；外部对象变化后数据库失败仍返回安全的 500。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FileStorageTestConfiguration.class)
class FileFailureHttpTest {
    @Autowired MockMvc mvc;
    @Autowired FileStorageTestConfiguration.MemoryStorage storage;
    @MockitoBean FileRepository repository;
    @BeforeEach void reset() { storage.reset(); }
    @Test void databaseSaveFailureLeavesRemoteObjectAndReturnsSafeError() throws Exception {
        when(repository.insert(any())).thenThrow(new IllegalStateException("secret SQL"));
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", null, new byte[] {97}))
                .param("storageType", "minio"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("服务器内部错误"));
        assertThat(storage.objects).hasSize(1);
    }
    @Test void databaseDeleteFailureLeavesRecordAccessibleButObjectMissing() throws Exception {
        var now = Instant.now();
        var file = new FileAsset("id", "a.txt", "text/plain", 1, "minio", "key", "sha", 1, null,
            "completed", "mock-user", "mock-user", now, now);
        when(repository.findById("id")).thenReturn(Optional.of(file));
        when(repository.softDelete(eq("id"), eq("mock-user"), any())).thenReturn(0);
        storage.objects.put("key", new byte[] {97});
        mvc.perform(delete("/api/files/id")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(storage.objects).isEmpty();
        mvc.perform(get("/api/files/id/download-url")).andExpect(status().isOk());
    }
}
