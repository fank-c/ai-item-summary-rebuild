package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusRequest;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody.QueryDocParserStatusResponseBodyDataOutputFormatResult;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody.QueryDocParserStatusResponseBodyDataOutputFormatResultPages;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobAdvanceRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.item.aisummary.infrastructure.knowledge.indexing.config.DocMindProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/**
 * 阿里云文档智能（Document Mind）PDF 云端解析客户端适配器。
 *
 * <p>将文档智能官方的“异步作业”（提交任务 -> 轮询状态 -> 分页获取版面结果）同步封装为有界的阻塞解析流程。
 *
 * <p>核心机制与安全边界：
 * <ul>
 *   <li>生命周期与流控制：文档原字节通过本地内存流（{@link ByteArrayInputStream}）同步上传，不向外部传递源文件的原始名称、存储路径或预签名 URL。</li>
 *   <li>禁用 SDK 自动重试：配置 {@code RuntimeOptions.setAutoretry(false)} 和 {@code setMaxAttempts(1)}，
 *       防止 SDK 在网络抖动或超时时隐式重试导致阿里云多次计费，全部重试与超时均由应用层绝对截止时间显式管控。</li>
 *   <li>绝对超时与中断控制：依据 {@link DocMindProperties#waitTimeout()} 计算纳秒级绝对截止时间（{@code deadline}）；
 *       每次 HTTP 调用的网络连接与读取超时根据剩余时间动态收缩，轮询休眠响应线程中断，超时直接熔断。</li>
 *   <li>分页拉取与容量防御：结果拉取以 {@value #LAYOUT_PAGE_SIZE}（100）为单页步长逐页拉取版面；
 *       严格防御版面总数不超过 {@value #MAX_LAYOUTS}（100,000）且提取总字符数不超过 {@value #MAX_RESULT_CHARS}（50,000,000）。</li>
 *   <li>敏感凭据与异常脱敏：底层 SDK 抛出的网络或服务端异常可能携带 AccessKey、签名参数或远端响应体；
 *       本客户端在捕获后统一舍弃原始 cause 并转换为脱敏的业务异常提示，严禁向外输出凭据。</li>
 * </ul>
 */
public final class DocMindPdfParserClient implements DocMindPdfParser {
    /** 每次向 DocMind 拉取版面结果的单页步长大小。 */
    static final int LAYOUT_PAGE_SIZE = 100;
    /** DocMind 允许提取的最大版面数量防御上限。 */
    private static final int MAX_LAYOUTS = 100_000;
    /** DocMind 允许提取的最大字符总数防御上限（5000 万字）。 */
    private static final long MAX_RESULT_CHARS = 50_000_000L;

    /** 单次 HTTP 请求默认连接超时时间（毫秒）。 */
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5000;
    /** 单次 HTTP 请求最大读取超时时间（毫秒）。 */
    private static final long MAX_READ_TIMEOUT_MILLIS = 30_000L;

