package com.bank.account.dto;

import java.math.BigDecimal;

public class DebitRequest {
    private BigDecimal amount;
    private String referenceType;
    private String referenceId;
    private String idempotencyKey;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String t) { this.referenceType = t; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String id) { this.referenceId = id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String key) { this.idempotencyKey = key; }
}
