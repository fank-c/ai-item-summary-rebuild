package com.item.aisummary.application.file.content;

/** 处理前查询的最小元数据，不包含内容流；打开内容时仍需重新校验归属与状态。 */
public record ProcessableFileAsset(
        /** 文件原始展示名称。 */
        String originName,
        /** 规范化后的 MIME 类型。 */
        String mime) {}
