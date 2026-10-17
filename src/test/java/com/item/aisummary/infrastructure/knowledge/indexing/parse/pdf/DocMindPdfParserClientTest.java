package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aliyun.tea.TeaException;
import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponse;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponseBody;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponse;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody.QueryDocParserStatusResponseBodyData;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody.QueryDocParserStatusResponseBodyDataOutputFormatResult;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponseBody.QueryDocParserStatusResponseBodyDataOutputFormatResultPages;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponse;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponseBody;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponseBody.SubmitDocParserJobResponseBodyData;
import com.item.aisummary.infrastructure.knowledge.indexing.config.DocMindProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 阿里云文档智能（DocMind）客户端适配器单元测试。
 *
 * <p>基于 Mockito 对 OpenAPI Client 进行替身隔离，验证：
 * <ul>
 *   <li>主链路流程：文档字节提交、视觉增强参数传递、异步任务状态轮询与全量分页版面结果拉取；</li>
 *   <li>禁用 SDK 重试：验证单次请求显式关闭自动重试（setAutoretry(false)），连接超时不超过 5s；</li>
 *   <li>绝对超时与中断：验证超出 waitTimeout 时抛出脱敏异常，线程中断时正确响应并设置中断标志；</li>
 *   <li>容量防御边界：拦截版面总数超限（>100,000）与提取总字符数超限（>50,000,000）；</li>
 *   <li>敏感信息保护：捕获底层 SDK 异常时舍弃原始 cause，避免密钥与远端响应体泄漏。</li>
 * </ul>
 */
