package com.capstone.bookstore.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/login body (LoginRequest in openapi.yaml).
 * <p>
 * The password deliberately has no maximum length: an over-long password can never be a valid one (registration
 * limits it to 72 UTF-8 bytes), so AuthService answers it with the same generic 401 as any wrong password
 * instead of a 400 that would reveal the length rule.
 */
public record LoginRequest(
        @NotBlank(message = "is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 150, message = "must be at most 150 characters")
        String email,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "is required")
        String password) {

    /** Never print the password, even by accident (logging, debugging). */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
