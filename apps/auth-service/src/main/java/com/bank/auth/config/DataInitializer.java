package com.bank.auth.config;

import com.bank.auth.entity.User;
import com.bank.auth.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initUsers(UserRepository repo, BCryptPasswordEncoder encoder) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(new User("admin", encoder.encode("123456"), "U1001",
                        "Demo Bank Customer", "GOLD", "LOW", "CUSTOMER,MALL_USER"));
                repo.save(new User("vip01", encoder.encode("vip123"), "U1002",
                        "VIP Customer Alpha", "PLATINUM", "LOW", "CUSTOMER,VIP,MALL_USER"));
                repo.save(new User("tester", encoder.encode("test123"), "U1003",
                        "QA Tester", "SILVER", "MEDIUM", "CUSTOMER,TESTER"));
                System.out.println("[auth-service] Seeded 3 default users (BCrypt encoded).");
            }
        };
    }
}
