package com.item.aisummary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 在独立工作目录解析真实配置，不创建数据库、模型或存储客户端。 */
class AiItemSummaryEnvironmentTest {
    @TempDir Path workingDirectory;

    @Test
    void loadsLocalFileThroughProductionConfiguration() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"), """
            SERVER_PORT=9081
            DB_URL=jdbc:mysql://localhost:3306/local_demo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
            DB_USERNAME=local-user
            DB_PASSWORD=demo#part=tail
            SPRING_AI_MODEL_CHAT=openai
            OPENAI_API_KEY=fixture-key
            OPENAI_MODEL=fixture-model
            APP_KNOWLEDGE_RAG_ENABLED=true
            APP_KNOWLEDGE_DOCMIND_ENABLED=true
            ALIYUN_DOCMIND_ACCESS_KEY_ID=fixture-docmind-id
            ALIYUN_DOCMIND_ACCESS_KEY_SECRET=fixture-docmind-secret
            ALIYUN_DOCMIND_ENDPOINT=docmind-fixture.example
            ALIYUN_DOCMIND_VLM_ENHANCEMENT=false
            ALIYUN_DOCMIND_FORMULA_ENHANCEMENT=true
            ALIYUN_DOCMIND_WAIT_TIMEOUT=300s
            ALIYUN_DOCMIND_POLL_INTERVAL=1s
            SPRING_AI_MODEL_EMBEDDING=openai
            SPRING_AI_VECTORSTORE_TYPE=qdrant
            OPENAI_EMBEDDING_API_KEY=fixture-embedding-key
            OPENAI_EMBEDDING_MODEL=fixture-embedding-model
            OPENAI_EMBEDDING_DIMENSIONS=256
            QDRANT_HOST=fixture-host
            QDRANT_PORT=16334
            QDRANT_USE_TLS=true
            QDRANT_API_KEY=fixture-qdrant-key
            QDRANT_COLLECTION_NAME=fixture-collection
            QDRANT_INITIALIZE_SCHEMA=true
            RAG_BATCH_SIZE=16
            RAG_CHUNK_SIZE=256
            RAG_MIN_CHUNK_SIZE_CHARS=80
            RAG_MIN_CHUNK_LENGTH_TO_EMBED=0
            RAG_MAX_NUM_CHUNKS=1000
            RAG_KEEP_SEPARATOR=false
            RAG_PUNCTUATION_MARKS=。；
            APP_AUTH_MOCK_USER_ID=local-user
            APP_AUTH_MOCK_USERNAME=本地演示用户
            APP_FILE_MAX_SIZE=7MB
            APP_FILE_REQUEST_MAX_SIZE=8MB
            APP_FILE_MAX_PART_HEADER_SIZE=6KB
            FILE_STORAGE_DOWNLOAD_URL_EXPIRATION=10m
            MINIO_ENABLED=true
            MINIO_ENDPOINT=http://127.0.0.1:19000
            MINIO_BUCKET=fixture-bucket
            MINIO_ACCESS_KEY=fixture-access
            MINIO_SECRET_KEY=fixture-secret
            ALIYUN_OSS_ENABLED=true
            ALIYUN_OSS_KEY_PREFIX=/item/fixture-project/
            ALIYUN_OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
            ALIYUN_OSS_BUCKET=fixture-bucket
            ALIYUN_OSS_ACCESS_KEY_ID=fixture-access
            ALIYUN_OSS_ACCESS_KEY_SECRET=fixture-secret
            """, StandardCharsets.UTF_8);

        Properties values = probe("src/main/resources/application.yml", Map.of());
        assertThat(values.getProperty("server.port")).isEqualTo("9081");
        assertThat(values.getProperty("spring.datasource.url"))
            .isEqualTo("jdbc:mysql://localhost:3306/local_demo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        assertThat(values.getProperty("spring.datasource.username")).isEqualTo("local-user");
        assertThat(values.getProperty("spring.datasource.password")).isEqualTo("demo#part=tail");
        assertThat(values.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(values.getProperty("spring.ai.openai.api-key")).isEqualTo("fixture-key");
        assertThat(values.getProperty("spring.ai.openai.chat.model")).isEqualTo("fixture-model");
        assertThat(values.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
        assertThat(values.getProperty("spring.ai.vectorstore.type")).isEqualTo("qdrant");
        assertThat(values.getProperty("app.knowledge.rag.enabled")).isEqualTo("true");
        assertThat(values.getProperty("app.knowledge.rag.docmind.enabled")).isEqualTo("true");
        assertThat(values.getProperty("app.knowledge.rag.docmind.access-key-id")).isEqualTo("fixture-docmind-id");
        assertThat(values.getProperty("app.knowledge.rag.docmind.access-key-secret")).isEqualTo("fixture-docmind-secret");
        assertThat(values.getProperty("app.knowledge.rag.docmind.endpoint")).isEqualTo("docmind-fixture.example");
        assertThat(values.getProperty("app.knowledge.rag.docmind.vlm-enhancement")).isEqualTo("false");
        assertThat(values.getProperty("app.knowledge.rag.docmind.formula-enhancement")).isEqualTo("true");
        assertThat(values.getProperty("app.knowledge.rag.docmind.wait-timeout")).isEqualTo("300s");
        assertThat(values.getProperty("app.knowledge.rag.docmind.poll-interval")).isEqualTo("1s");
        assertThat(values.getProperty("spring.ai.openai.embedding.api-key")).isEqualTo("fixture-embedding-key");
        assertThat(values.getProperty("spring.ai.openai.embedding.model")).isEqualTo("fixture-embedding-model");
        assertThat(values.getProperty("spring.ai.openai.embedding.dimensions")).isEqualTo("256");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.host")).isEqualTo("fixture-host");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.port")).isEqualTo("16334");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.use-tls")).isEqualTo("true");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.api-key")).isEqualTo("fixture-qdrant-key");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.collection-name")).isEqualTo("fixture-collection");
        assertThat(values.getProperty("spring.ai.vectorstore.qdrant.initialize-schema")).isEqualTo("true");
        assertThat(values.getProperty("app.knowledge.rag.batch-size")).isEqualTo("16");
        assertThat(values.getProperty("app.knowledge.rag.chunk-size")).isEqualTo("256");
        assertThat(values.getProperty("app.knowledge.rag.min-chunk-size-chars")).isEqualTo("80");
        assertThat(values.getProperty("app.knowledge.rag.min-chunk-length-to-embed")).isEqualTo("0");
        assertThat(values.getProperty("app.knowledge.rag.max-num-chunks")).isEqualTo("1000");
        assertThat(values.getProperty("app.knowledge.rag.keep-separator")).isEqualTo("false");
        assertThat(values.getProperty("app.knowledge.rag.punctuation-marks")).isEqualTo("。；");
        assertThat(values.getProperty("app.knowledge.rag.max-source-size")).isEqualTo("7MB");
        assertThat(values.getProperty("app.auth.mock.user-id")).isEqualTo("local-user");
        assertThat(values.getProperty("app.auth.mock.username")).isEqualTo("本地演示用户");
        assertThat(values.getProperty("app.file.upload.max-size")).isEqualTo("7MB");
        assertThat(values.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("7MB");
        assertThat(values.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("8MB");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("6KB");
        assertThat(values.getProperty("app.file.storage.download-url-expiration")).isEqualTo("10m");
        assertThat(values.getProperty("app.file.storage.minio.enabled")).isEqualTo("true");
        assertThat(values.getProperty("app.file.storage.minio.endpoint")).isEqualTo("http://127.0.0.1:19000");
        assertThat(values.getProperty("app.file.storage.minio.secret-key")).isEqualTo("fixture-secret");
        assertThat(values.getProperty("app.file.storage.aliyun.enabled")).isEqualTo("true");
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("/item/fixture-project/");
        assertThat(values.getProperty("app.file.storage.aliyun.access-key-secret")).isEqualTo("fixture-secret");
    }

    @Test
    void processEnvironmentOverridesLocalFile() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"),
            "SERVER_PORT=9081\nDB_USERNAME=local-user\nAPP_FILE_MAX_PART_HEADER_SIZE=5KB\nALIYUN_OSS_KEY_PREFIX=item/local-project\n");
        Properties values = probe("src/main/resources/application.yml",
            Map.of("SERVER_PORT", "9082", "DB_USERNAME", "process-user", "APP_FILE_MAX_PART_HEADER_SIZE", "6KB",
                "ALIYUN_OSS_KEY_PREFIX", "item/process-project"));
        assertThat(values.getProperty("server.port")).isEqualTo("9082");
        assertThat(values.getProperty("spring.datasource.username")).isEqualTo("process-user");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("6KB");
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/process-project");
    }

    @Test
    void commandLineOverridesProcessEnvironmentAndLocalFile() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"), "SERVER_PORT=9081\n");
        Properties values = probe("src/main/resources/application.yml",
            Map.of("SERVER_PORT", "9082", "APP_FILE_MAX_PART_HEADER_SIZE", "6KB"),
            "--server.port=9083", "--server.tomcat.max-part-header-size=7KB",
            "--spring.application.name=renamed-project");
        assertThat(values.getProperty("server.port")).isEqualTo("9083");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("7KB");
        // 未单独指定 OSS 项目目录时，默认值跟随应用名称。
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/renamed-project");
    }

    @Test
    void missingLocalFileRetainsSafeDefaults() throws Exception {
        Properties values = probe("src/main/resources/application.yml", Map.of());
        assertThat(values.getProperty("server.port")).isEqualTo("8080");
        assertThat(values.getProperty("spring.datasource.username")).isEmpty();
        assertThat(values.getProperty("spring.datasource.password")).isEmpty();
        assertThat(values.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(values.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(values.getProperty("spring.ai.vectorstore.type")).isEqualTo("none");
        assertThat(values.getProperty("app.knowledge.rag.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.knowledge.rag.docmind.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.knowledge.rag.docmind.access-key-id")).isEmpty();
        assertThat(values.getProperty("app.knowledge.rag.docmind.access-key-secret")).isEmpty();
        assertThat(values.getProperty("app.knowledge.rag.docmind.vlm-enhancement")).isEqualTo("true");
        assertThat(values.getProperty("app.knowledge.rag.docmind.formula-enhancement")).isEqualTo("false");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("4KB");
        assertThat(values.getProperty("spring.ai.openai.api-key")).isEmpty();
        assertThat(values.getProperty("app.file.storage.minio.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.file.storage.aliyun.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/ai-item-summary");
    }

    @Test
    void ordinaryTestConfigurationDoesNotImportLocalCredentials() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"), """
            DB_URL=jdbc:mysql://localhost:3306/local_demo
            SPRING_AI_MODEL_CHAT=openai
            OPENAI_API_KEY=fixture-key
            MINIO_ENABLED=true
            ALIYUN_OSS_ENABLED=true
            APP_AUTH_MOCK_USERNAME=local-name
            """);
        Properties values = probe("src/test/resources/application.yml", Map.of());
        assertThat(values.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
        assertThat(values.getProperty("spring.datasource.username")).isEqualTo("sa");
        assertThat(values.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(values.getProperty("spring.ai.openai.api-key")).isEmpty();
        assertThat(values.getProperty("app.file.storage.minio.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.file.storage.aliyun.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.auth.mock.username")).isEqualTo("mock-user");
    }

    private Properties probe(String configuration, Map<String, String> variables, String... arguments) throws Exception {
        Path output = workingDirectory.resolve("values.properties");
        Path log = workingDirectory.resolve("probe.log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
            File.separatorChar == '\\' ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(javaExecutable, "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ConfigurationProbe.class.getName(),
            "--spring.config.location=" + Path.of(configuration).toAbsolutePath().toUri(),
            "--probe.output=" + output));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        // 不继承开发者的服务开关、凭据、配置位置或 Java 注入参数。
        builder.environment().keySet().removeIf(key -> key.matches(
            "(?i)(DB_.*|OPENAI_.*|QDRANT_.*|RAG_.*|MINIO_.*|ALIYUN_OSS_.*|ALIYUN_DOCMIND_.*|FILE_STORAGE_.*|APP_.*|USER_TEST_DB_.*|SPRING_.*|SERVER_PORT|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)"));
        builder.environment().putAll(variables);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("配置探针应及时结束").isTrue();
            assertThat(process.exitValue()).as("配置探针输出：%s", Files.readString(log)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        var values = new Properties();
        try (var reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return values;
    }

    public static class ConfigurationProbe {
        public static void main(String[] arguments) throws Exception {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
            RandomValuePropertySource.addToEnvironment(environment);
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            var values = new Properties();
            for (String key : List.of("server.port", "spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "spring.ai.model.chat", "spring.ai.model.embedding",
                    "spring.ai.vectorstore.type", "app.knowledge.rag.enabled", "spring.ai.openai.api-key",
                    "spring.ai.openai.embedding.api-key", "spring.ai.openai.embedding.model", "spring.ai.openai.embedding.dimensions",
                    "spring.ai.vectorstore.qdrant.host", "spring.ai.vectorstore.qdrant.port", "spring.ai.vectorstore.qdrant.use-tls",
                    "spring.ai.vectorstore.qdrant.api-key", "spring.ai.vectorstore.qdrant.collection-name", "spring.ai.vectorstore.qdrant.initialize-schema",
                    "app.knowledge.rag.batch-size", "app.knowledge.rag.chunk-size", "app.knowledge.rag.min-chunk-size-chars",
                    "app.knowledge.rag.min-chunk-length-to-embed", "app.knowledge.rag.max-num-chunks", "app.knowledge.rag.keep-separator",
                    "app.knowledge.rag.punctuation-marks", "app.knowledge.rag.max-source-size",
                    "app.knowledge.rag.docmind.enabled", "app.knowledge.rag.docmind.access-key-id",
                    "app.knowledge.rag.docmind.access-key-secret", "app.knowledge.rag.docmind.endpoint",
                    "app.knowledge.rag.docmind.vlm-enhancement", "app.knowledge.rag.docmind.formula-enhancement",
                    "app.knowledge.rag.docmind.wait-timeout", "app.knowledge.rag.docmind.poll-interval",
                    "spring.ai.openai.chat.model", "app.auth.mock.user-id", "app.auth.mock.username",
                    "app.file.upload.max-size", "spring.servlet.multipart.max-file-size",
                    "spring.servlet.multipart.max-request-size", "server.tomcat.max-part-header-size",
                    "app.file.storage.download-url-expiration",
                    "app.file.storage.minio.enabled", "app.file.storage.minio.endpoint",
                    "app.file.storage.minio.secret-key", "app.file.storage.aliyun.enabled",
                    "app.file.storage.aliyun.key-prefix", "app.file.storage.aliyun.access-key-secret")) {
                values.setProperty(key, environment.getProperty(key, "<unset>"));
            }
            try (var writer = Files.newBufferedWriter(Path.of(environment.getRequiredProperty("probe.output")),
                    StandardCharsets.UTF_8)) {
                values.store(writer, "仅受控测试配置值");
            }
        }
    }
}
