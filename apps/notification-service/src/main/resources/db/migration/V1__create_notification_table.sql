CREATE TABLE notifications (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    notification_no  VARCHAR(36)  NOT NULL,
    account_no       VARCHAR(20)  DEFAULT NULL,
    type             VARCHAR(20)  DEFAULT NULL,
    title            VARCHAR(128) DEFAULT NULL,
    content          VARCHAR(512) DEFAULT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at       DATETIME     NOT NULL,
    UNIQUE KEY uk_notification_no (notification_no),
    INDEX idx_account_no (account_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
