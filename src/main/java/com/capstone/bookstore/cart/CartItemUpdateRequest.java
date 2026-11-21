package com.capstone.bookstore.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** CartItemUpdateRequest in openapi.yaml. */
public record CartItemUpdateRequest(
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Integer quantity) {
}
