package com.capstone.bookstore.common;

/** Machine-readable error codes. Must stay identical to {@code components.schemas.ErrorCode} in openapi.yaml. */
public enum ErrorCode {
    VALIDATION_FAILED,
    AUTHENTICATION_FAILED,
    ACCESS_DENIED,
    NOT_FOUND,
    PRODUCT_INACTIVE,
    EMAIL_ALREADY_REGISTERED,
    DUPLICATE_RESOURCE,
    CONCURRENT_MODIFICATION,
    INSUFFICIENT_STOCK,
    EMPTY_CART,
    INSUFFICIENT_GIFT_POINTS,
    POINTS_LIMIT_EXCEEDED,
    INVALID_ORDER_STATE,
    CANCELLATION_WINDOW_EXPIRED,
    INVALID_PAYMENT_DETAILS,
    PAYMENT_DECLINED,
    INTERNAL_ERROR
}
