package com.item.aisummary.application.knowledge.processing;

import java.util.List;

/**
 * 分块组草稿模型，作为数据库批次保存的最小关系单元。
 *
 * <p>以“组”结构天然表达片段的两层上下文关系：
 * <ul>
 *   <li>平铺模式：{@code context} 为 null，{@code retrievals} 包含一个或多个独立的检索块。</li>
 *   <li>父子模式：{@code context} 为上下文父块草稿，{@code retrievals} 为隶属于该父块的检索子片段列表。</li>
 * </ul>
 *
 * <p>批次原子性与保存规则：
 * <ul>
 *   <li>一个分块组内部的父子关系在持久化时不可拆开为两次事务，确保不会出现指向不存在父块的孤立子块。</li>
 *   <li>所有角色（父与子）均占有全局自增序号，先保存父块再保存子块。</li>
 * </ul>
 *
 * @param context 可选的上下文父块草稿（CONTEXT 角色）；平铺模式或无父组时为 null
 * @param retrievals 检索块草稿列表（RETRIEVAL 角色），必须非空且包含至少一个元素
 */
public record ChunkGroupDraft(ChunkDraft context, List<ChunkDraft> retrievals) {
    public ChunkGroupDraft {
        if (retrievals == null || retrievals.isEmpty()
                || retrievals.stream().anyMatch(chunk -> chunk == null)) {
            throw new IllegalArgumentException("分块组必须包含检索片段");
        }
        retrievals = List.copyOf(retrievals);
    }

    /**
     * 获取本组所包含的片段总行数（包含父块与全部检索子块）。
     *
     * @return 该组的片段行数总和
     */
    public int chunkCount() {
        return Math.addExact(retrievals.size(), context == null ? 0 : 1);
    }

    /**
     * 避免输出完整正文，只记录是否有父块和检索子块数量。
     */
    @Override public String toString() {
        return "ChunkGroupDraft[hasContext=" + (context != null)
                + ", retrievalCount=" + retrievals.size() + "]";
    }
}
