package com.bank.account.dto;

import com.bank.account.entity.Account;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AccountResponse {
    private String accountNo;
    private String userId;
    private String accountType;
    private String status;
    private BigDecimal balance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AccountResponse from(Account a) {
        AccountResponse r = new AccountResponse();
        r.accountNo = a.getAccountNo();
        r.userId = a.getUserId();
        r.accountType = a.getAccountType();
        r.status = a.getStatus();
        r.balance = a.getBalance();
        r.createdAt = a.getCreatedAt();
        r.updatedAt = a.getUpdatedAt();
        return r;
    }

    public String getAccountNo() { return accountNo; }
    public String getUserId() { return userId; }
    public String getAccountType() { return accountType; }
    public String getStatus() { return status; }
    public BigDecimal getBalance() { return balance; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
