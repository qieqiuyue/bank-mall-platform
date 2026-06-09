package com.bank.notification.service;

import com.bank.notification.dto.NotificationRequest;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.entity.Notification;
import com.bank.notification.metrics.NotificationMetrics;
import com.bank.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final NotificationMetrics metrics;

    public NotificationService(NotificationRepository repo, NotificationMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    @Transactional
    public NotificationResponse send(NotificationRequest req) {
        Notification n = new Notification();
        n.setAccountNo(req.getAccountNo());
        n.setType(req.getChannel() != null ? req.getChannel() : "SYSTEM");
        n.setTitle(req.getTemplate() != null ? req.getTemplate() : req.getTitle());
        n.setContent(req.getContent() != null ? req.getContent() : "Notification content");
        n.setStatus("SENT");
        repo.save(n);
        metrics.recordSent(n.getStatus());
        return NotificationResponse.from(n);
    }

    public Page<NotificationResponse> getByAccount(String accountNo, Pageable pageable) {
        return repo.findByAccountNoOrderByCreatedAtDesc(accountNo, pageable)
                .map(NotificationResponse::from);
    }
}
