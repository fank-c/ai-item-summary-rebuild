package com.item.aisummary.domain.file.exception;

/** 文件不存在、已删除、状态不符或归属不符时使用的统一异常。 */
public class FileNotFoundException extends RuntimeException {

    /** 构造文件不存在、已被逻辑删除、归属不匹配或不可处理时的统一异常。 */
    public FileNotFoundException() {
        super("文件不存在");
    }
}
