-- Select the database configured by DB_URL before running this script.
-- Existing IDs, password hashes, status and deletion marks must remain unchanged.
CREATE TABLE IF NOT EXISTS `user` (
    `id` VARCHAR(32) NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `email` VARCHAR(254) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `status` INT NOT NULL DEFAULT 1,
    `deleted_at` DATETIME(6) NULL DEFAULT NULL,
    `create_at` DATETIME(6) NOT NULL,
    `update_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Core file metadata. Direct-upload expiry is retained for schema compatibility, unused by this flow.
CREATE TABLE IF NOT EXISTS `file_asset` (
    `id` VARCHAR(32) NOT NULL,
    `origin_name` VARCHAR(255) NOT NULL,
    `mime` VARCHAR(255) NOT NULL,
    `size` BIGINT NOT NULL,
    `storage` VARCHAR(512) NOT NULL,
    `storage_type` VARCHAR(16) NULL,
    `sha256` CHAR(64) NULL,
    `status` INT NOT NULL DEFAULT 1,
    `deleted_at` DATETIME(6) NULL DEFAULT NULL,
    `upload_status` VARCHAR(16) NULL,
    `upload_expires_at` DATETIME(6) NULL,
    `create_by` VARCHAR(64) NULL,
    `update_by` VARCHAR(64) NULL,
    `create_at` DATETIME(6) NOT NULL,
    `update_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 知识库四张核心表，支持基础内部服务和 txt/md RAG 入库；字段说明见 docs/reference/database/knowledge.md。
-- 此脚本用于初始化，不会升级同名旧表、复制历史数据或操作向量库。时间统一采用 UTC。
CREATE TABLE IF NOT EXISTS `knowledge_base` (
    `id` VARCHAR(32) NOT NULL COMMENT '知识库 ID',
    `name` VARCHAR(100) NOT NULL COMMENT '知识库名称',
    `status` INT NOT NULL DEFAULT 1 COMMENT '1 表示可用',
    `create_at` DATETIME(6) NOT NULL,
    `update_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id` VARCHAR(32) NOT NULL COMMENT '知识文档 ID',
    `knowledge_base_id` VARCHAR(32) NOT NULL COMMENT '所属知识库 ID',
    `file_id` VARCHAR(32) NOT NULL COMMENT '已上传的 file_asset ID',
    `status` INT NOT NULL DEFAULT 1 COMMENT '文档可用状态，1 表示可用',
    `index_status` VARCHAR(16) NOT NULL COMMENT '索引状态：pending/indexed/failed/removed',
    `active_revision_id` VARCHAR(32) NULL COMMENT '当前可查询的完整版本；重建失败时保留旧值',
    `last_failure_code` VARCHAR(64) NULL COMMENT '最近一次构建失败编码',
    `last_failure_message` VARCHAR(512) NULL COMMENT '最近一次构建失败的脱敏摘要',
    `create_at` DATETIME(6) NOT NULL,
    `update_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_base_file` (`knowledge_base_id`, `file_id`),
    KEY `idx_knowledge_document_file` (`file_id`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_document_revision` (
    `id` VARCHAR(32) NOT NULL COMMENT '一次构建产生的版本 ID',
    `knowledge_document_id` VARCHAR(32) NOT NULL COMMENT '所属知识文档 ID',
    `revision_number` INT NOT NULL COMMENT '同一文档内递增的版本号',
    `revision_status` VARCHAR(24) NOT NULL COMMENT 'building/ready/failed/superseded',
    `source_sha256` CHAR(64) NULL COMMENT '本次实际读取源文件的 SHA-256',
    `chunk_count` INT NOT NULL DEFAULT 0 COMMENT '本版本上下文块与检索块的片段总数',
    `failure_stage` VARCHAR(64) NULL COMMENT '失败发生的处理阶段',
    `failure_code` VARCHAR(64) NULL COMMENT '失败编码',
    `failure_message` VARCHAR(512) NULL COMMENT '失败的脱敏摘要',
    `created_at` DATETIME(6) NOT NULL,
    `started_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL,
    `published_at` DATETIME(6) NULL COMMENT '发布为当前版本的时间',
    `cleaned_at` DATETIME(6) NULL COMMENT '向量清理完成时间，数据库片段保留',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_revision_document_number` (`knowledge_document_id`, `revision_number`),
    KEY `idx_knowledge_revision_document_status` (`knowledge_document_id`, `revision_status`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id` VARCHAR(32) NOT NULL COMMENT '片段 ID；检索块的向量关联使用同一业务标识',
    `revision_id` VARCHAR(32) NOT NULL COMMENT '所属构建版本 ID',
    `parent_chunk_id` VARCHAR(32) NULL COMMENT '同版本无父 CONTEXT 块 ID；关系由应用层校验',
    `chunk_role` VARCHAR(16) NOT NULL COMMENT '片段角色：CONTEXT 或 RETRIEVAL',
    `sequence_no` INT NOT NULL COMMENT '版本内非负全局顺序，两种角色共同占用，不表示原文位置',
    `content` MEDIUMTEXT NOT NULL COMMENT '片段正文',
    `heading_path` JSON NULL COMMENT '有序标题路径，例如 [{"level":1,"title":"说明"}]',
    `token_count` INT NULL COMMENT '所用分词器的计数；无法确定时为空',
    `start_offset` INT NULL COMMENT '处理后全文中的 UTF-16 起始偏移；无法可靠映射时为空',
    `end_offset` INT NULL COMMENT 'UTF-16 结束偏移，不包含该位置；与起始偏移同时为空或有值',
    `page_start` INT NULL COMMENT '来源起始页，从 1 开始；未知时为空',
    `page_end` INT NULL COMMENT '来源结束页，包含该页；未知时为空',
    `create_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_chunk_revision_sequence` (`revision_id`, `sequence_no`),
    KEY `idx_knowledge_chunk_revision_parent_sequence` (`revision_id`, `parent_chunk_id`, `sequence_no`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
