package com.item.aisummary.infrastructure.knowledge.config;

import com.item.aisummary.application.knowledge.processing.ChunkingOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 知识库 RAG 内部构建与切分选项的配置属性映射类。
 *
 * <p>绑定前缀 {@code app.knowledge.rag} 下的各项运行参数。
 *
 * <p>字段含义与约束规则：
 * <ul>
 *   <li>{@code enabled}：总开关，默认 {@code false}；开启后才触发 {@link KnowledgeRagConfiguration} 条件装配。</li>
 *   <li>{@code batchSize}：数据库分组追加与向量缓冲批量推送的目标大小（范围 1 到 1024，默认 128）。</li>
 *   <li>{@code chunkSize}：切分策略的目标尺寸阈值（必须为正数，默认 512）。</li>
 *   <li>{@code minChunkSizeChars}：标点符号截断的最小字符数（非负，默认 200）。</li>
 *   <li>{@code minChunkLengthToEmbed}：允许向量化的最小字符长度（首版严格限制为 0，防止静默丢弃短文本）。</li>
 *   <li>{@code maxNumChunks}：单个文档允许生成的最大片段总数，包含 CONTEXT 与 RETRIEVAL（正整数，默认 100000）。</li>
 *   <li>{@code keepSeparator}：切分时是否保留标点符号，传给切分策略（默认 true）。</li>
 *   <li>{@code punctuationMarks}：用于句子断句的中英文标点与换行符集合（非空，默认 {@code .?!;。？！；\n}）。</li>
 *   <li>{@code maxSourceSize}：源文件允许的最大字节大小（必须为正且小于 Integer.MAX_VALUE 字节，默认 20MB）。</li>
 * </ul>
 */
@ConfigurationProperties("app.knowledge.rag")
public record KnowledgeRagProperties(
        /** 是否启用知识库 RAG 构建装配。 */
        @DefaultValue("false") boolean enabled,
        /** 每次保存或写入向量的目标批量大小。 */
        @DefaultValue("128") int batchSize,
        /** 切分策略可用的目标尺寸。 */
        @DefaultValue("512") int chunkSize,
        /** 切分策略可用的最小尺寸阈值。 */
        @DefaultValue("200") int minChunkSizeChars,
        /** 最小向量文本长度；当前仅允许零，避免静默丢弃短块。 */
        @DefaultValue("0") int minChunkLengthToEmbed,
        /** 单次文档允许的最大切片数量（全部角色）。 */
        @DefaultValue("100000") int maxNumChunks,
        /** 是否由具体策略保留分隔符。 */
        @DefaultValue("true") boolean keepSeparator,
        /** 提供给具体策略的句末与换行分隔符。 */
        @DefaultValue(".?!;。？！；\n") String punctuationMarks,
        /** 原始文件读取的最大字节数。 */
        @DefaultValue("20MB") DataSize maxSourceSize) {

    public KnowledgeRagProperties {
        if (batchSize < 1 || batchSize > 1024) {
            throw new IllegalArgumentException("RAG 批次大小必须为 1 到 1024");
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("RAG 切片尺寸必须为正数");
        }
        if (minChunkSizeChars < 0) {
            throw new IllegalArgumentException("RAG 最小切片尺寸不能为负数");
        }
        if (minChunkLengthToEmbed != 0) {
            throw new IllegalArgumentException("RAG 最小向量文本长度当前只能为 0");
        }
        if (maxNumChunks < 1) {
            throw new IllegalArgumentException("RAG 最大切片数必须为正数");
        }
        if (punctuationMarks == null || punctuationMarks.isEmpty()) {
            throw new IllegalArgumentException("RAG 分隔符不能为空");
        }
        if (maxSourceSize == null
                || maxSourceSize.toBytes() < 1
                || maxSourceSize.toBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("RAG 源文件大小必须为正且小于 Integer.MAX_VALUE 字节");
        }
    }

    /** 构造传递给策略的只读参数快照。 */
    public ChunkingOptions chunkingOptions() {
        return new ChunkingOptions(
                chunkSize,
                minChunkSizeChars,
                minChunkLengthToEmbed,
                maxNumChunks,
                keepSeparator,
                punctuationMarks);
    }
}
