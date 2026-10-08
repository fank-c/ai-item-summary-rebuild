package com.item.aisummary.application.file.port;

import java.io.IOException;
import java.io.InputStream;

/** 同一份上传内容可重新打开；调用方必须保证两次读取之间内容不变。 */
public interface UploadSource {
    String originalName();
    String mime();
    long declaredSize();
    InputStream openStream() throws IOException;
}
