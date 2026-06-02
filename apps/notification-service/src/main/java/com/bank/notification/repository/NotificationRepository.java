package com.bank.notification.repository;

import com.bank.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByAccountNoOrderByCreatedAtDesc(String accountNo);
}