    private final Client client;
    private final DocMindProperties properties;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    /**
     * 使用生产配置构造文档智能客户端实例。
     *
     * @param properties DocMind 属性配置（包含 AccessKey、端点、超时时限）
     */
    public DocMindPdfParserClient(DocMindProperties properties) {
        this(createClient(properties), properties, System::nanoTime,
                nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    /**
     * 包内可见构造器，支持注入时间源与休眠策略用于确定性测试。
     */
    DocMindPdfParserClient(Client client, DocMindProperties properties, LongSupplier nanoTime, Sleeper sleeper) {
        this.client = java.util.Objects.requireNonNull(client);
        this.properties = java.util.Objects.requireNonNull(properties);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
        this.sleeper = java.util.Objects.requireNonNull(sleeper);
    }

    /**
     * 初始化阿里云 OpenAPI 基础 Client。
     *
     * <p>固定 HTTPS 协议，单次连接超时 5s、读取超时 30s，关闭 SDK 本地行为数据统计上报。
     */
    private static Client createClient(DocMindProperties properties) {
        try {
            return new Client(new Config().setAccessKeyId(properties.accessKeyId())
                    .setAccessKeySecret(properties.accessKeySecret()).setEndpoint(properties.endpoint())
                    .setProtocol("HTTPS")
                    .setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MILLIS)
                    .setReadTimeout((int) MAX_READ_TIMEOUT_MILLIS)
                    .setEnableUsageDataCollection(false));
        } catch (Exception exception) {
            throw failure("DocMind 客户端初始化失败");
        }
    }

    /**
     * 执行云端文档智能解析的主编排入口。
     *
     * <p>协调执行清晰的三阶段有界流程：
     * <ol>
     *   <li><b>提交作业（Submit）</b>：上传 PDF 内存流，配置 VLM 视觉大模型增强与格式选项，获取云端任务标识；</li>
     *   <li><b>轮询等待（Polling）</b>：在截止时间前以固定间隔轮询状态，成功时校验并提取已识别完成的页面影像清单；</li>
     *   <li><b>分页拉取（Fetch）</b>：以 100 为步长分页拉取全部版面元素，核验版面总数与字符总数防御预算。</li>
     * </ol>
     *
     * @param pdfBytes PDF 原始二进制字节
     * @return 包含版面列表与已处理页码集合的结果对象
     * @throws IllegalArgumentException 当源字节为空时抛出
     * @throws IllegalStateException 当提交失败、轮询失败、解析被拒绝、超时或结果超限时抛出
     */
    @Override
    public Result parse(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 源文件不能为空");
        }

        // 以纳秒为单位计算绝对截止时间（deadline），贯穿后续所有提交、轮询休眠与分页拉取调用
        long deadline = nanoTime.getAsLong() + properties.waitTimeout().toNanos();

        // 阶段 1：提交解析作业
        String taskId = submitJob(pdfBytes, deadline);

        // 阶段 2：轮询等待作业完成并提取已识别页码集合
        Set<Integer> processedPages = waitForCompletion(taskId, deadline);

        // 阶段 3：分页拉取全部版面数据并装配为 Result
        List<Layout> layouts = fetchLayouts(taskId, deadline);

        return new Result(layouts, processedPages);
    }

    /**
     * 构建并提交文档解析异步任务。
     *
     * <p>安全细节：
     * <ul>
     *   <li>使用 {@link ByteArrayInputStream} 内存流同步消费，不在网络中传递原始文件名或本地路径；</li>
     *   <li>显式设置 {@code fileNameExtension = "pdf"} 指明解析路由；</li>
     *   <li>请求 {@code markdown} 和 {@code visualLayoutInfo} 两种输出格式，支持段落正文与版面切分；</li>
     *   <li>当 {@code vlmEnhancement} 为 true 时启用视觉大模型增强与 HTML 表格输出（保留跨行合并属性）；</li>
     *   <li>设置 {@code needHeaderFooter = true} 提取完整版面边界信息。</li>
     * </ul>
     *
     * @param pdfBytes PDF 原文字节数组
     * @param deadline 纳秒级绝对截止时间
     * @return 云端生成的唯一任务标识 taskId
     */
    private String submitJob(byte[] pdfBytes, long deadline) {
        try (var input = new ByteArrayInputStream(pdfBytes)) {
            var request = buildSubmitRequest(input);
            var response = call(() -> client.submitDocParserJobAdvance(request, runtimeOptions(deadline)),
                    deadline, "DocMind 文档提交失败");

            var body = response == null ? null : response.getBody();
            if (body == null) {
                throw failure("DocMind 提交响应无效");
            }
            requireResponse(response.getStatusCode(), body.getCode());

            String taskId = body.getData() == null ? null : body.getData().getId();
            if (taskId == null || taskId.isBlank()) {
                throw failure("DocMind 未返回有效任务标识");
            }
            return taskId;
        } catch (java.io.IOException exception) {
            throw failure("PDF 源流关闭失败");
        }
    }

