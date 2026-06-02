package com.bank.notification.controller;

import com.bank.notification.api.ApiResponse;
import com.bank.notification.dto.NotificationRequest;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotifyController {

    private final NotificationService service;

    public NotifyController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<NotificationResponse> send(@RequestBody NotificationRequest request) {
        return ApiResponse.success("Notification sent", service.send(request));
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> listByAccount(@RequestParam("accountNo") String accountNo) {
        return ApiResponse.success(service.getByAccount(accountNo));
    }

    @GetMapping("/templates")
    public ApiResponse<List<Map<String, String>>> templates() {
        return ApiResponse.success(List.of(
                Map.of("name", "PAYMENT_SUCCESS", "title", "Payment Confirmed",
                        "content", "Your payment of {amount} {currency} has been processed."),
                Map.of("name", "LOGIN_ALERT", "title", "Login Alert",
                        "content", "Your account was logged in at {time}."),
                Map.of("name", "ORDER_SHIPPED", "title", "Order Shipped",
                        "content", "Your order {orderId} has been shipped.")
        ));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success("notification-service is healthy", Map.of(
                "status", "UP",
                "service", "notification-service"
        ));
    }
}
