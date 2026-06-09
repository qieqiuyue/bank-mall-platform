package com.bank.account.service;

import com.bank.account.dto.*;
import com.bank.account.entity.Account;
import com.bank.account.entity.Transaction;
import com.bank.account.entity.TransactionType;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ErrorCode;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_RETRY = 3;
    private static final DateTimeFormatter TXN_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final java.util.concurrent.ThreadLocalRandom RNG = java.util.concurrent.ThreadLocalRandom.current();

    private final AccountRepository accountRepo;
    private final TransactionRepository txnRepo;

    private final com.bank.account.metrics.AccountMetrics metrics;

    // S4 Chaos Engineering: configurable delay for slow-call fault injection
    @Value("${account.chaos.debit-delay-ms:0}")
    private long debitDelayMs;

    public AccountService(AccountRepository accountRepo, TransactionRepository txnRepo,
                          com.bank.account.metrics.AccountMetrics metrics) {
        this.accountRepo = accountRepo;
        this.txnRepo = txnRepo;
        this.metrics = metrics;
    }

    public AccountResponse getAccount(String accountNo) {
        Account account = findAccount(accountNo);
        return AccountResponse.from(account);
    }

    public BigDecimal getBalance(String accountNo) {
        return findAccount(accountNo).getBalance();
    }

    public org.springframework.data.domain.Page<TransactionResponse> getTransactions(String accountNo,
            org.springframework.data.domain.Pageable pageable) {
        findAccount(accountNo); // validate existence
        return txnRepo.findByAccountNoOrderByCreatedAtDesc(accountNo, pageable)
                .map(TransactionResponse::from);
    }

    public TransactionResponse debit(String accountNo, DebitRequest req) {
        validateAmount(req.getAmount());
        checkIdempotency(req.getIdempotencyKey());
        TransactionResponse resp = withRetry(() -> doDebit(accountNo, req));
        metrics.recordDebit();
        return resp;
    }

    public TransactionResponse credit(String accountNo, CreditRequest req) {
        validateAmount(req.getAmount());
        checkIdempotency(req.getIdempotencyKey());
        TransactionResponse resp = withRetry(() -> doCredit(accountNo, req));
        metrics.recordCredit();
        return resp;
    }

    public TransactionResponse reverse(String accountNo, ReverseRequest req) {
        checkIdempotency(req.getIdempotencyKey());

        Transaction original = txnRepo.findById(req.getOriginalTransactionNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction not found: " + req.getOriginalTransactionNo()));

        if (!original.getAccountNo().equals(accountNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Transaction does not belong to account " + accountNo);
        }
        if (original.getType() == TransactionType.REVERSAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Cannot reverse a reversal transaction: " + req.getOriginalTransactionNo());
        }

        // Reverse the original transaction: debit→credit, credit→debit
        // Uses REVERSAL type so the transaction is correctly marked
        BigDecimal reverseAmount = original.getAmount();
        TransactionResponse resp;
        if (original.getType() == TransactionType.DEBIT) {
            resp = withRetry(() ->
                    doCreditWithType(accountNo, toCreditForReverse(reverseAmount, original, req),
                            TransactionType.REVERSAL));
        } else {
            resp = withRetry(() ->
                    doDebitWithType(accountNo, toDebitForReverse(reverseAmount, original, req),
                            TransactionType.REVERSAL));
        }
        metrics.recordReverse();
        return resp;
    }

    // --- Internal operations ---

    @Transactional
    protected TransactionResponse doDebit(String accountNo, DebitRequest req) {
        return doDebitWithType(accountNo, req, TransactionType.DEBIT);
    }

    @Transactional
    protected TransactionResponse doCredit(String accountNo, CreditRequest req) {
        return doCreditWithType(accountNo, req, TransactionType.CREDIT);
    }

    private TransactionResponse doDebitWithType(String accountNo, DebitRequest req, TransactionType type) {
        // S4 Chaos Engineering: inject configurable delay for slow-call fault injection
        if (debitDelayMs > 0) {
            try {
                Thread.sleep(debitDelayMs);
                log.info("[CHAOS] Injected delay of {}ms for debit on account {}", debitDelayMs, accountNo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[CHAOS] Delay interrupted for account {}", accountNo);
            }
        }

        Account account = findAccount(accountNo);
        if (account.getBalance().compareTo(req.getAmount()) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE,
                    "Insufficient balance: available " + account.getBalance() + ", required " + req.getAmount());
        }
        BigDecimal before = account.getBalance();
        account.setBalance(before.subtract(req.getAmount()));
        accountRepo.save(account);

        Transaction txn = new Transaction(generateTxnNo(), accountNo, type,
                req.getAmount(), before, account.getBalance(),
                req.getReferenceType(), req.getReferenceId(), req.getIdempotencyKey());
        txnRepo.save(txn);

        log.info("{} {} amount={} balance {}→{}", type, accountNo, req.getAmount(), before, account.getBalance());
        return TransactionResponse.from(txn);
    }

    private TransactionResponse doCreditWithType(String accountNo, CreditRequest req, TransactionType type) {
        Account account = findAccount(accountNo);
        BigDecimal before = account.getBalance();
        account.setBalance(before.add(req.getAmount()));
        accountRepo.save(account);

        Transaction txn = new Transaction(generateTxnNo(), accountNo, type,
                req.getAmount(), before, account.getBalance(),
                req.getReferenceType(), req.getReferenceId(), req.getIdempotencyKey());
        txnRepo.save(txn);

        log.info("{} {} amount={} balance {}→{}", type, accountNo, req.getAmount(), before, account.getBalance());
        return TransactionResponse.from(txn);
    }

    // --- Helpers ---

    private Account findAccount(String accountNo) {
        return accountRepo.findByAccountNo(accountNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "Account not found: " + accountNo));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "Amount must be positive");
        }
    }

    private void checkIdempotency(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "idempotencyKey is required");
        }
        Optional<Transaction> existing = txnRepo.findByIdempotencyKey(key);
        existing.ifPresent(txn -> {
            throw new BusinessException(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY,
                    "Duplicate request. Original transaction: " + txn.getTransactionNo());
        });
    }

    private <T> T withRetry(java.util.function.Supplier<T> operation) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                return operation.get();
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_RETRY - 1) {
                    throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                            "Optimistic lock conflict after " + MAX_RETRY + " retries");
                }
                // Exponential backoff: 20ms, 80ms, 320ms
                long backoffMs = (long) (20 * Math.pow(4, attempt));
                log.warn("Optimistic lock retry {}/{} — backing off {}ms", attempt + 1, MAX_RETRY, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Retry interrupted");
                }
            }
        }
        throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Retry exhausted");
    }

    /** Generates a unique transaction number with nanosecond + random suffix to prevent collision. */
    private String generateTxnNo() {
        return "TXN" + LocalDateTime.now().format(TXN_FMT)
                + String.format("%09d", java.time.LocalTime.now().getNano())
                + String.format("%04d", RNG.nextInt(10000));
    }

    private CreditRequest toCreditForReverse(BigDecimal amount, Transaction original, ReverseRequest req) {
        CreditRequest c = new CreditRequest();
        c.setAmount(amount);
        c.setReferenceType("REVERSAL");
        c.setReferenceId(original.getTransactionNo());
        c.setIdempotencyKey(req.getIdempotencyKey());
        return c;
    }

    private DebitRequest toDebitForReverse(BigDecimal amount, Transaction original, ReverseRequest req) {
        DebitRequest d = new DebitRequest();
        d.setAmount(amount);
        d.setReferenceType("REVERSAL");
        d.setReferenceId(original.getTransactionNo());
        d.setIdempotencyKey(req.getIdempotencyKey());
        return d;
    }
}
