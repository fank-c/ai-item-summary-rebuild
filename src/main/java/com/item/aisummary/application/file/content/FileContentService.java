package com.item.aisummary.application.file.content;

/** 供知识库等下游使用，后台任务也必须显式传入用户 ID。 两个入口都重新检查归属与状态，不依赖 HTTP 请求上下文。 */
public interface FileContentService {
    /**
     * 校验文件存在、归属与正常状态，并获取其元数据。
     *
     * <p>仅查询数据库元数据，不打开远端存储流。校验调用者所有权及文件可处理状态（未删除、status=1、uploadStatus=completed）。
     *
     * @param actorUserId 调用方用户 ID
     * @param fileId 文件唯一标识
     * @return 包含文件名称与 MIME 的可处理文件元数据
     */
    ProcessableFileAsset requireOwnedProcessable(String actorUserId, String fileId);

    /**
     * 校验文件归属与状态，并打开远端对象存储的二进制内容流。
     *
     * <p>返回包含文件元数据与输入流的包装资源，调用方必须通过 try-with-resources 关闭该资源。
     *
     * @param actorUserId 调用方用户 ID
     * @param fileId 文件唯一标识
     * @return 包含流和元数据的文件内容资源，实现 AutoCloseable
     */
    FileContentResource openForProcessing(String actorUserId, String fileId);
}
