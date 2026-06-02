package com.bank.account.api;

import java.time.Instant;

/**
 * 统一 API 响应包装器.
 * NOTE: Duplicated across services. Keep in sync with auth-service if changed.
 */
public class ApiResponse<T> {
    private final String code;
    private final String message;
    private final T data;
    private final String timestamp;

    private ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        return of("SUCCESS", "OK", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return of("SUCCESS", message, data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return of(code, message, null);
    }

    private static <T> ApiResponse<T> of(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getTimestamp() { return timestamp; }
}
