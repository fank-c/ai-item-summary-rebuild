package com.item.aisummary.application.file.port;

import java.io.InputStream;
import java.time.Duration;

/** 四项对象存储操作；上传流由调用者关闭，读取流的所有权交给调用者。 */
public interface ObjectStorageClient {
    /** size 和 sha256 来自完整读取后的实际内容；同步上传结束后由调用者关闭流。 */
    void putObject(String bucket, String key, String mime, long size, String sha256, InputStream stream);
    /** 返回原始内容流；调用者关闭流时也应释放关联的 SDK 响应资源。 */
    InputStream openObject(String bucket, String key);
    /** 签发 GET 临时凭证，不核对远端对象；地址持有者可以在有效期内使用。 */
    String presignGetObject(String bucket, String key, Duration expiration);
    /** 只负责远端删除，不更新数据库，也不承诺跨系统原子性。 */
    void deleteObject(String bucket, String key);
}
