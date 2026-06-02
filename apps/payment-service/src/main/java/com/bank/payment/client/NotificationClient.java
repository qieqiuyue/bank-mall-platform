package com.bank.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 封装对 notification-service 的调用.
 * 通知失败不影响支付主流程（catch log warn，不回滚）.
 */
@Component
public class NotificationClient {
    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient restClient;
    private final String notificationUrl;

    public NotificationClient(RestClient restClient,
                              @Value("${bank.services.notification-url:http://notification-service:8084}")
                              String notificationUrl) {
        this.restClient = restClient;
        this.notificationUrl = notificationUrl;
    }

    public void send(String accountNo, String template, String content) {
        try {
            Map<String, Object> body = Map.of(
                    "accountNo", accountNo,
                    "channel", "SMS",
                    "template", template,
                    "content", content
            );
            restClient.post()
                    .uri(notificationUrl + "/api/notifications")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Notification sent to {} template={}", accountNo, template);
        } catch (Exception e) {
            log.warn("Notification failed (non-blocking): {} template={}", accountNo, template, e);
        }
    }
}
