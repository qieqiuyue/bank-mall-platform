package com.bank.account.controller;

import com.bank.account.dto.TransactionResponse;
import com.bank.account.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountControllerTest {

    private MockMvc mvc;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(accountService)).build();
    }

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
        Page<TransactionResponse> emptyPage = new PageImpl<>(Collections.emptyList());
        when(accountService.getTransactions(eq("A1001"), any(Pageable.class))).thenReturn(emptyPage);

        mvc.perform(get("/api/accounts/A1001/transactions?page=0&size=20"))
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
