package com.bank.account.dto;

import jakarta.validation.constraints.NotBlank;

public class ReverseRequest {
    @NotBlank(message = "originalTransactionNo is required")
    private String originalTransactionNo;
    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    public String getOriginalTransactionNo() { return originalTransactionNo; }
    public void setOriginalTransactionNo(String txn) { this.originalTransactionNo = txn; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String key) { this.idempotencyKey = key; }
}
