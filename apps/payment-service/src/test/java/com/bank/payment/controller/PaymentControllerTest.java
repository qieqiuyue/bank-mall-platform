package com.bank.payment.controller;

import com.bank.payment.dto.PaymentResponse;
import com.bank.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerTest {

    private MockMvc mvc;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService)).build();
    }

    @Test
    void createPayment_success() throws Exception {
        PaymentResponse resp = new PaymentResponse();
        when(paymentService.processPayment(any())).thenReturn(resp);

        mvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccount":"A1001","amount":299.00,\
                                "currency":"CNY","idempotencyKey":"KEY-001"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void queryPayment_success() throws Exception {
        PaymentResponse resp = new PaymentResponse();
        when(paymentService.getPayment("PAY-001")).thenReturn(resp);

        mvc.perform(get("/api/payments/PAY-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/payments/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
