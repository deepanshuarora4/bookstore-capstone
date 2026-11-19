package com.capstone.bookstore.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** CartItemAddRequest in openapi.yaml. */
public record CartItemAddRequest(
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Long productId,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Integer quantity) {
}
