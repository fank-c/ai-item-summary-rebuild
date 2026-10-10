package com.item.aisummary.domain.file.exception;

/** 文件输入违反名称、MIME、Key 或大小规则时抛出的业务异常。 */
public class FileValidationException extends RuntimeException {

    /**
     * 构造文件规则校验失败异常。
     *
     * @param message 校验失败详细原因
     */
    public FileValidationException(String message) {
        super(message);
    }
}
