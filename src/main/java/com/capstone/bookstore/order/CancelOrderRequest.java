package com.capstone.bookstore.order;

import jakarta.validation.constraints.Size;

/** CancelOrderRequest in openapi.yaml. The whole body is optional. */
public record CancelOrderRequest(
        @Size(max = 255, message = "must be at most 255 characters")
        String reason) {
}
