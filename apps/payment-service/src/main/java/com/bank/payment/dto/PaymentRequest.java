package com.bank.payment.dto;

import java.math.BigDecimal;

public class PaymentRequest {
    private String payerAccount;
    private String payeeAccount;
    private BigDecimal amount;
    private String currency;
    private String orderId;
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
