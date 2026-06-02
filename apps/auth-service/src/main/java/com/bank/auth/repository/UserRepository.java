package com.bank.auth.repository;

import com.bank.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByUserId(String userId);

    boolean existsByUsername(String username);
}
