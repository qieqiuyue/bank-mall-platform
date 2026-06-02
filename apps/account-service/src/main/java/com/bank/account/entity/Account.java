package com.bank.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_no", length = 20)
    private String accountNo;

    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Account() {}

    public Account(String accountNo, String userId, String accountType,
                   String status, BigDecimal balance) {
        this.accountNo = accountNo;
        this.userId = userId;
        this.accountType = accountType;
        this.status = status;
        this.balance = balance;
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

    // Getters and setters
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String no) { this.accountNo = no; }
    public String getUserId() { return userId; }
    public void setUserId(String id) { this.userId = id; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String type) { this.accountType = type; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal b) { this.balance = b; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
