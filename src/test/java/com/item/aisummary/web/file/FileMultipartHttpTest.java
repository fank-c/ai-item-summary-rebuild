package com.item.aisummary.web.file;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.jayway.jsonpath.JsonPath;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实容器解析 multipart，抓住 MockMvc 不执行的请求大小限制。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.servlet.multipart.max-file-size=4B", "spring.servlet.multipart.max-request-size=600B",
    "app.file.upload.max-size=4B"})
@Import(FileStorageTestConfiguration.class)
class FileMultipartHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired FileStorageTestConfiguration.MemoryStorage storage;

    @org.junit.jupiter.api.BeforeEach void resetStorage() { storage.reset(); }

    @Test void realContainerRejectsFileAndTotalRequestLimitsWithProblemDetails() throws Exception {
        for (var response : java.util.List.of(send("12345", "a.txt"), send("abc", "a.txt", "x".repeat(700)))) {
            // 第二个请求的文件只有 3 字节，只因整个请求超过 600 字节而被拒绝。
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FILE_SIZE_LIMIT_EXCEEDED");
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
            assertThat(storage.objects).isEmpty();
        }
    }
    @Test void realMultipartValidUploadAndEmptyFileReachApplicationRules() throws Exception {
        var success = send("abc", "a.txt");
        assertThat(success.statusCode()).isEqualTo(201);
        assertThat((Number) JsonPath.read(success.body(), "$.size")).isEqualTo(3);
        var empty = send("", "a.txt");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(empty.body(), "$.code")).isEqualTo("FILE_VALIDATION_ERROR");
    }
    private HttpResponse<String> send(String data, String filename) throws Exception {
        return send(data, filename, "");
    }
    private HttpResponse<String> send(String data, String filename, String padding) throws Exception {
        String body = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
            + "\"\r\nContent-Type: text/plain\r\n\r\n" + data + "\r\n"
            + (padding.isEmpty() ? "" : "--boundary\r\nContent-Disposition: form-data; name=\"padding\"\r\n\r\n" + padding + "\r\n")
            + "--boundary--\r\n";
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/files?storageType=minio"))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
