CREATE TABLE payments (
    id               BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    payment_no       VARCHAR(36)     NOT NULL,
    payer_account    VARCHAR(20)     NOT NULL,
    payee_account    VARCHAR(20)     NOT NULL,
    amount           DECIMAL(18,2)   NOT NULL,
    currency         VARCHAR(3)      DEFAULT 'CNY',
    status           VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    idempotency_key  VARCHAR(128)    DEFAULT NULL,
    fail_reason      VARCHAR(1024)   DEFAULT NULL,
    created_at       DATETIME        NOT NULL,
    updated_at       DATETIME        DEFAULT NULL,
    UNIQUE KEY uk_payment_no (payment_no),
    UNIQUE KEY uk_idempotency (idempotency_key),
    INDEX idx_status (status),
    INDEX idx_payer (payer_account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
