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
