package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * 基于 JTokkit 的本地 CL100K_BASE 分词预算计算工具。
 *
 * <p>用于在切分策略执行时进行精确的本地 Token 预算计算与前缀长度截断：
 * <ul>
 *   <li>编码字典只在类加载时初始化一次；调用时直接使用无状态的计数与普通编码方法。</li>
 *   <li>将文档中的任何特殊 Token 字面量（如 {@code <|endoftext|>}）均作为普通字面文本处理（{@code Ordinary} 模式），不触发模型端控制逻辑，不访问外部网络。</li>
 *   <li>截断时基于 UTF-16 字符坐标，严格保护 Unicode 代理对（Surrogate Pair），防止从高低代理中间切断产生畸形字符。</li>
 * </ul>
 */
final class JTokkitTokenBudget {
    // 编码字典只加载一次；每次计数/编码的游标与输出由 JTokkit 保持在调用局部。
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /**
     * 计算给定文本在 CL100K_BASE 下的 Token 数量。
     *
     * @param text 待计数的文本
     * @return Token 数量
     */
    int count(String text) {
        return ENCODING.countTokensOrdinary(text);
    }

    /**
     * 计算在给定最大 Token 预算内能够容纳的文本前缀字符长度。
     *
     * <p>返回保守安全的 UTF-16 字符结束索引，保证不从 Unicode 代理对中间截断。
     *
     * @param text 输入文本
     * @param maxTokens 最大允许容纳的 Token 数量
     * @return 保守的 UTF-16 前缀字符长度
     */
    int prefixLength(String text, int maxTokens) {
        var result = ENCODING.encodeOrdinary(text, maxTokens);
        int end = result.getLastProcessedCharacterIndex() + 1;
        if (end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end;
    }

    /**
     * 校验文本中不存在未配对的高代理或低代理字符，防止畸形 Unicode 导致切分异常。
     *
     * @param text 待校验文本
     * @throws IllegalArgumentException 当文本包含畸形或孤立代理字符时抛出
     */
    static void requireValidUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("切分文本包含不完整的 Unicode 字符");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("切分文本包含不完整的 Unicode 字符");
            }
        }
    }
}
