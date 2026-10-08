package com.item.aisummary.application.file.port;

/** 外部存储失败合同；HTTP 边界不暴露厂商异常和凭据。 */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message) { super(message); }
    public ObjectStorageException(String message, Throwable cause) { super(message, cause); }
}
