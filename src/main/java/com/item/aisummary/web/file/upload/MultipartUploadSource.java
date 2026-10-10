package com.item.aisummary.web.file.upload;

import com.item.aisummary.application.file.port.UploadSource;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/** 将可重开 multipart 临时文件适配到应用输入合同。 必须在当前请求内完成两次读取，不能把该来源交给请求结束后的后台任务。 */
public record MultipartUploadSource(
        /** 当前 HTTP 请求中的 multipart 文件；应用服务会在请求内重开两次读取。 */
        MultipartFile file) implements UploadSource {
    /** 获取客户端上传时的原始文件名。 */
    @Override
    public String originalName() {
        return file.getOriginalFilename();
    }

    /** 获取客户端声明的内容 MIME 类型。 */
    @Override
    public String mime() {
        return file.getContentType();
    }

    /** 获取客户端声明的输入字节大小。 */
    @Override
    public long declaredSize() {
        return file.getSize();
    }

    /**
     * 打开上传文件的内容输入流。
     *
     * <p>在同一个 HTTP 请求生命周期内支持重复打开读取。
     *
     * @return 文件的二进制输入流
     * @throws IOException 流打开异常
     */
    @Override
    public InputStream openStream() throws IOException {
        return file.getInputStream();
    }
}
