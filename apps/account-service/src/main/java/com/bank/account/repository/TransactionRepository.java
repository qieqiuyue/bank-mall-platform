package com.bank.account.repository;

import com.bank.account.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByAccountNoOrderByCreatedAtDesc(String accountNo);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Optional<Transaction> findByReferenceIdAndReferenceType(String referenceId, String referenceType);
}
