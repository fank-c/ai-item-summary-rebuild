package com.item.aisummary.web.file.controller;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用生产 multipart 头部配置和真实容器；测试 YAML 覆盖外部后端且禁止导入本地 .env。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.location=file:./src/main/resources/application.yml,classpath:application.yml",
    "spring.config.import=",
    "spring.servlet.multipart.max-file-size=4B", "spring.servlet.multipart.max-request-size=8KB",
    "app.file.upload.max-size=4B"})
@Import(FileStorageTestConfiguration.class)
class FileLongFilenameMultipartHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorageTestConfiguration.MemoryStorage storage;

    @BeforeEach void reset() { jdbc.update("DELETE FROM file_asset"); storage.reset(); }

    @Test void maximumAsciiChineseAndSupplementaryNamesReachApplicationWithoutTruncation() throws Exception {
        for (String name : new String[] {"a".repeat(251) + ".TXT", "文".repeat(251) + ".TXT",
                "𠀀".repeat(125) + "a.TXT"}) {
            var response = send(name, "abc", "");
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat((String) JsonPath.read(response.body(), "$.originName")).isEqualTo(name);
            String id = JsonPath.read(response.body(), "$.id");
            String key = jdbc.queryForObject("SELECT storage FROM file_asset WHERE id=?", String.class, id);
            assertThat(key).matches("files/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.txt");
            assertThat(storage.objects.get(key)).containsExactly(97, 98, 99);
        }
    }

    @Test void overlongNameReachesBusinessValidationInsteadOfContainerSizeError() throws Exception {
        var response = send("文".repeat(252) + ".txt", "x", "");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FILE_VALIDATION_ERROR");
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class)).isZero();
    }

    @Test void partHeaderAndFileSizeLimitsStillRejectOversizeRequests() throws Exception {
        for (var response : java.util.List.of(send("a.txt", "x", "x".repeat(4096)), send("a.txt", "12345", ""))) {
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FILE_SIZE_LIMIT_EXCEEDED");
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        }
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class)).isZero();
    }

    private HttpResponse<String> send(String name, String content, String headerPadding) throws Exception {
        String body = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
            + "\"\r\nContent-Type: text/plain\r\n"
            + (headerPadding.isEmpty() ? "" : "X-Test: " + headerPadding + "\r\n")
            + "\r\n" + content + "\r\n--boundary--\r\n";
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/files?storageType=minio"))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
