package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Token 预算与断句标点回退的区间切分器。
 *
 * <p>核心算法与切分回退阶梯设计：
 * <ul>
 *   <li>纯区间运算：只在局部字符坐标上计算切片起止索引 {@link Range}，不重新序列化正文文本，也不猜测全文绝对位置。</li>
 *   <li>预算控制：每次以 {@code maxTokens} 为硬上限，使用 BPE 计算可容纳的最长前缀上限（{@code cap}）；严格防范单字符无法放入预算的情况。</li>
 *   <li>层级降级断句（边界优先阶梯）：
 *     <ol>
 *       <li>段落换行（{@code \n\n}）：优先在自然段落空行处截断。</li>
 *       <li>强标点句末（{@code 。？！?!} 与不构成数字/域名的英文句号 {@code .}）。</li>
 *       <li>次级分隔符（单换行 {@code \n} 或分号、逗号等普通标点）。</li>
 *       <li>空白字符（空格等）。</li>
 *       <li>预算安全截断：如果找不到以上任何分隔符，直接在 Token 预算上限（{@code cap}）处截断，并保证不拆分 Unicode 代理对。</li>
 *     </ol>
 *   </li>
 *   <li>短尾保留：低于 {@code minChars} 软阈值时不强制截断，但绝不丢弃文档末尾的合法短正文内容。</li>
 *   <li>正推进与重叠窗口：支持 {@code overlap} 重叠回退；每次切片必须向前推进覆盖新内容（{@code covered}），重叠部分绝不能挤掉新正文。</li>
 * </ul>
 */
final class TokenRangeSplitter {
    private final JTokkitTokenBudget tokens;

    /**
     * 构造区间切分器。
     *
     * @param tokens 本地 Token 预算计算组件
     */
    TokenRangeSplitter(JTokkitTokenBudget tokens) { this.tokens = tokens; }

    /**
     * 将输入文本在 [from, to) 范围内按 Token 预算切分为一组区间列表。
     *
     * @param text 输入文本
     * @param from 起始字符索引
     * @param to 结束字符索引
     * @param maxTokens 单个区间的最大 Token 预算上限
     * @param overlap 区间重叠目标 Token 尺寸
     * @param minChars 标点断句的短块软字符数阈值
     * @param punctuation 断句标点符号集合
     * @param lineOnly 是否仅允许按换行切分（代码/表格结构块）
     * @param maxRanges 最大允许生成的区间总数预算
     * @return 切分后的有效区间列表
     * @throws IllegalArgumentException 当 Token 预算无法容纳单字符或总切片数超限时抛出
     */
    List<Range> split(String text, int from, int to, int maxTokens, int overlap,
            int minChars, String punctuation, boolean lineOnly, int maxRanges) {
        var ranges = new ArrayList<Range>();
        int start = from;
        int covered = from;
        while (covered < to) {
            // 避免重复编码整个剩余文档：有效 UTF-8 文本每码点至少消费一个字节，候选窗口限定为预算的 16 倍 UTF-16 单位。
            // 这只是保守工作窗，不作为 Token/字符换算；最终始终由 tokenizer 校验。
            int windowEnd = (int) Math.min(to, (long) start + Math.max(64L, (long) maxTokens * 16));
            windowEnd = safeEnd(text, windowEnd);
            int cap = start + tokens.prefixLength(text.substring(start, windowEnd), maxTokens);
            if (cap <= start) {
                throw new IllegalArgumentException("Token 预算不足以容纳一个完整 Unicode 字符");
            }
            // 重叠不能挤掉新正文；放不下新内容时从上一个覆盖终点重新开始。
            if (cap <= covered && start < covered) { start = covered; continue; }
            int end = cap == to ? cap : chooseBoundary(text, start, cap, covered, minChars, punctuation, lineOnly);
            end = fit(text, start, end, maxTokens);
            if (end <= covered && start < covered) { start = covered; continue; }
            if (end <= start) throw new IllegalArgumentException("Token 预算不足以容纳一个完整 Unicode 字符");
            String content = text.substring(start, end);
            if (!content.isBlank()) {
                if (ranges.size() >= maxRanges) throw new IllegalArgumentException("总片段数超过构建限制");
                ranges.add(new Range(start, end));
            }
            covered = end;
            if (covered == to) break;
            start = overlap == 0 || content.isBlank() ? end : overlapStart(text, start, end, overlap);
        }
        return List.copyOf(ranges);
    }

