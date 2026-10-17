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

/**
 * 配置加载与环境属性解析行为测试。
 *
 * <p>在独立的临时工作目录中拉起子进程探针，真实解析主配置文件（{@code application.yml}）
 * 与测试配置文件（{@code src/test/resources/application.yml}），验证各配置源的加载顺序与覆盖行为：
 * <ul>
 *   <li>无本地配置时的安全默认值回退（含 OSS 项目目录前缀默认值 {@code item/${spring.application.name}}）；</li>
 *   <li>从工作目录导入 UTF-8 Java Properties 格式的 {@code .env} 配置文件；</li>
 *   <li>操作系统/进程环境变量对 {@code .env} 配置的覆盖优先级；</li>
 *   <li>命令行参数（{@code --key=value}）对进程环境变量与 {@code .env} 的最高覆盖优先级；</li>
 *   <li>普通测试配置隔离，避免从本地 {@code .env} 导入敏感凭据或本地覆写。</li>
 * </ul>
 *
 * <p>该测试通过子进程探针独立提取解析后的属性集合，避免在当前测试进程中拉起完整的 Spring 容器、
 * 数据库连接池、AI 模型客户端或对象存储 SDK，实现轻量、快速且无外部依赖的配置绑定验证。
 */
class AiItemSummaryEnvironmentTest {

    /**
     * JUnit 5 注入的隔离临时目录。
     * 模拟每次测试运行时的独立进程工作目录，用于存放临时生成的 {@code .env} 文件、探针输出文件及子进程日志，
     * 避免不同用例之间或测试与宿主机本地环境相互污染。
     */
    @TempDir Path workingDirectory;

    /**
     * 验证生产配置能够正确从本地工作目录的 {@code .env} 文件导入各项业务与基础设施配置。
     *
     * <p>覆盖数据库连接、Spring AI、RAG 构建与阿里云文档智能（DocMind）、Mock 身份、文件上传大小上限、
     * Tomcat 请求头限制、MinIO 以及阿里云 OSS 的配置绑定。
     *
     * <p>特别验证：当 {@code .env} 中显式配置 {@code ALIYUN_OSS_KEY_PREFIX=/item/fixture-project/} 时，
     * 配置探针提取到的 {@code app.file.storage.aliyun.key-prefix} 正确解析为该设置值。
     *
     * @throws Exception 当临时文件写入或子进程探针执行失败时抛出
     */
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
            OPENAI_BASE_URL=https://api.openai.com/v1
            OPENAI_EMBEDDING_BASE_URL=https://embedding-fixture.example/v1
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
        assertThat(values.getProperty("spring.application.name")).isEqualTo("ai-item-summary");
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
        assertThat(values.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.openai.com/v1");
        assertThat(values.getProperty("spring.ai.openai.embedding.base-url"))
            .isEqualTo("https://embedding-fixture.example/v1");
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
        assertThat(values.getProperty("app.file.storage.key-prefix")).isEqualTo("files");
        assertThat(values.getProperty("app.file.storage.minio.enabled")).isEqualTo("true");
        assertThat(values.getProperty("app.file.storage.minio.endpoint")).isEqualTo("http://127.0.0.1:19000");
        assertThat(values.getProperty("app.file.storage.minio.secret-key")).isEqualTo("fixture-secret");
        assertThat(values.getProperty("app.file.storage.aliyun.enabled")).isEqualTo("true");
        // 验证从 .env 中正确加载显式设置的 ALIYUN_OSS_KEY_PREFIX 项目前缀
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("/item/fixture-project/");
        assertThat(values.getProperty("app.file.storage.aliyun.access-key-secret")).isEqualTo("fixture-secret");
    }

