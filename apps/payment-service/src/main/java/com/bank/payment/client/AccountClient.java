package com.bank.payment.client;

import com.bank.payment.dto.AccountServiceResponse;
import com.bank.payment.exception.BusinessException;
import com.bank.payment.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 封装对 account-service 的 RestClient 调用.
 * 所有方法检查 ApiResponse.code=="SUCCESS"，否则抛 BusinessException.
 */
@Component
public class AccountClient {
    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);

    private final RestClient restClient;
    private final String accountServiceUrl;

    public AccountClient(RestClient restClient,
                         @Value("${bank.services.account-url:http://account-service:8082}") String accountServiceUrl) {
        this.restClient = restClient;
        this.accountServiceUrl = accountServiceUrl;
    }

    /** 扣款 */
    public AccountServiceResponse.TransactionData debit(String accountNo, BigDecimal amount,
                                                         String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "referenceType", "PAYMENT",
                "referenceId", idempotencyKey,
                "idempotencyKey", "debit-" + idempotencyKey
        );
        return callAccount(accountNo + "/debit", body);
    }

    /** 入账 */
    public AccountServiceResponse.TransactionData credit(String accountNo, BigDecimal amount,
                                                          String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "referenceType", "PAYMENT",
                "referenceId", idempotencyKey,
                "idempotencyKey", "credit-" + idempotencyKey
        );
        return callAccount(accountNo + "/credit", body);
    }

    /** 冲正（撤销扣款） */
    public AccountServiceResponse.TransactionData reverse(String accountNo, String originalTxnNo,
                                                           String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "originalTransactionNo", originalTxnNo,
                "idempotencyKey", "reverse-" + idempotencyKey
        );
        return callAccount(accountNo + "/reverse", body);
    }

    private AccountServiceResponse.TransactionData callAccount(String path, Map<String, Object> body) {
        try {
            AccountServiceResponse resp = restClient.post()
                    .uri(accountServiceUrl + "/api/accounts/" + path)
                    .body(body)
                    .retrieve()
                    .body(AccountServiceResponse.class);

            if (resp == null || !resp.isSuccess()) {
                String errMsg = resp != null ? resp.getMessage() : "null response";
                log.error("Account service call failed: {} -> {}", path, errMsg);
                throw new BusinessException(ErrorCode.ACCOUNT_SERVICE_UNAVAILABLE,
                        "Account service error: " + errMsg);
            }
            return resp.getData();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Account service unreachable: {} -> {}", path, e.getMessage());
            throw new BusinessException(ErrorCode.ACCOUNT_SERVICE_UNAVAILABLE,
                    "Cannot reach account-service: " + e.getMessage());
        }
    }
}
