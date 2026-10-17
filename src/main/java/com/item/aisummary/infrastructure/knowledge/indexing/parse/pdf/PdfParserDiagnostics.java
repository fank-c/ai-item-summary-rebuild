package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import com.aliyun.tea.TeaException;
import java.util.Set;

/**
 * PDF 解析链路的安全诊断摘要：保留异常类型、源码位置及受限云端标识，不输出任意异常消息或响应体。
 *
 * <p>仅替换敏感键名不足以过滤正文、签名地址或嵌套响应，因此消息只允许本地定义的固定错误提示。
 */
final class PdfParserDiagnostics {
    /** 异常链最多检查 16 层，避免循环 cause 导致诊断过程无法结束。 */
    private static final int MAX_CAUSE_DEPTH = 16;
    /** 云端任务 ID、请求 ID 和错误码的日志长度上限；超长或含非法字符时整体隐藏，不截断回显。 */
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    /** 本地解析器定义的固定错误原因；不允许把供应商返回的自由文本当作安全消息。 */
    private static final Set<String> SAFE_MESSAGES = Set.of(
            "DocMind 客户端初始化失败", "PDF 源文件不能为空", "DocMind 文档提交失败",
            "DocMind 提交响应无效", "DocMind 未返回有效任务标识", "PDF 源流关闭失败",
            "DocMind 状态查询失败", "DocMind 状态响应无效", "DocMind 文档解析失败",
            "DocMind 返回未知任务状态", "等待 DocMind 解析时被中断", "DocMind 解析等待超时",
            "DocMind 结果获取失败", "DocMind 结果响应无效", "DocMind 版面结果无效",
            "DocMind 版面数量超过解析限制", "DocMind 提取文本超过解析限制", "DocMind 未提取到有效内容",
            "DocMind 未返回页面影像清单", "DocMind 页面影像清单无效", "DocMind 响应状态无效",
            "DocMind 文本字段无效", "DocMind 整数字段无效", "PDF 源文件无效或超过解析限制",
            "PDF 源文件读取失败", "不支持加密 PDF", "PDF 页数超过解析限制", "PDF 格式无效或已损坏",
            "PDF 解析结果无效或超过解析限制", "PDF 解析结果包含空版面", "PDF 解析结果阅读顺序无效",
            "PDF 解析结果页码无效", "PDF 解析结果包含无法表示的空版面", "PDF 提取文本超过解析限制",
            "PDF 解析结果未覆盖全部页面");

    private PdfParserDiagnostics() {}

    /** 获取受限深度内的根因类型，区分 PDFBox、网络客户端或 SDK 的失败来源。 */
    static String exceptionType(Throwable exception) {
        Throwable root = rootCause(exception);
        return root == null ? "unknown" : root.getClass().getName();
    }

    /** 仅回显本地固定错误提示；不认识的消息整体隐藏，原始 cause 也不写入日志。 */
    static String exceptionMessage(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        return message != null && SAFE_MESSAGES.contains(message) ? message : "<异常消息已隐藏>";
    }

    /** 返回根因抛出位置；跳过客户端统一建异常的工厂帧，定位实际校验分支，不打印异常消息或完整异常链。 */
    static String failureLocation(Throwable exception) {
        Throwable root = rootCause(exception);
        if (root == null) {
            return "unknown";
        }
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getClassName().equals(DocMindPdfParserClient.class.getName())
                    && frame.getMethodName().equals("failure")) {
                continue;
            }
            return frame.toString();
        }
        return "unknown";
    }

    /** 云端标识仅允许有限长度的 ASCII 字母、数字、点、下划线、冒号和连字符，阻止换行和 URL 回显。 */
    static String identifier(String value) {
        if (value == null) {
            return "<未提供>";
        }
        return value.length() <= MAX_IDENTIFIER_LENGTH && value.matches("[A-Za-z0-9_.:-]+")
                ? value : "<已隐藏>";
    }

    /**
     * 从 TeaException 提取结构化错误码、HTTP 状态和请求 ID，便于向云服务追踪；不读取 message、description 或其他 data。
     */
    static String sdkDetails(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof TeaException tea) {
                Object requestId = tea.getData() == null ? null : tea.getData().get("RequestId");
                return "httpStatus=" + tea.getStatusCode() + ", providerCode=" + identifier(tea.getCode())
                        + ", providerRequestId=" + identifier(requestId instanceof String id ? id : null);
            }
        }
        return "<无云端错误字段>";
    }

    /** 沿 cause 最多前进固定层数；只返回异常引用，不读取异常消息。 */
    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && current.getCause() != null
                && current.getCause() != current && depth < MAX_CAUSE_DEPTH; depth++) {
            current = current.getCause();
        }
        return current;
    }
}
