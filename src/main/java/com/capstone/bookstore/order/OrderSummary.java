package com.capstone.bookstore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** OrderSummary in openapi.yaml: one row of the order history. {@code itemCount} is the total quantity. */
public record OrderSummary(
        Long id,
        String orderNumber,
        OrderStatus status,
        int itemCount,
        String firstItemTitle,
        BigDecimal totalAmount,
        Instant placedAt,
        LocalDate estimatedDeliveryDate,
        boolean cancellable) {
}
