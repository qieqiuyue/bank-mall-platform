package com.bank.account.repository;

import com.bank.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {
    Optional<Account> findByAccountNo(String accountNo);
}