    /**
     * 构造向阿里云文档智能提交作业的请求对象。
     */
    private SubmitDocParserJobAdvanceRequest buildSubmitRequest(InputStream input) {
        return new SubmitDocParserJobAdvanceRequest()
                .setFileNameExtension("pdf")
                .setFileUrlObject(input)
                .setOutputFormat(List.of("markdown", "visualLayoutInfo"))
                .setLlmEnhancement(properties.vlmEnhancement())
                .setEnhancementMode(properties.vlmEnhancement() ? "VLM" : null)
                .setFormulaEnhancement(properties.formulaEnhancement())
                .setOutputHtmlTable(properties.vlmEnhancement())
                .setNeedHeaderFooter(true);
    }

    /**
     * 轮询文档解析任务状态直至完成或超时。
     *
     * <p>状态流转语义：
     * <ul>
     *   <li>{@code "success"}：解析完成，提取已完成渲染处理的页面影像清单（pageImage）；</li>
     *   <li>{@code "fail"}：云端明确解析失败，抛出业务异常；</li>
     *   <li>{@code "init"} 或 {@code "processing"}：任务仍在处理中，受控休眠后继续轮询；</li>
     *   <li>其他未知状态：抛出未知状态异常拒绝；</li>
     *   <li>超时熔断：当超过 deadline 时立即终止并抛出超时异常。</li>
     * </ul>
     *
     * @param taskId 云端任务 ID
     * @param deadline 纳秒级绝对截止时间
     * @return 云端输出的页面影像清单中的页码集合
     */
    private Set<Integer> waitForCompletion(String taskId, long deadline) {
        while (true) {
            var request = new QueryDocParserStatusRequest().setId(taskId);
            var response = call(() -> client.queryDocParserStatusWithOptions(request, runtimeOptions(deadline)),
                    deadline, "DocMind 状态查询失败");

            var body = response == null ? null : response.getBody();
            var data = body == null ? null : body.getData();
            if (data == null) {
                throw failure("DocMind 状态响应无效");
            }
            requireResponse(response.getStatusCode(), body.getCode());

            String status = data.getStatus();
            if ("success".equalsIgnoreCase(status)) {
                return pageIds(data.getOutputFormatResult());
            }
            if ("fail".equalsIgnoreCase(status)) {
                throw failure("DocMind 文档解析失败");
            }
            if (!"init".equalsIgnoreCase(status) && !"processing".equalsIgnoreCase(status)) {
                throw failure("DocMind 返回未知任务状态");
            }

            // 检查截止时间是否已耗尽
            requireTime(deadline);

            // 休眠时间取轮询间隔与距离截止时间剩余纳秒数的较小者，避免休眠越过超时时间
            long remainingNanos = deadline - nanoTime.getAsLong();
            long sleepNanos = Math.min(remainingNanos, properties.pollInterval().toNanos());
            try {
                sleeper.sleep(sleepNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failure("等待 DocMind 解析时被中断");
            }
        }
    }

    /**
     * 分页拉取任务解析产出的全部版面元素。
     *
     * <p>拉取与防御规则：
     * <ul>
     *   <li>使用 {@code layoutNum} 作为当前已拉取总数的偏移游标，以 {@value #LAYOUT_PAGE_SIZE}（100）为固定步长分页获取；</li>
     *   <li>单页结果数量少于 {@value #LAYOUT_PAGE_SIZE} 即代表所有版面拉取完毕；</li>
     *   <li>累计版面总数超过 {@value #MAX_LAYOUTS} 时主动防御熔断，防止巨大恶意响应爆内存；</li>
     *   <li>累计提取文本字符总数超过 {@value #MAX_RESULT_CHARS} 时主动防御熔断；</li>
     *   <li>若最终版面列表为空，判定为无效解析结果拒绝。</li>
     * </ul>
     *
     * @param taskId 云端任务 ID
     * @param deadline 纳秒级绝对截止时间
     * @return 汇聚后的全部版面列表（不可变副本）
     */
    private List<Layout> fetchLayouts(String taskId, long deadline) {
        var layouts = new ArrayList<Layout>();
        long totalResultChars = 0;

        while (true) {
            var request = new GetDocParserResultRequest()
                    .setId(taskId)
                    .setLayoutNum(layouts.size())
                    .setLayoutStepSize(LAYOUT_PAGE_SIZE);

            var response = call(() -> client.getDocParserResultWithOptions(request, runtimeOptions(deadline)),
                    deadline, "DocMind 结果获取失败");

            var body = response == null ? null : response.getBody();
            var data = body == null ? null : body.getData();
            if (data == null) {
                throw failure("DocMind 结果响应无效");
            }
            requireResponse(response.getStatusCode(), body.getCode());

            if (!(data.get("layouts") instanceof List<?> rawLayouts) || rawLayouts.size() > LAYOUT_PAGE_SIZE) {
                throw failure("DocMind 版面结果无效");
            }

            // 预先校验版面总数防御上限
            if (rawLayouts.size() > MAX_LAYOUTS - layouts.size()) {
                throw failure("DocMind 版面数量超过解析限制");
            }

            for (Object raw : rawLayouts) {
                Layout layout = parseLayout(raw);
                long chars = calculateLayoutChars(layout);
                totalResultChars += chars;
                if (totalResultChars > MAX_RESULT_CHARS) {
                    throw failure("DocMind 提取文本超过解析限制");
                }
                layouts.add(layout);
            }

            // 游标拉取结束判定：当本次返回条数不足单页步长时，说明云端已无后续数据
            if (rawLayouts.size() < LAYOUT_PAGE_SIZE) {
                break;
            }
        }

        if (layouts.isEmpty()) {
            throw failure("DocMind 未提取到有效内容");
        }
        return List.copyOf(layouts);
    }

    /**
     * 将云端返回的无类型 Map 解析并校验为强类型的 {@link Layout} 记录对象。
     *
     * @param raw 未经类型转换的原始版面对象
     * @return 强类型 Layout 结构
     */
    private static Layout parseLayout(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw failure("DocMind 版面结果无效");
        }
        return new Layout(
                text(map.get("text")),
                text(map.get("markdownContent")),
                text(map.get("llmResult")),
                text(map.get("type")),
                text(map.get("subType")),
                integer(map.get("level")),
                integer(map.get("pageNum")),
                integer(map.get("index"))
        );
    }

