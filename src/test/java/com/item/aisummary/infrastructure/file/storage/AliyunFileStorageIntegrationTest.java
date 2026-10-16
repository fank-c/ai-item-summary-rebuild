package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实路由、文件服务和 H2 Mapper，验证 OSS 项目目录入库及历史 Key 透传；不访问云端。 */
@SpringBootTest(properties = {
    "app.file.storage.aliyun.enabled=true",
    "app.file.storage.aliyun.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
    "app.file.storage.aliyun.bucket=test-bucket",
    "app.file.storage.aliyun.access-key-id=test-access",
    "app.file.storage.aliyun.access-key-secret=test-secret",
    "app.file.storage.aliyun.key-prefix=/item/custom-project/",
    "app.file.storage.key-prefix=documents/source"
})
@AutoConfigureMockMvc
class AliyunFileStorageIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileContentService contentService;
    @MockitoBean(name = "aliyun") ObjectStorageClient storage;

    @BeforeEach
    void resetFiles() {
        jdbc.update("DELETE FROM file_asset");
    }

    /** 上传、入库、签名、读取和删除共用同一个完整 Key，项目目录只拼接一次。 */
    @Test
    void uploadPersistsProjectPrefixAndLaterOperationsUseThatExactKey() throws Exception {
        String id = upload();
        String key = jdbc.queryForObject("SELECT storage FROM file_asset WHERE id=?", String.class, id);
        assertThat(key).matches("item/custom-project/documents/source/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.txt");
        assertThat(jdbc.queryForObject("SELECT storage_type FROM file_asset WHERE id=?", String.class, id))
            .isEqualTo("aliyun");
        verify(storage).putObject(eq("test-bucket"), eq(key), eq("text/plain"), eq(3L),
            eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), any(InputStream.class));
        assertRecordedKeyIsUsed(id, key);
    }

    /** 配置项目目录后，旧文件仍按原 Key 下载、读取和删除，不自动搬迁或补前缀。 */
    @Test
    void historicalAliyunKeyIsPassedThroughWithoutAddingCurrentPrefix() throws Exception {
        String id = upload();
        String historicalKey = "files/2020/01/01/" + "文".repeat(100) + ".txt";
        jdbc.update("UPDATE file_asset SET storage=? WHERE id=?", historicalKey, id);
        assertRecordedKeyIsUsed(id, historicalKey);
    }

    /** 以真实 HTTP 入口上传最小样例，外部对象存储由 Mock 接管。 */
    private String upload() throws Exception {
        var result = mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", "a.TXT", "text/plain", new byte[] {97, 98, 99}))
                .param("storageType", "aliyun"))
            .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 验证后续三个入口只消费已存 Key，并检查删除落库。 */
    private void assertRecordedKeyIsUsed(String id, String key) throws Exception {
        when(storage.presignGetObject("test-bucket", key, Duration.ofMinutes(15)))
            .thenReturn("https://example.test/signed");
        mvc.perform(get("/api/files/{id}/download-url", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://example.test/signed"));
        verify(storage).presignGetObject("test-bucket", key, Duration.ofMinutes(15));

        when(storage.openObject("test-bucket", key))
            .thenReturn(new ByteArrayInputStream(new byte[] {97, 98, 99}));
        try (var content = contentService.openForProcessing("mock-user", id)) {
            assertThat(content.inputStream().readAllBytes()).containsExactly(97, 98, 99);
        }
        verify(storage).openObject("test-bucket", key);

        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNoContent());
        verify(storage).deleteObject("test-bucket", key);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM file_asset WHERE id=?", Object.class, id)).isNotNull();
    }
}
