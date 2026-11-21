package com.capstone.bookstore.catalog;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * ProductRequest in openapi.yaml, used for create (POST) and full update (PUT).
 * <p>
 * Text is trimmed before validation. {@code active} defaults to true when omitted. {@code version} is optional:
 * on update, if it is sent and differs from the stored version, the product was changed by someone else since
 * the client read it and the update is refused with 409.
 */
public record ProductRequest(
        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String title,

        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String author,

        @NotNull(message = "is required")
        @Pattern(regexp = "^([0-9]{10}|[0-9]{13})$", message = "must be 10 or 13 digits without hyphens")
        String isbn,

        @Size(max = 2000, message = "must be at most 2000 characters")
        String description,

        @NotNull(message = "is required")
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 8, fraction = 2, message = "must have at most 8 integer digits and 2 decimal places")
        BigDecimal price,

        @Size(max = 500, message = "must be at most 500 characters")
        @Pattern(regexp = "^https?://\\S+$", message = "must be an http or https URL")
        String imageUrl,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must not be negative")
        Integer stockQty,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be between 0 and 30")
        @Max(value = 30, message = "must be between 0 and 30")
        Integer dispatchDays,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Long categoryId,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Long brandId,

        Boolean active,

        @Min(value = 0, message = "must not be negative")
        Long version) {

    public ProductRequest {
        title = title == null ? null : title.trim();
        author = author == null ? null : author.trim();
        isbn = isbn == null ? null : isbn.trim();
        description = description == null || description.isBlank() ? null : description.trim();
        imageUrl = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
