package com.bank.notification.repository;

import com.bank.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByAccountNoOrderByCreatedAtDesc(String accountNo, Pageable pageable);
}
