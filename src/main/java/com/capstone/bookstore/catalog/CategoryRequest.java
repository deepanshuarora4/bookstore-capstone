package com.capstone.bookstore.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** CategoryRequest in openapi.yaml. Text is trimmed before validation, so "  a " is a 1-character name. */
public record CategoryRequest(
        @NotBlank(message = "is required")
        @Size(min = 2, max = 100, message = "must be between 2 and 100 characters")
        String name,

        @Size(max = 500, message = "must be at most 500 characters")
        String description) {

    public CategoryRequest {
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.trim();
    }
}
