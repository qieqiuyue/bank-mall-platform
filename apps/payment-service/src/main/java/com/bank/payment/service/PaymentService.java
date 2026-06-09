package com.bank.payment.service;

import com.bank.payment.client.AccountClient;
import com.bank.payment.client.NotificationClient;
import com.bank.payment.metrics.PaymentMetrics;
import com.bank.payment.dto.AccountServiceResponse;
import com.bank.payment.dto.PaymentRequest;
import com.bank.payment.dto.PaymentResponse;
import com.bank.payment.entity.Payment;
import com.bank.payment.entity.PaymentTransaction;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ErrorCode;
import com.bank.payment.repository.PaymentRepository;
import com.bank.payment.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String MALL_SETTLEMENT = "MALL-SETTLEMENT";
    private static final int MAX_REVERSE_RETRY = 3;

    private final PaymentRepository paymentRepo;
    private final PaymentTransactionRepository txnRepo;
    private final AccountClient accountClient;
    private final NotificationClient notificationClient;
    private final PaymentMetrics metrics;

    public PaymentService(PaymentRepository paymentRepo,
                          PaymentTransactionRepository txnRepo,
                          AccountClient accountClient,
                          NotificationClient notificationClient,
                          PaymentMetrics metrics) {
        this.paymentRepo = paymentRepo;
        this.txnRepo = txnRepo;
        this.accountClient = accountClient;
        this.notificationClient = notificationClient;
        this.metrics = metrics;
    }

    public PaymentResponse getPayment(String paymentNo) {
        Payment p = paymentRepo.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment not found: " + paymentNo));
        return PaymentResponse.from(p);
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest req) {
        long start = System.currentTimeMillis();

        // Idempotency check
        if (req.getIdempotencyKey() != null && !req.getIdempotencyKey().isBlank()) {
            Optional<Payment> existing = paymentRepo.findByIdempotencyKey(req.getIdempotencyKey());
            if (existing.isPresent()) {
                Payment p = existing.get();
                String status = p.getStatus();
                if ("COMPLETED".equals(status) || "FAILED".equals(status)
                        || "ERROR_MANUAL_REVIEW".equals(status)) {
                    log.info("Idempotent request: returning existing payment {}", p.getPaymentNo());
                    return PaymentResponse.from(p);
                }
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                        "Payment with idempotency key " + req.getIdempotencyKey() + " is being processed");
            }
        }

        String payeeAccount = req.getPayeeAccount() != null
                && !req.getPayeeAccount().isBlank() ? req.getPayeeAccount() : MALL_SETTLEMENT;
        String idempotencyKey = req.getIdempotencyKey() != null
                ? req.getIdempotencyKey() : "AUTO-" + System.currentTimeMillis();

        Payment payment = new Payment();
        payment.setPayerAccount(req.getPayerAccount());
        payment.setPayeeAccount(payeeAccount);
        payment.setAmount(req.getAmount());
        payment.setCurrency(req.getCurrency() != null ? req.getCurrency() : "CNY");
        payment.setIdempotencyKey(idempotencyKey);
        paymentRepo.save(payment);

        boolean hasDebit = false;
        try {
            // Step 1: Debit payer
            AccountServiceResponse.TransactionData debitResp = accountClient.debit(
                    req.getPayerAccount(), req.getAmount(), idempotencyKey);
            saveTxn(payment, debitResp.getTransactionNo(), "ACCOUNT", "DEBIT", "SUCCESS");
            hasDebit = true;

            // Step 2: Credit payee (mall settlement account)
            AccountServiceResponse.TransactionData creditResp = accountClient.credit(
                    payeeAccount, req.getAmount(), idempotencyKey);
            saveTxn(payment, creditResp.getTransactionNo(), "ACCOUNT", "CREDIT", "SUCCESS");

            payment.setStatus("COMPLETED");
            log.info("Payment {} completed: {} debit, {} credit, amount={}",
                    payment.getPaymentNo(), req.getPayerAccount(), payeeAccount, req.getAmount());

        } catch (Exception e) {
            if (hasDebit) {
                // Compensation: debit succeeded but credit failed → reverse the debit
                AccountServiceResponse.TransactionData revResp = reverseWithRetry(req.getPayerAccount(), req.getAmount(), idempotencyKey);
                if (revResp != null) {
                    saveTxn(payment, revResp.getTransactionNo(), "ACCOUNT", "REVERSAL", "SUCCESS");
                    payment.setStatus("FAILED");
                    payment.setFailReason("Credit to " + payeeAccount + " failed. Reversal successful. "
                            + "Original error: " + e.getMessage());
                } else {
                    payment.setStatus("ERROR_MANUAL_REVIEW");
                    payment.setFailReason("Debit succeeded but credit+reverse both failed for "
                            + req.getPayerAccount() + ". Credit error: " + e.getMessage()
                            + ". Reverse retried " + MAX_REVERSE_RETRY + " times and failed.");
                    log.error("Payment {} ERROR_MANUAL_REVIEW: debit OK, credit+reverse both failed",
                            payment.getPaymentNo());
                }
            } else {
                payment.setStatus("FAILED");
                payment.setFailReason(e.getMessage());
            }
        }

        // Step 3: Notify — non-blocking, failure does not roll back payment
        try {
            notificationClient.send(req.getPayerAccount(), "PAYMENT_SUCCESS",
                    "Payment of " + req.getAmount() + " " + payment.getCurrency() + " processed.");
        } catch (Exception ex) {
            log.warn("Notification failed for payment {}: {}", payment.getPaymentNo(), ex.getMessage());
        }

        paymentRepo.save(payment);
        metrics.recordPayment(payment.getStatus());
        metrics.recordDuration(System.currentTimeMillis() - start);
        return PaymentResponse.from(payment);
    }

    /** Manual 3-retry loop with exponential backoff — no spring-retry dependency. Returns TransactionData on success, null on exhaustion. */
    private AccountServiceResponse.TransactionData reverseWithRetry(String accountNo, BigDecimal amount, String idempotencyKey) {
        for (int attempt = 0; attempt < MAX_REVERSE_RETRY; attempt++) {
            try {
                AccountServiceResponse.TransactionData revResp = accountClient.reverse(
                        accountNo, "debit-" + idempotencyKey, idempotencyKey);
                log.info("Reverse attempt {}/{} succeeded: txn={}", attempt + 1, MAX_REVERSE_RETRY,
                        revResp.getTransactionNo());
                return revResp;
            } catch (Exception e) {
                if (attempt == MAX_REVERSE_RETRY - 1) {
                    log.error("Reverse exhausted after {} attempts for account {}",
                            MAX_REVERSE_RETRY, accountNo, e);
                    return null;
                }
                // Exponential backoff: 50ms, 200ms, 800ms
                long backoffMs = (long) (50 * Math.pow(4, attempt));
                log.warn("Reverse attempt {}/{} failed, retrying in {}ms...", attempt + 1, MAX_REVERSE_RETRY, backoffMs);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
        }
        return null;
    }

    private void saveTxn(Payment payment, String transactionNo, String serviceName,
                         String direction, String status) {
        txnRepo.save(new PaymentTransaction(payment, transactionNo, serviceName, direction, status));
    }
}
