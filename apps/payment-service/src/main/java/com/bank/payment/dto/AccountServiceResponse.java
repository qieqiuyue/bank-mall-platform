package com.bank.payment.dto;

import java.math.BigDecimal;

/** Parses the data field of account-service ApiResponse for debit/credit/reverse. */
public class AccountServiceResponse {
    private String code;
    private String message;
    private TransactionData data;

    public static class TransactionData {
        private String transactionNo;
        private String accountNo;
        private String type;
        private BigDecimal amount;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        public String getTransactionNo() { return transactionNo; }
        public void setTransactionNo(String t) { this.transactionNo = t; }
        public String getAccountNo() { return accountNo; }
        public void setAccountNo(String a) { this.accountNo = a; }
        public String getType() { return type; }
        public void setType(String t) { this.type = t; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal a) { this.amount = a; }
        public BigDecimal getBalanceBefore() { return balanceBefore; }
        public void setBalanceBefore(BigDecimal b) { this.balanceBefore = b; }
        public BigDecimal getBalanceAfter() { return balanceAfter; }
        public void setBalanceAfter(BigDecimal b) { this.balanceAfter = b; }
    }

    public String getCode() { return code; }
    public void setCode(String c) { this.code = c; }
    public String getMessage() { return message; }
    public void setMessage(String m) { this.message = m; }
    public TransactionData getData() { return data; }
    public void setData(TransactionData d) { this.data = d; }

    public boolean isSuccess() { return "SUCCESS".equals(code); }
}
