package com.capstone.bookstore.address;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AddressRequest in openapi.yaml (create and update). Text is trimmed; {@code country} defaults to India and
 * {@code isDefault} to false when omitted.
 */
public record AddressRequest(
        @Size(max = 50, message = "must be at most 50 characters")
        String label,

        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String line1,

        @Size(max = 150, message = "must be at most 150 characters")
        String line2,

        @NotBlank(message = "is required")
        @Size(max = 80, message = "must be at most 80 characters")
        String city,

        @NotBlank(message = "is required")
        @Size(max = 80, message = "must be at most 80 characters")
        String state,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "must be a 6-digit Indian PIN code")
        String postalCode,

        @Size(max = 60, message = "must be at most 60 characters")
        String country,

        Boolean isDefault) {

    public AddressRequest {
        label = blankToNull(label);
        line1 = line1 == null ? null : line1.trim();
        line2 = blankToNull(line2);
        city = city == null ? null : city.trim();
        state = state == null ? null : state.trim();
        postalCode = postalCode == null ? null : postalCode.trim();
        country = blankToNull(country);
    }

    String countryOrDefault() {
        return country == null ? "India" : country;
    }

    boolean defaultRequested() {
        return Boolean.TRUE.equals(isDefault);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
