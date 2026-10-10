package com.item.aisummary.application.file.port;

import java.io.InputStream;
import java.time.Duration;

/** 四项对象存储操作；上传流由调用者关闭，读取流的所有权交给调用者。 */
public interface ObjectStorageClient {
    /**
     * 上传二进制对象到指定 Bucket。
     *
     * <p>size 和 sha256 来自完整读取后的实际内容；同步上传结束后由调用者关闭流。
     *
     * @param bucket 目标存储桶名称
     * @param key 对象在存储桶内的唯一 Key
     * @param mime 对象的 MIME 类型
     * @param size 实际文件字节大小
     * @param sha256 实际文件内容的十六进制 SHA-256 摘要
     * @param stream 二进制内容输入流
     */
    void putObject(
            String bucket, String key, String mime, long size, String sha256, InputStream stream);

    /**
     * 打开存储对象的二进制内容流。
     *
     * <p>返回原始内容流；调用者关闭流时释放关联的底层 SDK 网络响应资源。
     *
     * @param bucket 目标存储桶名称
     * @param key 对象在存储桶内的唯一 Key
     * @return 对象的二进制输入流
     */
    InputStream openObject(String bucket, String key);

    /**
     * 为对象生成临时的 HTTP GET 预签名下载 URL。
     *
     * <p>不探测远端对象是否存在；地址持有者在有效期内可直接下载。
     *
     * @param bucket 目标存储桶名称
     * @param key 对象在存储桶内的唯一 Key
     * @param expiration 签名 URL 的有效时长
     * @return 预签名的下载 URL 字符串
     */
    String presignGetObject(String bucket, String key, Duration expiration);

    /**
     * 从存储桶中删除指定对象。
     *
     * <p>仅负责远端存储的删除，不更新数据库，也不承诺分布式事务原子性。
     *
     * @param bucket 目标存储桶名称
     * @param key 待删除对象的唯一 Key
     */
    void deleteObject(String bucket, String key);
}
