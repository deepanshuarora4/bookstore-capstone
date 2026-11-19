package com.capstone.bookstore.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/register body (RegisterRequest in openapi.yaml).
 * There is deliberately no {@code role} property: a client-supplied role is ignored, so every
 * self-registered account is a CUSTOMER.
 */
public record RegisterRequest(
        @NotBlank(message = "is required")
        @Size(min = 2, max = 100, message = "must be between 2 and 100 characters")
        String fullName,

        @NotBlank(message = "is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 150, message = "must be at most 150 characters")
        String email,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "must contain at least one letter and one digit")
        String password,

        @Pattern(regexp = "^[6-9][0-9]{9}$", message = "must be a 10-digit Indian mobile number")
        String phone) {

    /** Never print the password, even by accident (logging, debugging). */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***]";
    }
}