    /**
     * 二次精校终点字符位置，确保在 BPE 编码下该子串不超过 Token 预算上限。
     */
    private int fit(String text, int start, int end, int maxTokens) {
        // 截短也可能改变 BPE 的末尾编码；重新检查，不假设前缀 Token 数严格单调。
        while (end > start && tokens.count(text.substring(start, end)) > maxTokens) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return end;
    }

    /**
     * 计算下一区间的重叠起始位置。
     * 在上一个区间的尾部有界范围内使用二分查找探测满足重叠 Token 预算的起点。
     */
    private int overlapStart(String text, int previousStart, int end, int budget) {
        // 只探索有界的连续后缀，避免每个码点都重新执行 BPE 导致近似平方成本。
        int low = (int) Math.max((long) previousStart + Character.charCount(text.codePointAt(previousStart)),
                (long) end - Math.max(64L, (long) budget * 16));
        if (low < end && Character.isLowSurrogate(text.charAt(low))) low++;
        int high = end - 1;
        int best = end;
        while (low <= high) {
            int candidate = low + ((high - low) >>> 1);
            if (Character.isLowSurrogate(text.charAt(candidate))) candidate--;
            if (candidate < low) { low++; continue; }
            if (tokens.count(text.substring(candidate, end)) <= budget) {
                best = candidate;
                high = candidate - 1;
            } else {
                low = candidate + 1;
            }
        }
        // BPE 后缀计数不保证严格单调；best 始终来自实际通过校验的候选，不保证最大重叠。
        return best;
    }

    /**
     * 按回退阶梯选择最佳断句边界（段落 -> 强句末 -> 次级分隔符 -> 空白 -> 预算上限）。
     */
    private static int chooseBoundary(String text, int start, int cap, int covered,
            int minChars, String punctuation, boolean lineOnly) {
        int paragraph = -1;
        int sentence = -1;
        int secondary = -1;
        int whitespace = -1;
        int firstContent = start;
        while (firstContent < cap && Character.isWhitespace(text.charAt(firstContent))) firstContent++;
        for (int i = start; i < cap; ) {
            int position = i;
            int character = text.codePointAt(position);
            int end = position + Character.charCount(character);
            i = end;
            if (end <= covered || end <= firstContent || end - start < minChars) continue;
            if (character == '\n') {
                secondary = end;
                if (!lineOnly && position > start && text.charAt(position - 1) == '\n') paragraph = end;
            } else if (!lineOnly && punctuation.indexOf(character) >= 0) {
                // 避免把数字小数点、域名/缩写中的点当作强句末；这只是确定性的候选规则。
                if (character == '.' && position > start && end < text.length()
                        && Character.isLetterOrDigit(text.codePointBefore(position)) && Character.isLetterOrDigit(text.codePointAt(end))) continue;
                if ("。？！?!".indexOf(character) >= 0 || character == '.') sentence = end;
                else secondary = end;
            } else if (!lineOnly && Character.isWhitespace(character)) whitespace = end;
        }
        if (paragraph > 0) return paragraph;
        if (sentence > 0) return sentence;
        if (secondary > 0) return secondary;
        return whitespace > 0 ? whitespace : cap;
    }

    /**
     * 确保结束索引不拆分 Unicode 高低代理对。
     */
    private static int safeEnd(String text, int end) {
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) return end - 1;
        return end;
    }

    /** 局部字符半开区间记录。 */
    record Range(int start, int end) {}
}
