package com.item.aisummary.domain.file.exception;

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException() { super("文件不存在"); }
}
