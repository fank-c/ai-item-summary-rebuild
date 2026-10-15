package com.item.aisummary.web.file.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 长中文前缀仅影响本上下文，非法 Key 应在存储和数据库写入前返回 400。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FileStorageTestConfiguration.class)
class FileKeyValidationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorageTestConfiguration.MemoryStorage storage;

    @DynamicPropertySource
    static void longPrefix(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage.key-prefix", () -> "中".repeat(150));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM file_asset");
        storage.reset();
    }

    @Test
    void invalidKeyReturns400BeforeCreatingRemoteOrDatabaseContent() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class);
        int remoteBefore = storage.objects.size();
        // 若错误 Key 进入上传，将得到 502；这里只允许业务校验的 400。
        storage.failPut = true;
        var file = new MockMultipartFile("file", "文".repeat(251) + ".txt", "text/plain", new byte[] {97});
        mvc.perform(multipart("/api/files").file(file).param("storageType", "minio"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FILE_VALIDATION_ERROR"))
            .andExpect(jsonPath("$.detail").value("对象 Key 的单个路径段不能超过 255 个 UTF-8 字节"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class)).isEqualTo(before);
        assertThat(storage.objects).hasSize(remoteBefore);
    }
}
