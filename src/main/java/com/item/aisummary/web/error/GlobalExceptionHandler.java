package com.item.aisummary.web.error;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildUnavailableException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.file.exception.FileNotFoundException;
import com.item.aisummary.domain.file.exception.FileValidationException;
import com.item.aisummary.domain.user.exception.UserNotFoundException;
import com.item.aisummary.infrastructure.web.RequestIdFilter;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 公共 HTTP 错误边界，只向调用方提供稳定错误码和安全的错误说明。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 Spring MVC 请求体校验失败异常（如 @Valid 校验不通过）。
     *
     * <p>返回 HTTP 400 状态码，错误码为 REQUEST_VALIDATION_ERROR，
     * 并在 ProblemDetail 的 errors 属性中携带各个字段的具体错误信息。
     *
     * @param exception 方法参数校验异常
     * @param request 当前 HTTP 请求
     * @return 包含 400 状态与字段校验错误列表的 ProblemDetail 响应实体
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "REQUEST_VALIDATION_ERROR",
                        "请求参数错误",
                        "请求参数校验失败",
                        request);
        problem.setProperty(
                "errors",
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Map.of(
                                                "field",
                                                error.getField(),
                                                "message",
                                                error.getDefaultMessage() == null
                                                        ? "参数不合法"
                                                        : error.getDefaultMessage()))
                        .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 处理文件业务规则校验异常（如扩展名不支持、MIME 超长、文件名为空等）。
     *
     * <p>返回 HTTP 400 状态码，错误码为 FILE_VALIDATION_ERROR。
     *
     * @param exception 文件校验异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应实体
     */
    @ExceptionHandler(FileValidationException.class)
    public ResponseEntity<ProblemDetail> fileValidation(
            FileValidationException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "FILE_VALIDATION_ERROR",
                "文件参数错误",
                exception.getMessage(),
                request);
    }

    /**
     * 处理上传文件超出大小限制异常。
     *
     * <p>返回 HTTP 413 Payload Too Large，错误码为 FILE_SIZE_LIMIT_EXCEEDED。
     *
     * @param exception 上传超限异常
     * @param request 当前 HTTP 请求
     * @return 413 错误响应实体
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONTENT_TOO_LARGE,
                "FILE_SIZE_LIMIT_EXCEEDED",
                "文件超过大小限制",
                "上传请求超过大小限制",
                request);
    }

    /**
     * 处理上传 Multipart 请求解析失败异常。
     *
     * <p>返回 HTTP 400 状态码，错误码为 FILE_VALIDATION_ERROR。
     *
     * @param exception Multipart 解析异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应实体
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> multipart(
            MultipartException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", "文件参数错误", "上传请求格式不正确", request);
    }

    /**
     * 处理远端对象存储（MinIO 或 OSS）操作失败异常。
     *
     * <p>返回 HTTP 502 Bad Gateway，错误码为 OBJECT_STORAGE_UNAVAILABLE，隐藏底层具体厂商和网络细节。
     *
     * @param exception 对象存储适配层异常
     * @param request 当前 HTTP 请求
     * @return 502 错误响应实体
     */
    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<ProblemDetail> objectStorage(
            ObjectStorageException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_GATEWAY,
                "OBJECT_STORAGE_UNAVAILABLE",
                "对象存储不可用",
                "对象存储暂时不可用",
                request);
    }

    /** 知识库登记重复或构建互斥等业务冲突；保留稳定冲突码，不展开数据库原因。 */
    @ExceptionHandler(KnowledgeConflictException.class)
    public ResponseEntity<ProblemDetail> knowledgeConflict(
            KnowledgeConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.code(), "知识库操作冲突", exception.getMessage(), request);
    }

    /** RAG 关闭或所需解析、切分策略未启用；请求尚未进入版本构建。 */
    @ExceptionHandler(KnowledgeBuildUnavailableException.class)
    public ResponseEntity<ProblemDetail> knowledgeUnavailable(
            KnowledgeBuildUnavailableException exception, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, exception.code(), "知识库构建不可用", exception.getMessage(), request);
    }

    /**
     * 将构建阶段失败转换为 HTTP 错误；只使用阶段固定摘要，不回显 cause 或策略异常正文。
     *
     * <p>读取和向量后端失败返回 502，解析和切分拒绝输入返回 422，持久化和发布失败返回 500。
     * cleanupCompleted 仅表示失败版本的派生向量清理已被确认；发布结果不明时仍需查询核对。
     */
    @ExceptionHandler(KnowledgeBuildException.class)
    public ResponseEntity<ProblemDetail> knowledgeBuild(
            KnowledgeBuildException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.stage()) {
            case SOURCE_READ, VECTOR_WRITE -> HttpStatus.BAD_GATEWAY;
            case PARSE, CHUNK -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CHUNK_SAVE, PUBLISH -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        ProblemDetail problem = problem(status, exception.code(), "知识库构建失败",
                exception.stage().safeMessage(), request);
        problem.setProperty("stage", exception.stage().name());
        problem.setProperty("cleanupCompleted", exception.cleanupCompleted());
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * 处理常规参数非法异常。
     *
     * <p>返回 HTTP 400 状态码，错误码为 REQUEST_VALIDATION_ERROR。
     *
     * @param exception 参数非法异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应实体
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> argument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "REQUEST_VALIDATION_ERROR",
                "请求参数错误",
                exception.getMessage(),
                request);
    }

    /**
     * 处理 Bean 验证约束违反异常。
     *
     * <p>返回 HTTP 400 状态码，错误码为 REQUEST_VALIDATION_ERROR。
     *
     * @param exception 约束违反异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应实体
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> constraint(
            ConstraintViolationException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_ERROR", "请求参数错误", "请求参数校验失败", request);
    }

    /**
     * 处理 HTTP 请求体无法读取或 JSON 反序列化失败异常。
     *
     * <p>返回 HTTP 400 状态码，错误码为 REQUEST_BODY_INVALID。
     *
     * @param exception 请求体不可读异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应实体
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> body(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求体错误", "请求体格式不正确", request);
    }

    /**
     * 处理用户、文件、知识库资源及 Spring MVC 静态资源未找到异常。
     *
     * <p>统一返回 HTTP 404 Not Found，错误码为 RESOURCE_NOT_FOUND。
     *
     * @param exception 资源未找到异常
     * @param request 当前 HTTP 请求
     * @return 404 错误响应实体
     */
    @ExceptionHandler({
        UserNotFoundException.class,
        FileNotFoundException.class,
        KnowledgeNotFoundException.class,
        NoResourceFoundException.class
    })
    public ResponseEntity<ProblemDetail> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "请求资源不存在", request);
    }

    /**
     * 处理缺少身份凭据异常。
     *
     * <p>当接口要求登录身份但未提供有效上下文时触发，返回 HTTP 401 Unauthorized，错误码为 AUTHENTICATION_REQUIRED。
     *
     * @param exception 身份缺失异常
     * @param request 当前 HTTP 请求
     * @return 401 错误响应实体
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> authentication(
            AuthenticationRequiredException exception, HttpServletRequest request) {
        return response(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "需要身份",
                exception.getMessage(),
                request);
    }

    /**
     * 处理 HTTP 请求方法不支持异常。
     *
     * <p>返回 HTTP 405 Method Not Allowed，保留框架原有的 Allow 响应头。
     *
     * @param exception 请求方法不支持异常
     * @param request 当前 HTTP 请求
     * @return 405 错误响应实体
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(
                        problem(
                                HttpStatus.METHOD_NOT_ALLOWED,
                                "METHOD_NOT_ALLOWED",
                                "请求方法不支持",
                                "该路径不支持此请求方法",
                                request));
    }

    /**
     * 处理请求 Content-Type 媒体类型不支持异常。
     *
     * <p>返回 HTTP 415 Unsupported Media Type，保留框架原有的 Accept 响应头。
     *
     * @param exception 媒体类型不支持异常
     * @param request 当前 HTTP 请求
     * @return 415 错误响应实体
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(exception.getHeaders())
                .body(
                        problem(
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                "UNSUPPORTED_MEDIA_TYPE",
                                "请求媒体类型不支持",
                                "请求的 Content-Type 不受支持",
                                request));
    }

    /**
     * 处理所有未显式捕获的内部未知异常。
     *
     * <p>记录异常类型与请求关联 ID 到日志，但不向调用方暴露异常细节或堆栈信息；统一返回 HTTP 500 INTERNAL_ERROR。
     *
     * @param exception 未预期的内部异常
     * @param request 当前 HTTP 请求
     * @return 500 错误响应实体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(
            Exception exception, HttpServletRequest request) {
        // 数据库或解析异常可能包含原始值；日志只记录异常类型和请求标识。
        LOGGER.error(
                "请求内部错误: requestId={}, exception={}",
                request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE),
                exception.getClass().getName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", "服务器内部错误", request);
    }

    /**
     * 构造带有标准状态码和 ProblemDetail 响应体的 ResponseEntity。
     *
     * @param status HTTP 状态码
     * @param code 业务错误码字符串
     * @param title 错误标题描述
     * @param detail 错误详细说明
     * @param request 当前 HTTP 请求，用于填充 instance 路径和 requestId 属性
     * @return 封装了 ProblemDetail 的 ResponseEntity
     */
    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(problem(status, code, title, detail, request));
    }

    /**
     * 根据 RFC 7807 规范组装统一的 ProblemDetail 异常信息对象。
     *
     * @param status HTTP 状态码
     * @param code 业务错误码（如 FILE_VALIDATION_ERROR）
     * @param title 错误简短描述
     * @param detail 错误具体原因
     * @param request 当前 HTTP 请求
     * @return 填充了 type, title, status, detail, instance, code, requestId 的 ProblemDetail 对象
     */
    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(
                URI.create("urn:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);

        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (requestId instanceof String id) {
            problem.setProperty("requestId", id);
        }
        return problem;
    }
}
