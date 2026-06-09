package com.bank.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public class CreditRequest {
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    private BigDecimal amount;
    private String referenceType;
    private String referenceId;
    @NotBlank(message = "idempotencyKey is required")
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
