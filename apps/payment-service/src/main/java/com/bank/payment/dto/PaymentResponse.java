package com.bank.payment.dto;

import com.bank.payment.entity.Payment;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentResponse {
    private String paymentNo;
    private String status;
    private String payerAccount;
    private String payeeAccount;
    private BigDecimal amount;
    private String currency;
    private String failReason;
    private LocalDateTime paidAt;

    public static PaymentResponse from(Payment p) {
        PaymentResponse r = new PaymentResponse();
        r.paymentNo = p.getPaymentNo();
        r.status = p.getStatus();
        r.payerAccount = p.getPayerAccount();
        r.payeeAccount = p.getPayeeAccount();
        r.amount = p.getAmount();
        r.currency = p.getCurrency() != null ? p.getCurrency() : "CNY";
        r.failReason = p.getFailReason();
        r.paidAt = p.getCreatedAt();
        return r;
    }

    public String getPaymentNo() { return paymentNo; }
    public String getStatus() { return status; }
    public String getPayerAccount() { return payerAccount; }
    public String getPayeeAccount() { return payeeAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getFailReason() { return failReason; }
    public LocalDateTime getPaidAt() { return paidAt; }
}
