package com.bank.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public class PaymentRequest {
    @NotBlank(message = "payerAccount is required")
    private String payerAccount;
    private String payeeAccount;
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    private BigDecimal amount;
    private String currency;
    private String orderId;
    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    public String getPayerAccount() { return payerAccount; }
    public void setPayerAccount(String a) { this.payerAccount = a; }
    public String getPayeeAccount() { return payeeAccount; }
    public void setPayeeAccount(String a) { this.payeeAccount = a; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public String getCurrency() { return currency; }
    public void setCurrency(String c) { this.currency = c; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String id) { this.orderId = id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String key) { this.idempotencyKey = key; }
}
