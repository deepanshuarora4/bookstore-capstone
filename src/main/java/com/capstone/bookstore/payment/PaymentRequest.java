package com.capstone.bookstore.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PaymentRequest in openapi.yaml. SECURITY: {@code cardNumber} and {@code cvv} are write-only, live in memory for
 * the duration of the request only, are never stored, returned or logged, and {@link #toString()} masks them.
 * Validation messages are fixed text and never contain the rejected value.
 */
public record PaymentRequest(
        @NotNull(message = "is required")
        PaymentMethod method,

        @NotBlank(message = "is required")
        @Size(min = 2, max = 100, message = "must be between 2 and 100 characters")
        String cardHolderName,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[0-9]{13,19}$", message = "must be 13 to 19 digits")
        String cardNumber,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be between 1 and 12")
        @Max(value = 12, message = "must be between 1 and 12")
        Integer expiryMonth,

        @NotNull(message = "is required")
        @Min(value = 1000, message = "must be a four-digit year")
        @Max(value = 9999, message = "must be a four-digit year")
        Integer expiryYear,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[0-9]{3,4}$", message = "must be 3 or 4 digits")
        String cvv) {

    public PaymentRequest {
        cardHolderName = cardHolderName == null ? null : cardHolderName.trim();
    }

    /** Never print the card number or CVV, even by accident (logging, debugging, assertion messages). */
    @Override
    public String toString() {
        return "PaymentRequest[method=" + method + ", cardNumber=***, cvv=***]";
    }
}
