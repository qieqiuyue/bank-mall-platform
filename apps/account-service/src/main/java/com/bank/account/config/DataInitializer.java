package com.bank.account.config;

import com.bank.account.entity.Account;
import com.bank.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    @Profile("dev")
    CommandLineRunner initAccounts(AccountRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                try {
                    repo.save(new Account("A1001", "U1001", "SAVING", "ACTIVE",
                            new BigDecimal("8888.88")));
                    repo.save(new Account("A1002", "U1002", "SAVING", "ACTIVE",
                            new BigDecimal("50000.00")));
                    repo.save(new Account("MALL-SETTLEMENT", "SYSTEM", "SETTLEMENT", "ACTIVE",
                            BigDecimal.ZERO));
                    log.info("[account-service] Seeded 3 demo accounts (incl. settlement).");
                } catch (DataIntegrityViolationException e) {
                    log.info("[account-service] Seed data already exists (race-safe), skipping.");
                }
            }
        };
    }
}
