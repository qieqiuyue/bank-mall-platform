CREATE TABLE transactions (
    transaction_no   VARCHAR(32)     NOT NULL PRIMARY KEY,
    account_no       VARCHAR(20)     NOT NULL,
    type             VARCHAR(10)     NOT NULL,
    amount           DECIMAL(18,2)   NOT NULL,
    balance_before   DECIMAL(18,2)   DEFAULT NULL,
    balance_after    DECIMAL(18,2)   DEFAULT NULL,
    reference_type   VARCHAR(32)     DEFAULT NULL,
    reference_id     VARCHAR(64)     DEFAULT NULL,
    idempotency_key  VARCHAR(128)    DEFAULT NULL,
    created_at       DATETIME        NOT NULL,
    UNIQUE KEY uk_idempotency (idempotency_key),
    INDEX idx_account_no (account_no),
    INDEX idx_created_at (created_at),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_no) REFERENCES accounts(account_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
