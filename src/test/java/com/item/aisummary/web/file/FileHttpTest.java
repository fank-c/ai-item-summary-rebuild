package com.item.aisummary.web.file;

import com.item.aisummary.application.file.FileContentService;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.domain.file.FileNotFoundException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 Mapper 的 HTTP 合同：公开字段、归属、状态、摘要、删除顺序和故障副作用。 */
@SpringBootTest(properties = "app.file.upload.max-size=4B")
@AutoConfigureMockMvc
@Import(FileStorageTestConfiguration.class)
class FileHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FileContentService contentService;
    @Autowired FileStorageTestConfiguration.MemoryStorage storage;

    @BeforeEach void reset() { jdbc.update("DELETE FROM file_asset"); storage.reset(); }

    @Test void uploadsActualContentWithOnlyPublicFields() throws Exception {
        var result = mvc.perform(multipart("/api/files").file(file("../a.TXT", "Text/Plain; charset=UTF-8", "abc"))
                .param("storageType", "minio").param("createBy", "attacker").param("bucket", "attacker"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.size").value(3))
            .andExpect(jsonPath("$.mime").value("text/plain"))
            .andExpect(jsonPath("$.uploadStatus").value("completed")).andReturn();
        Map<?, ?> response = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(response.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
            "id", "originName", "mime", "size", "uploadStatus", "createAt", "updateAt");
        String id = response.get("id").toString();
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/files/" + id);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?", id);
        assertThat(row.get("create_by")).isEqualTo("mock-user");
        assertThat(row.get("update_by")).isEqualTo("mock-user");
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("upload_expires_at")).isNull();
        assertThat(row.get("storage_type")).isEqualTo("minio");
        assertThat(row.get("sha256")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String key = row.get("storage").toString();
        assertThat(key).matches("files/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}-a.TXT").doesNotContain("..");
        assertThat(storage.objects.get(key)).containsExactly(97, 98, 99);
        assertThat(response.get("createAt")).isEqualTo(response.get("updateAt"));
        mvc.perform(get("/api/files/{id}", id)).andExpect(status().isMethodNotAllowed());
    }

    @Test void sameNameUsesDifferentKeysAndMissingMimeUsesBinary() throws Exception {
        String first = upload("a.md", null, "abc");
        String second = upload("a.md", null, "abc");
        assertThat(key(first)).isNotEqualTo(key(second));
        assertThat(jdbc.queryForObject("SELECT mime FROM file_asset WHERE id=?", String.class, first))
            .isEqualTo("application/octet-stream");
    }

    @Test void downloadUsesRecordedKeyAndConfiguredLifetimeAndDeleteHidesRecord() throws Exception {
        String id = upload("a.txt", "text/plain", "abc");
        String key = key(id);
        Instant before = Instant.now();
        var result = mvc.perform(get("/api/files/{id}/download-url", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fileId").value(id)).andReturn();
        String expires = JsonPath.read(result.getResponse().getContentAsString(), "$.expiresAt");
        String url = JsonPath.read(result.getResponse().getContentAsString(), "$.url");
        assertThat(url).contains("test-bucket/" + key);
        assertThat(storage.expiration).isEqualTo(Duration.ofMinutes(15));
        assertThat(Instant.parse(expires)).isBetween(before.plusSeconds(899), Instant.now().plusSeconds(901));
        storage.beforeDelete = () -> assertThat(jdbc.queryForObject(
            "SELECT deleted_at FROM file_asset WHERE id=?", Object.class, id)).isNull();
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(storage.objects).doesNotContainKey(key);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?", id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("update_by")).isEqualTo("mock-user");
        mvc.perform(get("/api/files/{id}/download-url", id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> contentService.openForProcessing("mock-user", id)).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"create_by='other'", "status=0", "upload_status='pending'", "deleted_at=CURRENT_TIMESTAMP(6)"})
    void inaccessibleStateIsHiddenFromAllEntrypoints(String update) throws Exception {
        String id = upload("a.txt", "text/plain", "abc");
        jdbc.update("UPDATE file_asset SET " + update + " WHERE id=?", id);
        mvc.perform(get("/api/files/{id}/download-url", id)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> contentService.requireOwnedProcessable("mock-user", id)).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> contentService.openForProcessing("mock-user", id)).isInstanceOf(FileNotFoundException.class);
        assertThat(storage.opened).isZero();
        assertThat(storage.objects).hasSize(1);
    }

    @Test void internalReaderCarriesMetadataAndClosesStream() throws Exception {
        String id = upload("a.txt", "text/plain", "abc");
        assertThat(contentService.requireOwnedProcessable("mock-user", id).originName()).isEqualTo("a.txt");
        try (var resource = contentService.openForProcessing("mock-user", id)) {
            assertThat(resource.fileId()).isEqualTo(id);
            assertThat(resource.size()).isEqualTo(3);
            assertThat(resource.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(resource.inputStream().readAllBytes()).containsExactly(97, 98, 99);
        }
        assertThat(storage.closed).isEqualTo(1);
        assertThatThrownBy(() -> contentService.openForProcessing("other", id)).isInstanceOf(FileNotFoundException.class);
        storage.failRead = true;
        assertThatThrownBy(() -> contentService.openForProcessing("mock-user", id)).isInstanceOf(ObjectStorageException.class);
    }

    @Test void rejectsEmptyOversizeAndDisallowedFilesWithoutSideEffects() throws Exception {
        for (var file : new MockMultipartFile[] {file("a.txt", null, ""), file("a.txt", null, "12345"),
                file("a.exe", null, "abc"), file("", null, "abc")}) {
            mvc.perform(multipart("/api/files").file(file).param("storageType", "minio"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_VALIDATION_ERROR"));
        }
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class)).isZero();
    }

    @Test void missingFileOrStorageTypeAndDisabledTypeAreBadRequests() throws Exception {
        mvc.perform(multipart("/api/files").param("storageType", "minio")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/files").file(file("a.txt", null, "abc"))).andExpect(status().isBadRequest());
        for (String type : new String[] {"aliyun", "unknown", ""}) {
            mvc.perform(multipart("/api/files").file(file("a.txt", null, "abc")).param("storageType", type))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_VALIDATION_ERROR"));
        }
    }

    @Test void storageFailuresNeverPretendUploadOrDeletionSucceeded() throws Exception {
        storage.failPut = true;
        mvc.perform(multipart("/api/files").file(file("a.txt", null, "abc")).param("storageType", "minio"))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OBJECT_STORAGE_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value("对象存储暂时不可用"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class)).isZero();
        storage.failPut = false;
        String id = upload("a.txt", null, "abc");
        storage.failDelete = true;
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isBadGateway());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM file_asset WHERE id=?", Object.class, id)).isNull();
        assertThat(storage.objects).hasSize(1);
        storage.failSign = true;
        mvc.perform(get("/api/files/{id}/download-url", id)).andExpect(status().isBadGateway());
    }

    private String upload(String name, String mime, String text) throws Exception {
        return JsonPath.read(mvc.perform(multipart("/api/files").file(file(name, mime, text)).param("storageType", "minio"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }
    private String key(String id) { return jdbc.queryForObject("SELECT storage FROM file_asset WHERE id=?", String.class, id); }
    private static MockMultipartFile file(String name, String mime, String text) {
        return new MockMultipartFile("file", name, mime, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