    /**
     * 验证操作系统/进程环境变量能够覆盖本地工作目录 {@code .env} 文件中的同名配置。
     *
     * <p>覆盖场景：
     * <ul>
     *   <li>{@code SERVER_PORT}：.env 为 9081，进程变量为 9082，生效值为 9082；</li>
     *   <li>{@code DB_USERNAME}：.env 为 local-user，进程变量为 process-user，生效值为 process-user；</li>
     *   <li>{@code APP_FILE_MAX_PART_HEADER_SIZE}：.env 为 5KB，进程变量为 6KB，生效值为 6KB；</li>
     *   <li>{@code ALIYUN_OSS_KEY_PREFIX}：.env 中配置为 {@code item/local-project}，进程变量配置为
     *       {@code item/process-project}，验证最终解析的 OSS 前缀属性优先采用进程环境变量 {@code item/process-project}。</li>
     * </ul>
     *
     * @throws Exception 当临时文件写入或子进程探针执行失败时抛出
     */
    /** 验证未单独指定 Embedding 服务地址时，复用 OpenAI 兼容服务的公共 base URL。 */
    @Test
    void embeddingBaseUrlFallsBackToSharedBaseUrl() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"),
            "OPENAI_BASE_URL=https://shared-fixture.example/v1\n");

        Properties values = probe("src/main/resources/application.yml", Map.of());

        assertThat(values.getProperty("spring.ai.openai.base-url"))
            .isEqualTo("https://shared-fixture.example/v1");
        assertThat(values.getProperty("spring.ai.openai.embedding.base-url"))
            .isEqualTo("https://shared-fixture.example/v1");
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
        // 验证进程环境变量 ALIYUN_OSS_KEY_PREFIX 成功覆盖 .env 中的同名配置
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/process-project");
    }

    /**
     * 验证命令行参数具有最高优先级，能够覆盖进程环境变量及 {@code .env}，并验证默认前缀随应用名联动变化。
     *
     * <p>验证重点：
     * <ul>
     *   <li>端口配置：命令行参数 {@code --server.port=9083} 优先于进程环境变量（9082）与 .env（9081）；</li>
     *   <li>Tomcat 请求头上限：命令行参数 {@code --server.tomcat.max-part-header-size=7KB} 优先于环境变量（6KB）；</li>
     *   <li>OSS 默认项目前缀联动：生产配置为 {@code app.file.storage.aliyun.key-prefix: ${ALIYUN_OSS_KEY_PREFIX:item/${spring.application.name}}}。
     *       在未显式提供 {@code ALIYUN_OSS_KEY_PREFIX} 时，若通过命令行参数传入 {@code --spring.application.name=renamed-project}，
     *       前缀属性应动态求值为 {@code item/renamed-project}，验证占位符随应用名称的动态联动特性。</li>
     * </ul>
     *
     * @throws Exception 当临时文件写入或子进程探针执行失败时抛出
     */
    @Test
    void commandLineOverridesProcessEnvironmentAndLocalFile() throws Exception {
        Files.writeString(workingDirectory.resolve(".env"), "SERVER_PORT=9081\n");
        Properties values = probe("src/main/resources/application.yml",
            Map.of("SERVER_PORT", "9082", "APP_FILE_MAX_PART_HEADER_SIZE", "6KB"),
            "--server.port=9083", "--server.tomcat.max-part-header-size=7KB",
            "--spring.application.name=renamed-project");
        assertThat(values.getProperty("server.port")).isEqualTo("9083");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("7KB");
        assertThat(values.getProperty("spring.application.name")).isEqualTo("renamed-project");
        // 未单独指定 OSS 项目目录时，默认前缀随命令行 --spring.application.name 变化
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/renamed-project");
    }

    /**
     * 验证在缺少本地 {@code .env} 文件且无环境变量覆盖时，配置能够安全回退至安全默认值。
     *
     * <p>验证重点：
     * <ul>
     *   <li>基础配置：端口 8080，数据库用户名密码为空，AI 模型及向量存储类型为 none，RAG 与 DocMind 均禁用；</li>
     *   <li>应用标识：{@code spring.application.name} 保持默认值 {@code ai-item-summary}；</li>
     *   <li>公共前缀：{@code app.file.storage.key-prefix} 保持默认值 {@code files}；</li>
     *   <li>OSS 默认项目前缀：在无 {@code .env} 且未指定 {@code ALIYUN_OSS_KEY_PREFIX} 时，
     *       {@code app.file.storage.aliyun.key-prefix} 正确解析为默认值 {@code item/ai-item-summary}
     *       （即 {@code item/${spring.application.name}} 计算结果）；</li>
     *   <li>存储开关：MinIO 与阿里云 OSS 默认保持 {@code false}，避免在缺少凭据时尝试初始化远端存储。</li>
     * </ul>
     *
     * @throws Exception 当子进程探针执行失败时抛出
     */
    @Test
    void missingLocalFileRetainsSafeDefaults() throws Exception {
        Properties values = probe("src/main/resources/application.yml", Map.of());
        assertThat(values.getProperty("server.port")).isEqualTo("8080");
        assertThat(values.getProperty("spring.application.name")).isEqualTo("ai-item-summary");
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
        assertThat(values.getProperty("spring.ai.openai.api-key")).isEmpty();
        assertThat(values.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.openai.com/v1");
        assertThat(values.getProperty("spring.ai.openai.embedding.base-url"))
            .isEqualTo("https://api.openai.com/v1");
        assertThat(values.getProperty("spring.ai.openai.embedding.model")).isEqualTo("text-embedding-3-small");
        assertThat(values.getProperty("app.file.storage.key-prefix")).isEqualTo("files");
        assertThat(values.getProperty("app.file.storage.minio.enabled")).isEqualTo("false");
        assertThat(values.getProperty("app.file.storage.aliyun.enabled")).isEqualTo("false");
        // 验证无 .env 时，OSS 项目目录前缀回退至默认值 item/ai-item-summary
        assertThat(values.getProperty("app.file.storage.aliyun.key-prefix")).isEqualTo("item/ai-item-summary");
        assertThat(values.getProperty("server.tomcat.max-part-header-size")).isEqualTo("4KB");
    }

    /**
     * 验证普通单元/集成测试使用的配置（{@code src/test/resources/application.yml}）不会导入本地 {@code .env} 文件。
     *
     * <p>即使本地存在包含真实凭据与生产配置的 {@code .env}，测试配置依然使用隔离的 H2 内存数据库、禁用模型及外部存储，
     * 避免本地运行环境对自动化测试结果产生非预期副作用。
     *
     * @throws Exception 当临时文件写入或子进程探针执行失败时抛出
     */
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

    /**
     * 启动独立的 Java 子进程运行 {@link ConfigurationProbe} 探针，提取指定配置文件解析后的属性集合。
     *
     * <p>执行流程与隔离机制：
     * <ol>
     *   <li>定位当前运行时的 {@code java} 可执行文件路径以及 surefire/java 测试 classpath；</li>
     *   <li>构造子进程命令，传入主配置/测试配置的绝对 URI（{@code --spring.config.location}）与输出属性文件路径（{@code --probe.output}）；</li>
     *   <li>通过正则剔除宿主机环境中可能干扰测试的开发者变量（如 {@code DB_*}、{@code ALIYUN_OSS_*}、{@code FILE_STORAGE_*}、{@code SPRING_*} 等）；</li>
     *   <li>注入测试用例显式提供的环境变量映射（{@code variables}）；</li>
     *   <li>启动子进程并在 30 秒超时内等待其正常退出（退出码 0），随后将落盘的属性文件加载为 {@link Properties} 返回。</li>
     * </ol>
     *
     * @param configuration 目标配置文件路径（相对路径或绝对路径）
     * @param variables     需注入子进程的环境变量键值映射
     * @param arguments     传递给配置探针的额外命令行参数（如 {@code --spring.application.name=...}）
     * @return 解析完成后的配置属性键值对集合，未配置的键默认值为 {@code "<unset>"}
     * @throws Exception 当子进程启动失败、执行超时或退出码非 0 时抛出
     */
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

    /**
     * 运行在隔离子进程中的配置解析探针程序。
     *
     * <p>利用 Spring Boot 底层的 {@link StandardEnvironment}、{@link SimpleCommandLinePropertySource}、
     * {@link RandomValuePropertySource} 与 {@link ConfigDataEnvironmentPostProcessor}，
     * 在不实例化重量级业务 Bean 的前提下，完全遵循 Spring Boot 规范解析配置文件、{@code .env} 导入与占位符。
     */
    public static class ConfigurationProbe {
        /**
         * 探针入口主函数。
         *
         * @param arguments 包含命令行配置参数、{@code --spring.config.location} 与 {@code --probe.output} 的参数列表
         * @throws Exception 当环境配置解析异常或文件写入失败时抛出
         */
        public static void main(String[] arguments) throws Exception {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
            RandomValuePropertySource.addToEnvironment(environment);
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            var values = new Properties();
            for (String key : List.of("server.port", "spring.application.name", "spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "spring.ai.model.chat", "spring.ai.model.embedding",
                    "spring.ai.vectorstore.type", "app.knowledge.rag.enabled", "spring.ai.openai.api-key",
                    "spring.ai.openai.base-url", "spring.ai.openai.embedding.base-url",
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
                    "app.file.storage.key-prefix",
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
