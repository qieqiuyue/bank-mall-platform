package com.bank.account.controller;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.TransactionResponse;
import com.bank.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
@ActiveProfiles("test")
class AccountControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService accountService;

    @Test
    void getBalance_success() throws Exception {
        when(accountService.getBalance("A1001")).thenReturn(new BigDecimal("8888.88"));

        mvc.perform(get("/api/accounts/A1001/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.availableBalance").value(8888.88));
    }

    @Test
    void getTransactions_success() throws Exception {
        when(accountService.getTransactions("A1001")).thenReturn(List.of());

        mvc.perform(get("/api/accounts/A1001/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void debit_success() throws Exception {
        TransactionResponse txn = new TransactionResponse();
        when(accountService.debit(eq("A1001"), any())).thenReturn(txn);

        mvc.perform(post("/api/accounts/A1001/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":299.00,"referenceType":"PAYMENT",\
                                "referenceId":"PAY001","idempotencyKey":"KEY001"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void credit_success() throws Exception {
        TransactionResponse txn = new TransactionResponse();
        when(accountService.credit(eq("A1001"), any())).thenReturn(txn);

        mvc.perform(post("/api/accounts/A1001/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.00,"referenceType":"REFUND",\
                                "referenceId":"REF001","idempotencyKey":"KEY002"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/accounts/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
