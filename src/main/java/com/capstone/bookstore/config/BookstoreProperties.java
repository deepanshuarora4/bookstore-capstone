package com.capstone.bookstore.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Configurable business rules (prefix {@code bookstore}). Defaults live in {@code application.yml}
 * and mirror the "Configurable business rules" table in {@code openapi.yaml}.
 * Invalid values fail application start-up.
 */
@Validated
@ConfigurationProperties(prefix = "bookstore")
public record BookstoreProperties(
        @Valid @NotNull Points points,
        @Valid @NotNull Order order,
        @Valid @NotNull Delivery delivery,
        @Valid @NotNull Cart cart,
        @Valid @NotNull Payment payment,
        @Valid @NotNull Security security) {

    public record Points(
            @Min(1) int earnPointsPerUnit,
            @NotNull @DecimalMin("0.01") BigDecimal earnSpendUnit,
            @NotNull @DecimalMin("0.01") BigDecimal pointValue,
            /* Capped at 50 so the payable total of an order is always greater than zero. */
            @Min(0) @Max(50) int maxRedemptionPercent) {
    }

    public record Order(@Min(1) int cancellationWindowHours) {
    }

    public record Delivery(@Min(0) int transitDays) {
    }

    public record Cart(@Min(1) int maxQuantityPerItem) {
    }

    public record Payment(@NotBlank @Size(min = 4, max = 4) String declineCardSuffix) {
    }

    public record Security(@Valid @NotNull Jwt jwt) {

        public record Jwt(
                /* HS256 needs at least 256 bits = 32 bytes. */
                @NotBlank @Size(min = 32) String secret,
                @Min(60) long expirySeconds) {
        }
    }
}
