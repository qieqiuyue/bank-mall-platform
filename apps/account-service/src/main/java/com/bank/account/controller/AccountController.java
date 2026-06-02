package com.bank.account.controller;

import com.bank.account.api.ApiResponse;
import com.bank.account.dto.*;
import com.bank.account.service.AccountService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // --- Query endpoints (backward-compatible with V1 mock) ---

    @GetMapping("/{accountNo}")
    public ApiResponse<AccountResponse> getAccount(@PathVariable String accountNo) {
        return ApiResponse.success(accountService.getAccount(accountNo));
    }

    @GetMapping("/{accountNo}/balance")
    public ApiResponse<Map<String, Object>> getBalance(@PathVariable String accountNo) {
        BigDecimal balance = accountService.getBalance(accountNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNo", accountNo);
        data.put("availableBalance", balance);
        data.put("currency", "CNY");
        data.put("updatedAt", LocalDateTime.now().toString());
        return ApiResponse.success(data);
    }

    @GetMapping("/{accountNo}/transactions")
    public ApiResponse<Map<String, Object>> getTransactions(@PathVariable String accountNo) {
        List<TransactionResponse> items = accountService.getTransactions(accountNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNo", accountNo);
        data.put("items", items);
        return ApiResponse.success(data);
    }

    @GetMapping("/balance/{id}")
    public ApiResponse<Map<String, Object>> legacyBalance(@PathVariable String id) {
        return getBalance(id);
    }

    // --- Command endpoints (new in S1 CP1) ---

    @PostMapping("/{accountNo}/debit")
    public ApiResponse<TransactionResponse> debit(@PathVariable String accountNo,
                                                   @RequestBody DebitRequest request) {
        return ApiResponse.success(accountService.debit(accountNo, request));
    }

    @PostMapping("/{accountNo}/credit")
    public ApiResponse<TransactionResponse> credit(@PathVariable String accountNo,
                                                    @RequestBody CreditRequest request) {
        return ApiResponse.success(accountService.credit(accountNo, request));
    }

    @PostMapping("/{accountNo}/reverse")
    public ApiResponse<TransactionResponse> reverse(@PathVariable String accountNo,
                                                     @RequestBody ReverseRequest request) {
        return ApiResponse.success(accountService.reverse(accountNo, request));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success("account-service is healthy", Map.of(
                "status", "UP",
                "service", "account-service"
        ));
    }
}
