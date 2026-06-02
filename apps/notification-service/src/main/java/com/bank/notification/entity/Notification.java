package com.bank.notification.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_no", unique = true, nullable = false, length = 36)
    private String notificationNo;

    @Column(name = "account_no", length = 20)
    private String accountNo;

    @Column(length = 20)
    private String type;

    @Column(length = 128)
    private String title;

    @Column(length = 512)
    private String content;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Notification() {
        this.notificationNo = UUID.randomUUID().toString();
        this.status = "PENDING";
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getNotificationNo() { return notificationNo; }
    public void setNotificationNo(String no) { this.notificationNo = no; }
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String a) { this.accountNo = a; }
    public String getType() { return type; }
    public void setType(String t) { this.type = t; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
