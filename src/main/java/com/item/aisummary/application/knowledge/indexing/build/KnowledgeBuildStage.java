package com.item.aisummary.application.knowledge.indexing.build;

/**
 * 知识库版本构建流程的内部阶段枚举。
 *
 * <p>用于精确记录构建生命周期中发生失败的具体环节，并提供全流程一致的固定安全提示文案。
 * 不映射到数据库作为持久化状态枚举，不作为公开 HTTP 错误码。
 */
public enum KnowledgeBuildStage {
    /** 阶段一：源文件原始字节读取、大小限制检查与 SHA-256 摘要核对。 */
    SOURCE_READ,
    /** 阶段二：文档格式解析，将原始字节转换为统一的结构化内容块（ParsedDocument）。 */
    PARSE,
    /** 阶段三：内容切分，将解析文档按策略规则切分为分块组草稿（ChunkGroupDraft）。 */
    CHUNK,
    /** 阶段四：片段持久化，分配业务 ID、解析两层父子关系并以组为单位写入 MySQL。 */
    CHUNK_SAVE,
    /** 阶段五：检索片段向量化，批量将已保存的 RETRIEVAL 片段写入向量数据库。 */
    VECTOR_WRITE,
    /** 阶段六：版本发布短事务，核对片段计数与文档快照，原子切换活动版本。 */
    PUBLISH;

    /**
     * 获取各阶段对应的固定安全摘要文本。
     *
     * <p>编排与生命周期短事务共用此方法生成统一的错误摘要，严禁用供应商异常消息或用户输入替换。
     *
     * @return 预置的安全中文错误摘要
     */
    public String safeMessage() {
        return switch (this) {
            case SOURCE_READ -> "源文件准备失败";
            case PARSE -> "源文件解析失败";
            case CHUNK -> "内容分块失败";
            case CHUNK_SAVE -> "知识片段保存失败";
            case VECTOR_WRITE -> "检索片段向量写入失败";
            case PUBLISH -> "知识版本发布失败，向量保留待核对";
        };
    }
}
