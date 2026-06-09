package com.bank.account.repository;

import com.bank.account.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    Page<Transaction> findByAccountNoOrderByCreatedAtDesc(String accountNo, Pageable pageable);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Optional<Transaction> findByReferenceIdAndReferenceType(String referenceId, String referenceType);
}
