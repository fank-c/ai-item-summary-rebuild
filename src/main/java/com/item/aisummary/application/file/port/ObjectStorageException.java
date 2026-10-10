package com.item.aisummary.application.file.port;

/** 外部存储失败合同；HTTP 边界不暴露厂商异常和凭据。 */
public class ObjectStorageException extends RuntimeException {
    /**
     * 构造存储服务异常。
     *
     * @param message 异常描述消息
     */
    public ObjectStorageException(String message) {
        super(message);
    }

    /**
     * 构造携带底层原因的存储服务异常。
     *
     * @param message 异常描述消息
     * @param cause 底层存储 SDK 抛出的异常原因
     */
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
