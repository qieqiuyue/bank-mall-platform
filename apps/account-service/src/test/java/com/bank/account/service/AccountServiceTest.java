package com.bank.account.service;

import com.bank.account.dto.*;
import com.bank.account.entity.Account;
import com.bank.account.entity.Transaction;
import com.bank.account.entity.TransactionType;
import com.bank.account.exception.BusinessException;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepo;
    @Mock TransactionRepository txnRepo;
    @InjectMocks AccountService accountService;

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account("A1001", "U1001", "SAVING", "ACTIVE", new BigDecimal("1000.00"));
    }

    @Test
    void debit_success() {
        when(accountRepo.findByAccountNo("A1001")).thenReturn(Optional.of(account));
        when(txnRepo.findByIdempotencyKey("KEY001")).thenReturn(Optional.empty());
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DebitRequest req = debitReq("100.00", "KEY001");
        TransactionResponse resp = accountService.debit("A1001", req);

        assertEquals("DEBIT", resp.getType());
        assertEquals(new BigDecimal("100.00"), resp.getAmount());
        assertEquals(new BigDecimal("900.00"), account.getBalance());
    }

    @Test
    void debit_insufficientBalance() {
        when(accountRepo.findByAccountNo("A1001")).thenReturn(Optional.of(account));
        when(txnRepo.findByIdempotencyKey("KEY002")).thenReturn(Optional.empty());

        DebitRequest req = debitReq("2000.00", "KEY002");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.debit("A1001", req));
        assertEquals("INSUFFICIENT_BALANCE", ex.getErrorCode().name());
    }

    @Test
    void debit_accountNotFound() {
        when(accountRepo.findByAccountNo("GHOST")).thenReturn(Optional.empty());
        when(txnRepo.findByIdempotencyKey("KEY003")).thenReturn(Optional.empty());

        DebitRequest req = debitReq("100.00", "KEY003");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.debit("GHOST", req));
        assertEquals("ACCOUNT_NOT_FOUND", ex.getErrorCode().name());
    }

    @Test
    void debit_invalidAmount() {
        when(txnRepo.findByIdempotencyKey("KEY004")).thenReturn(Optional.empty());

        DebitRequest req = debitReq("-50.00", "KEY004");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.debit("A1001", req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode().name());
    }

    @Test
    void debit_idempotency() {
        when(txnRepo.findByIdempotencyKey("KEY005")).thenReturn(Optional.of(
                new Transaction("TXN001", "A1001", TransactionType.DEBIT,
                        new BigDecimal("100.00"), new BigDecimal("1000.00"),
                        new BigDecimal("900.00"), null, null, "KEY005")));

        DebitRequest req = debitReq("100.00", "KEY005");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.debit("A1001", req));
        assertEquals("DUPLICATE_IDEMPOTENCY_KEY", ex.getErrorCode().name());
    }

    @Test
    void credit_success() {
        when(accountRepo.findByAccountNo("A1001")).thenReturn(Optional.of(account));
        when(txnRepo.findByIdempotencyKey("KEY010")).thenReturn(Optional.empty());
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreditRequest req = creditReq("500.00", "KEY010");
        TransactionResponse resp = accountService.credit("A1001", req);

        assertEquals("CREDIT", resp.getType());
        assertEquals(new BigDecimal("1500.00"), account.getBalance());
    }

    @Test
    void reverse_success() {
        Transaction original = new Transaction("TXN010", "A1001", TransactionType.DEBIT,
                new BigDecimal("200.00"), new BigDecimal("1000.00"),
                new BigDecimal("800.00"), "PAYMENT", "PAY001", null);
        when(txnRepo.findById("TXN010")).thenReturn(Optional.of(original));
        when(accountRepo.findByAccountNo("A1001")).thenReturn(Optional.of(account));
        when(txnRepo.findByIdempotencyKey("KEY020")).thenReturn(Optional.empty());
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReverseRequest req = new ReverseRequest();
        req.setOriginalTransactionNo("TXN010");
        req.setIdempotencyKey("KEY020");
        TransactionResponse resp = accountService.reverse("A1001", req);

        assertEquals("REVERSAL", resp.getType());
    }

    @Test
    void reverse_alreadyReversed() {
        Transaction original = new Transaction("TXN011", "A1001", TransactionType.REVERSAL,
                new BigDecimal("200.00"), new BigDecimal("800.00"),
                new BigDecimal("1000.00"), null, null, null);
        when(txnRepo.findById("TXN011")).thenReturn(Optional.of(original));
        when(txnRepo.findByIdempotencyKey("KEY021")).thenReturn(Optional.empty());

        ReverseRequest req = new ReverseRequest();
        req.setOriginalTransactionNo("TXN011");
        req.setIdempotencyKey("KEY021");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.reverse("A1001", req));
        assertEquals("INVALID_REQUEST", ex.getErrorCode().name());
    }

    @Test
    void getAccount_notFound() {
        when(accountRepo.findByAccountNo("GHOST")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.getAccount("GHOST"));
        assertEquals("ACCOUNT_NOT_FOUND", ex.getErrorCode().name());
    }

    @Test
    void getAccount_success() {
        when(accountRepo.findByAccountNo("A1001")).thenReturn(Optional.of(account));
        AccountResponse resp = accountService.getAccount("A1001");
        assertEquals("A1001", resp.getAccountNo());
        assertEquals(new BigDecimal("1000.00"), resp.getBalance());
    }

    // --- helpers ---

    private DebitRequest debitReq(String amount, String key) {
        DebitRequest r = new DebitRequest();
        r.setAmount(new BigDecimal(amount));
        r.setReferenceType("PAYMENT");
        r.setReferenceId("PAY001");
        r.setIdempotencyKey(key);
        return r;
    }

    private CreditRequest creditReq(String amount, String key) {
        CreditRequest r = new CreditRequest();
        r.setAmount(new BigDecimal(amount));
        r.setReferenceType("REFUND");
        r.setReferenceId("REF001");
        r.setIdempotencyKey(key);
        return r;
    }
}
