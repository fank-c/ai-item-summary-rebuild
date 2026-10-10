package com.item.aisummary.application.file.content;

/**
 * 供知识库等下游使用，后台任务也必须显式传入用户 ID。
 * 两个入口都重新检查归属与状态，不依赖 HTTP 请求上下文。
 */
public interface FileContentService {
    /** 只查询名称和 MIME，不打开远端内容；不保证与后续读取构成原子快照。 */
    ProcessableFileAsset requireOwnedProcessable(String actorUserId, String fileId);
    /** 返回元数据与原始流，调用者必须用 try-with-resources 关闭资源。 */
    FileContentResource openForProcessing(String actorUserId, String fileId);
}
