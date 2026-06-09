package com.bank.common.exception;

/** Unified error codes across all 4 services. */
public enum ErrorCode {
    // auth-service
    AUTH_FAILED,
    BAD_REQUEST,

    // account-service
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_BALANCE,
    OPTIMISTIC_LOCK_CONFLICT,
    TRANSACTION_NOT_FOUND,
    DUPLICATE_IDEMPOTENCY_KEY,
    INVALID_AMOUNT,

    // payment-service
    PAYMENT_NOT_FOUND,
    PAYMENT_ALREADY_PROCESSED,
    ACCOUNT_SERVICE_UNAVAILABLE,

    // notification-service
    NOTIFICATION_NOT_FOUND,

    // shared
    INVALID_REQUEST,
    INTERNAL_ERROR
}
