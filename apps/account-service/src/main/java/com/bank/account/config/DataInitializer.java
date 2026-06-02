package com.bank.account.config;

import com.bank.account.entity.Account;
import com.bank.account.repository.AccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initAccounts(AccountRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(new Account("A1001", "U1001", "SAVING", "ACTIVE",
                        new BigDecimal("8888.88")));
                repo.save(new Account("A1002", "U1002", "SAVING", "ACTIVE",
                        new BigDecimal("50000.00")));
                repo.save(new Account("MALL-SETTLEMENT", "SYSTEM", "SETTLEMENT", "ACTIVE",
                        BigDecimal.ZERO));
                System.out.println("[account-service] Seeded 3 demo accounts (incl. settlement).");
            }
        };
    }
}
