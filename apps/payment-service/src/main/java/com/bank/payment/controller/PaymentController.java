package com.bank.payment.controller;

import com.bank.payment.api.ApiResponse;
import com.bank.payment.dto.PaymentRequest;
import com.bank.payment.dto.PaymentResponse;
import com.bank.payment.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ApiResponse<PaymentResponse> createPayment(@RequestBody PaymentRequest request) {
        return ApiResponse.success("Payment processed", paymentService.processPayment(request));
    }

    @GetMapping("/{paymentNo}")
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
