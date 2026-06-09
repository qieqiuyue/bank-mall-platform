package com.bank.payment.controller;

import com.bank.common.api.ApiResponse;
import com.bank.payment.dto.PaymentRequest;
import com.bank.payment.dto.PaymentResponse;
import com.bank.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payment", description = "支付服务 — 创建支付、查询支付状态")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "创建支付", description = "处理一次支付请求，支持补偿事务和幂等")
    public ApiResponse<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        return ApiResponse.success("Payment processed", paymentService.processPayment(request));
    }

    @GetMapping("/{paymentNo}")
    @Operation(summary = "查询支付", description = "根据支付编号查询支付状态")
    public ApiResponse<PaymentResponse> queryPayment(@PathVariable String paymentNo) {
        return ApiResponse.success(paymentService.getPayment(paymentNo));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success("payment-service is healthy", Map.of(
                "status", "UP",
                "service", "payment-service"
        ));
    }
}
