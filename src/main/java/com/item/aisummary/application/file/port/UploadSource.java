package com.item.aisummary.application.file.port;

import java.io.IOException;
import java.io.InputStream;

/** 同一份上传内容可重新打开；调用方必须保证两次读取之间内容不变。 服务先读完整内容校验大小并计算摘要，再重开流上传，不缓存整个文件。 */
public interface UploadSource {
    /**
     * 上传时的原始文件名，用于展示和扩展名校验。
     *
     * @return 原始文件名
     */
    String originalName();

    /**
     * 客户端声明的 MIME 类型；服务会去除参数并统一大小写。
     *
     * @return 声明的 MIME 类型
     */
    String mime();

    /**
     * 客户端声明的文件字节大小；仅用于提前拒绝明显无效输入。
     *
     * @return 声明的文件大小
     */
    long declaredSize();

    /**
     * 打开用于读取文件内容的独立输入流。
     *
     * <p>每次调用返回独立可读的流，调用方负责关闭。
     *
     * @return 二进制输入流
     * @throws IOException 流打开异常
     */
    InputStream openStream() throws IOException;
}
