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
