package com.item.aisummary.application.knowledge.processing;

import com.item.aisummary.application.knowledge.port.KnowledgeChunker;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentParser;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析策略与分块策略的路由注册表。
 *
 * <p>维护已注册的 {@link KnowledgeDocumentParser} 与 {@link KnowledgeChunker} 策略映射：
 * <ul>
 *   <li>解析策略按 {@link FileFormat}（TXT/MD/PDF/DOCX）精确注册与查找。</li>
 *   <li>分块策略按 {@link ChunkingMode}（FLAT/PARENT_CHILD）精确注册与查找。</li>
 * </ul>
 *
 * <p>业务原则：
 * <ul>
 *   <li>解析与切分两个维度的策略完全解耦，不将文件格式与分块策略绑定，支持任意格式根据需求复用分块策略。</li>
 *   <li>严禁隐式回退或重复注册；请求未注册的策略时立即抛出显式异常拒绝，不装配不可靠的默认逻辑。</li>
 * </ul>
 */
public final class KnowledgeProcessingRouter {
    private final Map<FileFormat, KnowledgeDocumentParser> parsers;
    private final Map<ChunkingMode, KnowledgeChunker> chunkers;

    /**
     * 构造策略路由注册表并校验注册合法性。
     *
     * @param parsers 已注册的文档解析策略列表
     * @param chunkers 已注册的内容分块策略列表
     * @throws IllegalArgumentException 当策略列表包含 null、缺少对应枚举或存在重复注册时抛出
     */
    public KnowledgeProcessingRouter(
            List<KnowledgeDocumentParser> parsers, List<KnowledgeChunker> chunkers) {
        if (parsers == null || chunkers == null) {
            throw new IllegalArgumentException("策略注册列表不能为空");
        }
        var parserMap = new EnumMap<FileFormat, KnowledgeDocumentParser>(FileFormat.class);
        for (var parser : parsers) {
            if (parser == null || parser.format() == null || parserMap.putIfAbsent(parser.format(), parser) != null) {
                throw new IllegalArgumentException("解析策略缺失格式或重复注册");
            }
        }
        var chunkerMap = new EnumMap<ChunkingMode, KnowledgeChunker>(ChunkingMode.class);
        for (var chunker : chunkers) {
            if (chunker == null || chunker.mode() == null || chunkerMap.putIfAbsent(chunker.mode(), chunker) != null) {
                throw new IllegalArgumentException("分块策略缺失模式或重复注册");
            }
        }
        this.parsers = Map.copyOf(parserMap);
        this.chunkers = Map.copyOf(chunkerMap);
    }

    /** 是否已注册至少一个解析策略。 */
    public boolean hasParsers() { return !parsers.isEmpty(); }

    /** 是否已注册至少一个分块策略。 */
    public boolean hasChunkers() { return !chunkers.isEmpty(); }

    /**
     * 获取指定文件格式的解析策略。
     *
     * @param format 源文件格式
     * @return 对应的文档解析策略
     * @throws IllegalStateException 当该格式未注册解析策略时抛出
     */
    public KnowledgeDocumentParser requireParser(FileFormat format) {
        var parser = format == null ? null : parsers.get(format);
        if (parser == null) throw new IllegalStateException("源格式没有已注册的解析策略");
        return parser;
    }

    /**
     * 获取指定分块模式的分块策略。
     *
     * @param mode 内容分块模式
     * @return 对应的分块策略
     * @throws IllegalStateException 当该模式未注册分块策略时抛出
     */
    public KnowledgeChunker requireChunker(ChunkingMode mode) {
        var chunker = mode == null ? null : chunkers.get(mode);
        if (chunker == null) throw new IllegalStateException("分块模式没有已注册的分块策略");
        return chunker;
    }
}
