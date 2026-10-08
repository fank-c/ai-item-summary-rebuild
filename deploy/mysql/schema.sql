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
