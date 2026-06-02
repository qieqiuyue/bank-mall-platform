package com.bank.notification.dto;

import com.bank.notification.entity.Notification;
import java.time.LocalDateTime;

public class NotificationResponse {
    private String notificationNo;
    private String accountNo;
    private String channel;
    private String template;
    private String status;
    private LocalDateTime sentAt;

    public static NotificationResponse from(Notification n) {
        NotificationResponse r = new NotificationResponse();
        r.notificationNo = n.getNotificationNo();
        r.accountNo = n.getAccountNo();
        r.channel = n.getType();
        r.template = n.getTitle();
        r.status = n.getStatus();
        r.sentAt = n.getCreatedAt();
        return r;
    }

    public String getNotificationNo() { return notificationNo; }
    public String getAccountNo() { return accountNo; }
    public String getChannel() { return channel; }
    public String getTemplate() { return template; }
    public String getStatus() { return status; }
    public LocalDateTime getSentAt() { return sentAt; }
}
