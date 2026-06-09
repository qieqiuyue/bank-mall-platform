package com.bank.account.controller;

import com.bank.common.api.ApiResponse;
import com.bank.account.dto.*;
import com.bank.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Account", description = "账户服务 — 余额查询、交易记录、出入金")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // --- Query endpoints (backward-compatible with V1 mock) ---

    @GetMapping("/{accountNo}")
    @Operation(summary = "查询账户信息", description = "根据账号查询账户详情")
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
    @Operation(summary = "查询交易记录", description = "分页查询账户交易记录")
    public ApiResponse<Map<String, Object>> getTransactions(
            @PathVariable String accountNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageResult = accountService.getTransactions(accountNo,
                org.springframework.data.domain.PageRequest.of(page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNo", accountNo);
        data.put("items", pageResult.getContent());
        data.put("page", page);
        data.put("size", size);
        data.put("totalPages", pageResult.getTotalPages());
        data.put("totalElements", pageResult.getTotalElements());
        return ApiResponse.success(data);
    }

    // --- Command endpoints ---

    @PostMapping("/{accountNo}/debit")
    @Operation(summary = "扣款", description = "从账户扣除指定金额")
    public ApiResponse<TransactionResponse> debit(@PathVariable String accountNo,
                                                   @Valid @RequestBody DebitRequest request) {
        return ApiResponse.success(accountService.debit(accountNo, request));
    }

    @PostMapping("/{accountNo}/credit")
    @Operation(summary = "入账", description = "向账户存入指定金额")
    public ApiResponse<TransactionResponse> credit(@PathVariable String accountNo,
                                                    @Valid @RequestBody CreditRequest request) {
        return ApiResponse.success(accountService.credit(accountNo, request));
    }

    @PostMapping("/{accountNo}/reverse")
    @Operation(summary = "冲正", description = "撤销指定交易，恢复账户余额")
    public ApiResponse<TransactionResponse> reverse(@PathVariable String accountNo,
                                                     @Valid @RequestBody ReverseRequest request) {
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
