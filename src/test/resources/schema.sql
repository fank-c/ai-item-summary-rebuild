CREATE TABLE IF NOT EXISTS `user` (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password VARCHAR(255) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMP(6) NULL,
    create_at TIMESTAMP(6) NOT NULL,
    update_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS file_asset (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    origin_name VARCHAR(255) NOT NULL,
    mime VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL,
    storage VARCHAR(512) NOT NULL,
    storage_type VARCHAR(16) NULL,
    sha256 CHAR(64) NULL,
    status INT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMP(6) NULL,
    upload_status VARCHAR(16) NULL,
    upload_expires_at TIMESTAMP(6) NULL,
    create_by VARCHAR(64) NULL,
    update_by VARCHAR(64) NULL,
    create_at TIMESTAMP(6) NOT NULL,
    update_at TIMESTAMP(6) NOT NULL
);

-- 知识库四表与正式 schema 保持字段、可空性及唯一约束一致；H2 仅用于隔离测试。
CREATE TABLE IF NOT EXISTS knowledge_base (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    create_at TIMESTAMP(6) NOT NULL,
    update_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    knowledge_base_id VARCHAR(32) NOT NULL,
    file_id VARCHAR(32) NOT NULL,
    status INT NOT NULL DEFAULT 1,
    index_status VARCHAR(16) NOT NULL,
    active_revision_id VARCHAR(32) NULL,
    last_failure_code VARCHAR(64) NULL,
    last_failure_message VARCHAR(512) NULL,
    create_at TIMESTAMP(6) NOT NULL,
    update_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_knowledge_document_base_file UNIQUE (knowledge_base_id, file_id)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_file ON knowledge_document (file_id);

CREATE TABLE IF NOT EXISTS knowledge_document_revision (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    knowledge_document_id VARCHAR(32) NOT NULL,
    revision_number INT NOT NULL,
    revision_status VARCHAR(24) NOT NULL,
    source_sha256 CHAR(64) NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    failure_stage VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    failure_message VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    published_at TIMESTAMP(6) NULL,
    cleaned_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_knowledge_revision_document_number UNIQUE (knowledge_document_id, revision_number)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_revision_document_status
    ON knowledge_document_revision (knowledge_document_id, revision_status);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    revision_id VARCHAR(32) NOT NULL,
    sequence_no INT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    heading_path JSON NULL,
    token_count INT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    page_start INT NULL,
    page_end INT NULL,
    create_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_knowledge_chunk_revision_sequence UNIQUE (revision_id, sequence_no)
);
