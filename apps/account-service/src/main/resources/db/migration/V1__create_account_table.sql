CREATE TABLE accounts (
    account_no   VARCHAR(20)     NOT NULL PRIMARY KEY,
    user_id      VARCHAR(32)     NOT NULL,
    account_type VARCHAR(20)     NOT NULL DEFAULT 'SAVING',
    status       VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    version      BIGINT          NOT NULL DEFAULT 0,
    created_at   DATETIME        NOT NULL,
    updated_at   DATETIME        DEFAULT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
