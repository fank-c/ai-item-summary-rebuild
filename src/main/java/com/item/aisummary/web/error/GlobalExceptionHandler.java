package com.item.aisummary.web.error;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.domain.file.FileNotFoundException;
import com.item.aisummary.domain.file.FileValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import com.item.aisummary.domain.user.UserNotFoundException;
import com.item.aisummary.infrastructure.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.net.URI;
import java.util.Map;

/** 公共 HTTP 错误边界，只向调用方提供稳定错误码和安全的错误说明。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_ERROR", "请求参数错误", "请求参数校验失败", request);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream().map(error -> Map.of(
            "field", error.getField(), "message", error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage())).toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(FileValidationException.class)
    public ResponseEntity<ProblemDetail> fileValidation(FileValidationException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", "文件参数错误", exception.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return response(HttpStatus.CONTENT_TOO_LARGE, "FILE_SIZE_LIMIT_EXCEEDED", "文件超过大小限制", "上传请求超过大小限制", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> multipart(MultipartException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", "文件参数错误", "上传请求格式不正确", request);
    }

    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<ProblemDetail> objectStorage(ObjectStorageException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_UNAVAILABLE", "对象存储不可用", "对象存储暂时不可用", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> argument(IllegalArgumentException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_ERROR", "请求参数错误", exception.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> constraint(ConstraintViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_ERROR", "请求参数错误", "请求参数校验失败", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> body(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求体错误", "请求体格式不正确", request);
    }

    @ExceptionHandler({UserNotFoundException.class, FileNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ProblemDetail> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "请求资源不存在", request);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> authentication(AuthenticationRequiredException exception, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "需要身份", exception.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException exception,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(exception.getHeaders())
            .body(problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不支持", "该路径不支持此请求方法", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        // 数据库异常或解析异常可能包含原始值；只记录异常类型和请求标识。
        LOGGER.error("请求内部错误: requestId={}, exception={}", request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE),
            exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", "服务器内部错误", request);
    }

    private static ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String title, String detail,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status).body(problem(status, code, title, detail, request));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
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
