package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.tea.TeaException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 PDF 日志摘要只保留固定原因、源码位置和受限云端标识，不输出任意异常正文。 */
class PdfParserDiagnosticsTest {
    @Test
    void hidesArbitraryMessagesButKeepsRootTypeAndLocation() {
        var root = new IllegalArgumentException("https://private.invalid?signature=fixture\n私有正文");
        var exception = new IllegalStateException("wrapper", root);

        assertThat(PdfParserDiagnostics.exceptionType(exception))
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(PdfParserDiagnostics.failureLocation(exception)).contains("PdfParserDiagnosticsTest");
        assertThat(PdfParserDiagnostics.exceptionMessage(exception)).isEqualTo("<异常消息已隐藏>");
        assertThat(PdfParserDiagnostics.exceptionMessage(root)).isEqualTo("<异常消息已隐藏>");
        assertThat(PdfParserDiagnostics.exceptionMessage(new IllegalStateException("DocMind 文档解析失败")))
                .isEqualTo("DocMind 文档解析失败");
    }

    @Test
    void onlyKeepsBoundedSingleLineIdentifiersAndStructuredSdkFields() {
        var exception = new TeaException(Map.of(
                "code", "QuotaExceeded", "message", "私有 SDK 正文",
                "data", Map.of("statusCode", 403, "RequestId", "fixture-request", "body", "私有响应")));

        assertThat(PdfParserDiagnostics.sdkDetails(new IllegalStateException(exception)))
                .isEqualTo("httpStatus=403, providerCode=QuotaExceeded, providerRequestId=fixture-request");
        assertThat(PdfParserDiagnostics.identifier("fixture-task_1")).isEqualTo("fixture-task_1");
        for (String value : new String[]{"x".repeat(129), "line\nbreak", "https://private.invalid", ""}) {
            assertThat(PdfParserDiagnostics.identifier(value)).isEqualTo("<已隐藏>");
        }
        assertThat(PdfParserDiagnostics.identifier(null)).isEqualTo("<未提供>");
    }

    @Test
    void handlesMissingAndCyclicCausesWithoutHanging() {
        var first = new IllegalStateException();
        var second = new IllegalArgumentException(first);
        first.initCause(second);

        assertThat(PdfParserDiagnostics.exceptionType(first)).isNotBlank();
        assertThat(PdfParserDiagnostics.sdkDetails(first)).isEqualTo("<无云端错误字段>");
        assertThat(PdfParserDiagnostics.exceptionType(null)).isEqualTo("unknown");
        assertThat(PdfParserDiagnostics.failureLocation(null)).isEqualTo("unknown");
    }
}
