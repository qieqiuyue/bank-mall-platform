package com.bank.account.dto;

public class ReverseRequest {
    private String originalTransactionNo;
    private String idempotencyKey;

    public String getOriginalTransactionNo() { return originalTransactionNo; }
    public void setOriginalTransactionNo(String txn) { this.originalTransactionNo = txn; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String key) { this.idempotencyKey = key; }
}
