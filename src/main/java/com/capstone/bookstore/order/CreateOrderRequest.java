package com.capstone.bookstore.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** CreateOrderRequest in openapi.yaml. {@code pointsToRedeem} defaults to 0. */
public record CreateOrderRequest(
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Long addressId,

        @Min(value = 0, message = "must not be negative")
        Integer pointsToRedeem) {

    int pointsOrZero() {
        return pointsToRedeem == null ? 0 : pointsToRedeem;
    }
}
