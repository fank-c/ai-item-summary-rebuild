package com.item.aisummary.application.file.port;

import java.io.IOException;
import java.io.InputStream;

/**
 * 同一份上传内容可重新打开；调用方必须保证两次读取之间内容不变。
 * 服务先读完整内容校验大小并计算摘要，再重开流上传，不缓存整个文件。
 */
public interface UploadSource {
    String originalName();
    String mime();
    /** 仅用于提前拒绝明显无效输入，保存和上传时以实际读取的大小为准。 */
    long declaredSize();
    /** 每次返回独立可读的流，使用者负责关闭。 */
    InputStream openStream() throws IOException;
}
