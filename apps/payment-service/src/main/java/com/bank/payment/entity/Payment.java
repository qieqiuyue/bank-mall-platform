package com.bank.payment.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", unique = true, nullable = false, length = 36)
    private String paymentNo;

    @Column(name = "payer_account", nullable = false, length = 20)
    private String payerAccount;

    @Column(name = "payee_account", nullable = false, length = 20)
    private String payeeAccount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "fail_reason", length = 1024)
    private String failReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Payment() {
        this.paymentNo = UUID.randomUUID().toString();
        this.status = "PENDING";
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String no) { this.paymentNo = no; }
    public String getPayerAccount() { return payerAccount; }
    public void setPayerAccount(String a) { this.payerAccount = a; }
    public String getPayeeAccount() { return payeeAccount; }
    public void setPayeeAccount(String a) { this.payeeAccount = a; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getCurrency() { return currency; }
    public void setCurrency(String c) { this.currency = c; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String key) { this.idempotencyKey = key; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String r) { this.failReason = r; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
