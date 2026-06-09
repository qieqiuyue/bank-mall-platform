package com.bank.auth.config;

import com.bank.auth.entity.User;
import com.bank.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class DataInitializer {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    @Profile("dev")
    CommandLineRunner initUsers(UserRepository repo, BCryptPasswordEncoder encoder) {
        return args -> {
            if (repo.count() == 0) {
                try {
                    repo.save(new User("admin", encoder.encode("123456"), "U1001",
                            "Demo Bank Customer", "GOLD", "LOW", "CUSTOMER,MALL_USER"));
                    repo.save(new User("vip01", encoder.encode("vip123"), "U1002",
                            "VIP Customer Alpha", "PLATINUM", "LOW", "CUSTOMER,VIP,MALL_USER"));
                    repo.save(new User("tester", encoder.encode("test123"), "U1003",
                            "QA Tester", "SILVER", "MEDIUM", "CUSTOMER,TESTER"));
                    log.info("[auth-service] Seeded 3 default users (BCrypt encoded).");
                } catch (DataIntegrityViolationException e) {
                    log.info("[auth-service] Seed data already exists (race-safe), skipping.");
                }
            }
        };
    }
}
