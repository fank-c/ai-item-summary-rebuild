package com.item.aisummary.application.file;

/** 供知识库等下游使用，后台任务也必须显式传入用户 ID。 */
public interface FileContentService {
    ProcessableFileAsset requireOwnedProcessable(String actorUserId, String fileId);
    FileContentResource openForProcessing(String actorUserId, String fileId);
}