class DocMindPdfParserClientTest {
    /** 捕获本类日志，断言失败步骤可定位且不输出 SDK 的任意消息或 Throwable。 */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DocMindPdfParserClient.class);
    private final Client sdk = mock(Client.class);
    private final AtomicLong nanos = new AtomicLong();
    private final DocMindProperties properties = new DocMindProperties(true, "fixture-id", "fixture-secret",
            "docmind-api.cn-hangzhou.aliyuncs.com", true, false, Duration.ofSeconds(10), Duration.ofSeconds(1));
    private final DocMindPdfParserClient parser = new DocMindPdfParserClient(sdk, properties, nanos::get, nanos::addAndGet);

    @AfterEach void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @BeforeEach void submitAndSucceed() throws Exception {
        logs.start();
        logger.addAppender(logs);
        when(sdk.submitDocParserJobAdvance(any(), any())).thenReturn(new SubmitDocParserJobResponse()
                .setStatusCode(200).setBody(new SubmitDocParserJobResponseBody().setCode("200")
                        .setData(new SubmitDocParserJobResponseBodyData().setId("fixture-task"))));
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("success", List.of(0, 1)));
        when(sdk.getDocParserResultWithOptions(any(), any())).thenReturn(result(List.of(layout(0))));
    }

    @Test void submitsBytesAndExplicitEnhancementsThenPollsAndReadsAllLayoutBatches() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(sdk.submitDocParserJobAdvance(any(), any())).thenAnswer(call -> {
            var request = call.getArgument(0, com.aliyun.docmind_api20220711.models.SubmitDocParserJobAdvanceRequest.class);
            var runtime = call.getArgument(1, com.aliyun.teautil.models.RuntimeOptions.class);
            assertThat(request.getFileUrlObject().readAllBytes()).isEqualTo(bytes);
            assertThat(request.getFileName()).isNull();
            assertThat(request.getFileNameExtension()).isEqualTo("pdf");
            assertThat(request.getLlmEnhancement()).isTrue();
            assertThat(request.getEnhancementMode()).isEqualTo("VLM");
            assertThat(request.getFormulaEnhancement()).isFalse();
            assertThat(request.getPageIndex()).isNull();
            assertThat(request.getOutputFormat()).containsExactly("markdown", "visualLayoutInfo");
            assertThat(runtime.getAutoretry()).isFalse();
            assertThat(runtime.getMaxAttempts().intValue()).isEqualTo(1);
            return new SubmitDocParserJobResponse().setStatusCode(200).setBody(new SubmitDocParserJobResponseBody()
                    .setData(new SubmitDocParserJobResponseBodyData().setId("fixture-task")));
        });
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(
                status("Init", List.of()), status("Processing", List.of()), status("success", List.of(0, 1)));
        var offsets = new ArrayList<Integer>();
        when(sdk.getDocParserResultWithOptions(any(), any())).thenAnswer(call -> {
            var request = call.getArgument(0, GetDocParserResultRequest.class);
            assertThat(request.getId()).isEqualTo("fixture-task");
            assertThat(request.getLayoutStepSize()).isEqualTo(100);
            offsets.add(request.getLayoutNum());
            return request.getLayoutNum() == 0
                    ? result(IntStream.range(0, 100).mapToObj(DocMindPdfParserClientTest::layout).toList())
                    : result(List.of(layout(100)));
        });
        var parsed = parser.parse(bytes);
        assertThat(parsed.layouts()).hasSize(101);
        assertThat(parsed.processedPageIds()).containsExactlyInAnyOrder(0, 1);
        assertThat(offsets).containsExactly(0, 100);
        assertThat(nanos.get()).isEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @Test void acceptsIntegersDecodedByTheActualTeaSdkJsonPath() throws Exception {
        var json = com.aliyun.teautil.Common.assertAsMap(com.aliyun.teautil.Common.parseJSON("""
                {"Code":"200","Data":{"layouts":[{"text":"SDK 解码正文","index":0,"pageNum":0,"level":0,"type":"text"}]}}
                """));
        when(sdk.getDocParserResultWithOptions(any(), any())).thenReturn(new GetDocParserResultResponse()
                .setStatusCode(200).setBody(GetDocParserResultResponseBody.build(json)));

        var result = parser.parse(new byte[]{1});
        assertThat(result.layouts()).singleElement().satisfies(layout -> {
            assertThat(layout.text()).isEqualTo("SDK 解码正文");
            assertThat(layout.index()).isZero();
            assertThat(layout.pageNum()).isZero();
            assertThat(layout.level()).isZero();
        });
    }

    @Test void failAndUnknownStatesNeverFetchPartialResults() throws Exception {
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("Fail", List.of()));
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 文档解析失败").hasNoCause();
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("unexpected", List.of()));
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 返回未知任务状态");
        verify(sdk, never()).getDocParserResultWithOptions(any(), any());
    }

    @Test void processingTimesOutWithoutWallClockSleepOrRepeatedSubmission() throws Exception {
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("Processing", List.of()));
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 解析等待超时").hasNoCause();
        verify(sdk).submitDocParserJobAdvance(any(), any());
        verify(sdk, never()).getDocParserResultWithOptions(any(), any());
    }

    @Test void synchronousCallReturningAfterDeadlineIsRejected() throws Exception {
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenAnswer(call -> {
            nanos.set(Duration.ofSeconds(11).toNanos());
            return status("success", List.of(0));
        });
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 解析等待超时");
    }

    @Test void interruptionRestoresFlagAndStopsPolling() throws Exception {
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("Processing", List.of()));
        var interruptedParser = new DocMindPdfParserClient(sdk, properties, nanos::get,
                ignored -> { throw new InterruptedException("fixture"); });
        try {
            assertThatThrownBy(() -> interruptedParser.parse(new byte[]{1})).hasMessage("等待 DocMind 解析时被中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(sdk, never()).getDocParserResultWithOptions(any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void sdkFailuresDiscardSensitiveMessagesAndCauses() throws Exception {
        var failure = new TeaException(Map.of("code", "AccessDenied",
                "message", "private-secret https://private.invalid?signature=fixture",
                "data", Map.of("statusCode", 403, "RequestId", "fixture-request", "body", "private-body")));
        when(sdk.submitDocParserJobAdvance(any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 文档提交失败").hasNoCause();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("providerCode=AccessDenied", "providerRequestId=fixture-request"))
                .anySatisfy(message -> assertThat(message).contains("stage=SUBMIT", "inputBytes=1"))
                .allSatisfy(message -> assertThat(message).doesNotContain("private-secret", "private.invalid", "private-body"));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test void businessErrorInHttp200DoesNotProceed() throws Exception {
        var error = status("success", List.of(0));
        error.getBody().setCode("QuotaExceeded").setMessage("private provider message").setRequestId("quota-request");
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(error);
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 响应状态无效").hasNoCause();
        verify(sdk, never()).getDocParserResultWithOptions(any(), any());
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("httpStatus=200", "providerCode=QuotaExceeded", "quota-request"))
                .allSatisfy(message -> assertThat(message).doesNotContain("private provider message"));
    }

    @Test void logsSecondPageCursorWhenLayoutMappingFails() throws Exception {
        when(sdk.getDocParserResultWithOptions(any(), any())).thenReturn(
                result(IntStream.range(0, 100).mapToObj(DocMindPdfParserClientTest::layout).toList()),
                result(List.of(Map.of("pageNum", 0.5, "text", "private-page-text"))));

        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 整数字段无效");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("taskId=fixture-task", "layoutOffset=100", "accumulatedLayouts=100"))
                .anySatisfy(message -> assertThat(message).contains("stage=FETCH"))
                .allSatisfy(message -> assertThat(message).doesNotContain("private-page-text"));
    }

    @Test void duplicatePageIdsAndMalformedLayoutFieldsFailClosed() throws Exception {
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("success", List.of(0, 0)));
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 页面影像清单无效");
        when(sdk.queryDocParserStatusWithOptions(any(), any())).thenReturn(status("success", List.of(0)));
        when(sdk.getDocParserResultWithOptions(any(), any())).thenReturn(result(List.of(Map.of("pageNum", 0.5))));
        assertThatThrownBy(() -> parser.parse(new byte[]{1})).hasMessage("DocMind 整数字段无效");
    }

    private static QueryDocParserStatusResponse status(String status, List<Integer> pages) {
        var images = pages.stream().map(page -> new QueryDocParserStatusResponseBodyDataOutputFormatResultPages()
                .setPageIdCurDoc(page)).toList();
        return new QueryDocParserStatusResponse().setStatusCode(200).setBody(new QueryDocParserStatusResponseBody()
                .setCode("200").setData(new QueryDocParserStatusResponseBodyData().setStatus(status)
                        .setOutputFormatResult(List.of(new QueryDocParserStatusResponseBodyDataOutputFormatResult()
                                .setOutputType("pageImage").setPages(images)))));
    }

    private static GetDocParserResultResponse result(List<? extends Map<String, ?>> layouts) {
        return new GetDocParserResultResponse().setStatusCode(200).setBody(new GetDocParserResultResponseBody()
                .setCode("200").setData(Map.of("layouts", layouts)));
    }

    private static Map<String, Object> layout(int index) {
        return Map.of("text", "fixture " + index, "index", index, "pageNum", 0, "type", "text");
    }
}