    /**
     * 计算单个版面对象中包含的全部文本有效字符长度之和。
     */
    private static long calculateLayoutChars(Layout layout) {
        return length(layout.text()) + length(layout.markdownContent()) + length(layout.llmResult());
    }

    /**
     * 从 DocMind 状态响应的 outputFormatResult 中提取 pageImage 类型的页面 ID 集合。
     *
     * <p>pageImage 清单代表云端已完整完成版面切分和图像渲染的页面，用于后续与本地总物理页数进行严格双向核对。
     * 集合会自动拦截重复页码（{@code !ids.add(pageId)}），发现重复或负数页码时立即拒绝。
     *
     * @param outputs 云端输出格式结果列表
     * @return 页面 ID 集合（非空且已防御非法负数与重复 ID）
     */
    private static Set<Integer> pageIds(List<QueryDocParserStatusResponseBodyDataOutputFormatResult> outputs) {
        if (outputs == null) {
            throw failure("DocMind 未返回页面影像清单");
        }
        var ids = new HashSet<Integer>();
        for (var output : outputs) {
            if (output == null || !"pageImage".equalsIgnoreCase(output.getOutputType())) {
                continue;
            }
            if (output.getPages() == null) {
                throw failure("DocMind 页面影像清单无效");
            }
            for (var page : output.getPages()) {
                if (page == null) {
                    throw failure("DocMind 页面影像清单无效");
                }
                Integer pageId = page.getPageIdCurDoc();
                if (pageId == null || pageId < 0 || !ids.add(pageId)) {
                    throw failure("DocMind 页面影像清单无效");
                }
            }
        }
        if (ids.isEmpty()) {
            throw failure("DocMind 未返回页面影像清单");
        }
        return Set.copyOf(ids);
    }

