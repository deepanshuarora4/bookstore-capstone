package com.capstone.bookstore.payment;

import java.math.BigDecimal;
import java.time.Instant;

/** PaymentResponse in openapi.yaml. Never contains the full card number or CVV: only {@code cardLast4} exists. */
public record PaymentResponse(
        Long id,
        Long orderId,
        PaymentMethod method,
        PaymentStatus status,
        BigDecimal amount,
        String transactionRef,
        String cardLast4,
        String failureReason,
        Instant createdAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrder().getId(), p.getMethod(), p.getStatus(), p.getAmount(),
                p.getTransactionRef(), p.getCardLast4(), p.getFailureReason(), p.getCreatedAt());
    }
}
