package com.bank.notification.controller;

import com.bank.common.api.ApiResponse;
import com.bank.notification.dto.NotificationRequest;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notification", description = "通知服务 — 发送通知、查询通知记录")
public class NotifyController {

    private final NotificationService service;

    public NotifyController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "发送通知", description = "创建一条新的通知记录")
    public ApiResponse<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        return ApiResponse.success("Notification sent", service.send(request));
    }

    @GetMapping
    @Operation(summary = "查询通知列表（分页）", description = "分页查询账号通知记录")
    public ApiResponse<Map<String, Object>> listByAccount(
            @RequestParam("accountNo") String accountNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageResult = service.getByAccount(accountNo, PageRequest.of(page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNo", accountNo);
        data.put("items", pageResult.getContent());
        data.put("page", page);
        data.put("size", size);
        data.put("totalPages", pageResult.getTotalPages());
        data.put("totalElements", pageResult.getTotalElements());
        return ApiResponse.success(data);
    }

    @GetMapping("/templates")
    @Operation(summary = "通知模板列表", description = "返回所有可用的通知模板")
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
