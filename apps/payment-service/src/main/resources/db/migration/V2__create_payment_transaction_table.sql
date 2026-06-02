CREATE TABLE payment_transactions (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    payment_id       BIGINT       NOT NULL,
    transaction_no   VARCHAR(32)  DEFAULT NULL,
    service_name     VARCHAR(32)  DEFAULT NULL,
    direction        VARCHAR(10)  DEFAULT NULL,
    status           VARCHAR(20)  DEFAULT NULL,
    created_at       DATETIME     NOT NULL,
    INDEX idx_payment_id (payment_id),
    CONSTRAINT fk_pt_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
