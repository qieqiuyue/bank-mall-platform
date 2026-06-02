package com.bank.account.dto;

import com.bank.account.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionResponse {
    private String transactionNo;
    private String accountNo;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;

    public static TransactionResponse from(Transaction t) {
        TransactionResponse r = new TransactionResponse();
        r.transactionNo = t.getTransactionNo();
        r.accountNo = t.getAccountNo();
        r.type = t.getType().name();
        r.amount = t.getAmount();
        r.balanceBefore = t.getBalanceBefore();
        r.balanceAfter = t.getBalanceAfter();
        r.createdAt = t.getCreatedAt();
        return r;
    }

    public String getTransactionNo() { return transactionNo; }
    public String getAccountNo() { return accountNo; }
    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
