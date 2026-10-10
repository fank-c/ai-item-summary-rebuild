package com.item.aisummary.application.file.content;

/** 处理前查询的最小元数据，不包含内容流；后续打开内容时仍需重新校验归属与状态。 */
public record ProcessableFileAsset(String originName, String mime) { }
