package com.bank.payment.service;

import com.bank.payment.client.AccountClient;
import com.bank.payment.dto.AccountServiceResponse;
import com.bank.payment.dto.PaymentRequest;
import com.bank.payment.dto.PaymentResponse;
import com.bank.payment.entity.Payment;
import com.bank.payment.exception.BusinessException;
import com.bank.payment.repository.PaymentRepository;
import com.bank.payment.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepo;
    @Mock PaymentTransactionRepository txnRepo;
    @Mock AccountClient accountClient;
    @Mock com.bank.payment.client.NotificationClient notificationClient;
    @InjectMocks PaymentService paymentService;

    private PaymentRequest req;
    private AccountServiceResponse.TransactionData mockData;

    @BeforeEach
    void setUp() {
        req = new PaymentRequest();
        req.setPayerAccount("A1001");
        req.setPayeeAccount("MALL-SETTLEMENT");
        req.setAmount(new BigDecimal("299.00"));
        req.setCurrency("CNY");
        req.setIdempotencyKey("KEY-001");

        mockData = new AccountServiceResponse.TransactionData();
        mockData.setTransactionNo("TXN-TEST-001");
        mockData.setAccountNo("A1001");
        mockData.setType("DEBIT");
        mockData.setAmount(new BigDecimal("299.00"));
    }

    @Test
    void processPayment_success() {
        when(paymentRepo.findByIdempotencyKey("KEY-001")).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountClient.debit(eq("A1001"), any(), eq("KEY-001"))).thenReturn(mockData);
        when(accountClient.credit(eq("MALL-SETTLEMENT"), any(), eq("KEY-001"))).thenReturn(mockData);

        PaymentResponse resp = paymentService.processPayment(req);

        assertEquals("COMPLETED", resp.getStatus());
        assertEquals("A1001", resp.getPayerAccount());
        assertEquals(new BigDecimal("299.00"), resp.getAmount());
        verify(accountClient, times(1)).debit(any(), any(), any());
        verify(accountClient, times(1)).credit(any(), any(), any());
        verify(accountClient, never()).reverse(any(), any(), any());
    }

    @Test
    void processPayment_creditFails_reverseSucceeds() {
        when(paymentRepo.findByIdempotencyKey("KEY-002")).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountClient.debit(eq("A1001"), any(), eq("KEY-002"))).thenReturn(mockData);
        when(accountClient.credit(eq("MALL-SETTLEMENT"), any(), eq("KEY-002")))
                .thenThrow(new BusinessException(
                        com.bank.payment.exception.ErrorCode.ACCOUNT_SERVICE_UNAVAILABLE, "Credit failed"));
        when(accountClient.reverse(eq("A1001"), eq("debit-KEY-002"), eq("KEY-002")))
                .thenReturn(mockData);

        PaymentResponse resp = paymentService.processPayment(req("KEY-002"));

        assertEquals("FAILED", resp.getStatus());
        assertTrue(resp.getFailReason().contains("Reversal successful"));
        verify(accountClient, times(1)).debit(any(), any(), any());
        verify(accountClient, times(1)).reverse(any(), any(), any());
    }

    @Test
    void processPayment_creditFails_reverseFailsAllRetries() {
        when(paymentRepo.findByIdempotencyKey("KEY-003")).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountClient.debit(eq("A1001"), any(), eq("KEY-003"))).thenReturn(mockData);
        when(accountClient.credit(any(), any(), any()))
                .thenThrow(new RuntimeException("Credit failed"));
        when(accountClient.reverse(any(), any(), any()))
                .thenThrow(new RuntimeException("Reverse failed"));

        PaymentResponse resp = paymentService.processPayment(req("KEY-003"));

        assertEquals("ERROR_MANUAL_REVIEW", resp.getStatus());
        assertTrue(resp.getFailReason().contains("Credit error"));
        assertTrue(resp.getFailReason().contains("Reverse retried 3 times"));
        verify(accountClient, times(1)).debit(any(), any(), any());
        verify(accountClient, times(3)).reverse(any(), any(), any());
    }

    @Test
    void processPayment_debitFails() {
        when(paymentRepo.findByIdempotencyKey("KEY-004")).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountClient.debit(any(), any(), any()))
                .thenThrow(new RuntimeException("Account unavailable"));

        PaymentResponse resp = paymentService.processPayment(req("KEY-004"));

        assertEquals("FAILED", resp.getStatus());
        verify(accountClient, never()).credit(any(), any(), any());
        verify(accountClient, never()).reverse(any(), any(), any());
    }

    @Test
    void processPayment_idempotency() {
        Payment existing = new Payment();
        existing.setPaymentNo("PAY-EXISTING");
        existing.setStatus("COMPLETED");
        existing.setPayerAccount("A1001");
        existing.setPayeeAccount("MALL-SETTLEMENT");
        existing.setAmount(new BigDecimal("299.00"));
        existing.setCurrency("CNY");
        when(paymentRepo.findByIdempotencyKey("KEY-005")).thenReturn(Optional.of(existing));

        PaymentResponse resp = paymentService.processPayment(req("KEY-005"));

        assertEquals("COMPLETED", resp.getStatus());
        verify(accountClient, never()).debit(any(), any(), any());
    }

    @Test
    void processPayment_accountServiceUnavailable() {
        when(paymentRepo.findByIdempotencyKey("KEY-006")).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountClient.debit(any(), any(), any()))
                .thenThrow(new BusinessException(
                        com.bank.payment.exception.ErrorCode.ACCOUNT_SERVICE_UNAVAILABLE,
                        "Cannot reach account-service"));

        PaymentResponse resp = paymentService.processPayment(req("KEY-006"));

        assertEquals("FAILED", resp.getStatus());
        assertTrue(resp.getFailReason().contains("Cannot reach account-service"));
    }

    private PaymentRequest req(String idempotencyKey) {
        PaymentRequest r = new PaymentRequest();
        r.setPayerAccount("A1001");
        r.setPayeeAccount("MALL-SETTLEMENT");
        r.setAmount(new BigDecimal("299.00"));
        r.setCurrency("CNY");
        r.setIdempotencyKey(idempotencyKey);
        return r;
    }
}
