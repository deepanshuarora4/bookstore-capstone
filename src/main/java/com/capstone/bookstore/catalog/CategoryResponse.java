package com.capstone.bookstore.catalog;

/** Category in openapi.yaml. A DTO, so the JPA entity is never serialized. */
public record CategoryResponse(Long id, String name, String description) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription());
    }
}