    /**
     * 执行云端 OpenAPI 网络调用，统一进行超时检查、线程中断处理与异常脱敏。
     *
     * <p>安全核心原则：
     * SDK 底层抛出的异常可能包含 AccessKey、签名 URL 或提供商敏感诊断信息；
     * 为防止敏感数据泄漏，这里故意丢弃原始 cause，仅抛出脱敏的安全业务错误信息。
     *
     * @param <T> 返回值类型
     * @param action 调用的 Callable 闭包
     * @param deadline 纳秒级绝对截止时间
     * @param safeMessage 发生异常时对外呈现的脱敏中文错误提示
     * @return 调用结果
     */
    private <T> T call(Callable<T> action, long deadline, String safeMessage) {
        requireTime(deadline);
        T result;
        try {
            result = action.call();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("等待 DocMind 解析时被中断");
        } catch (Exception exception) {
            // SDK 异常可能包含凭据、签名 URL 和完整的供应商响应体；必须丢弃 cause 防止日志或上层泄漏
            throw failure(safeMessage);
        }
        requireTime(deadline);
        return result;
    }

    /**
     * 校验 HTTP 状态码与 DocMind 业务 code。
     *
     * <p>即使 HTTP 状态码为 200，若云端返回的业务状态码 code 不等于 "200"（例如 QuotaExceeded），
     * 也必须判定为失败并抛出脱敏异常。
     */
    private static void requireResponse(Integer httpStatus, String code) {
        if (httpStatus == null || httpStatus < 200 || httpStatus >= 300) {
            throw failure("DocMind 响应状态无效");
        }
        if (code != null && !"200".equals(code)) {
            throw failure("DocMind 响应状态无效");
        }
    }

    /**
     * 安全类型转换为 String，避免非字符串对象导致的隐蔽类型转换异常。
     */
    private static String text(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw failure("DocMind 文本字段无效");
    }

    /**
     * 安全类型转换为 Integer，支持标准 Integer 及受控范围内的 Long 数值缩小转换。
     */
    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw failure("DocMind 整数字段无效");
    }

    private static long length(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 根据当前距离 deadline 的剩余时间动态计算单次 HTTP 调用的 RuntimeOptions。
     *
     * <p>配置原则：
     * <ul>
     *   <li>显式设置 {@code setAutoretry(false)} 和 {@code setMaxAttempts(1)}，关闭 SDK 隐式重试；</li>
     *   <li>单次连接超时最多 5s（{@value #DEFAULT_CONNECT_TIMEOUT_MILLIS} ms）；</li>
     *   <li>读取超时取 30s（{@value #MAX_READ_TIMEOUT_MILLIS} ms）与距离 deadline 剩余毫秒数的较小值，最小为 1ms。</li>
     * </ul>
     */
    private RuntimeOptions runtimeOptions(long deadline) {
        requireTime(deadline);
        long remainingMillis = (deadline - nanoTime.getAsLong()) / 1_000_000L;
        int timeout = (int) Math.min(MAX_READ_TIMEOUT_MILLIS, Math.max(1L, remainingMillis));
        return new RuntimeOptions()
                .setAutoretry(false)
                .setMaxAttempts(1)
                .setConnectTimeout(Math.min(DEFAULT_CONNECT_TIMEOUT_MILLIS, timeout))
                .setReadTimeout(timeout);
    }

    /**
     * 检查当前线程未被中断且未超过截止时间。
     */
    private void requireTime(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw failure("等待 DocMind 解析时被中断");
        }
        if (deadline - nanoTime.getAsLong() <= 0) {
            throw failure("DocMind 解析等待超时");
        }
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    /**
     * 休眠策略接口，用于单元测试中模拟时间流逝而不产生真实时钟等待。
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }
}
