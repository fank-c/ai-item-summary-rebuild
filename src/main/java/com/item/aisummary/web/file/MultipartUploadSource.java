package com.item.aisummary.web.file;

import com.item.aisummary.application.file.port.UploadSource;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;

/** 将可重开 multipart 临时文件适配到应用输入合同。 */
record MultipartUploadSource(MultipartFile file) implements UploadSource {
    @Override public String originalName() { return file.getOriginalFilename(); }
    @Override public String mime() { return file.getContentType(); }
    @Override public long declaredSize() { return file.getSize(); }
    @Override public InputStream openStream() throws IOException { return file.getInputStream(); }
}
