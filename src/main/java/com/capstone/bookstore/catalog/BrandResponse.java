package com.capstone.bookstore.catalog;

/** Brand in openapi.yaml. A DTO, so the JPA entity is never serialized. */
public record BrandResponse(Long id, String name, String description) {

    public static BrandResponse from(Brand brand) {
        return new BrandResponse(brand.getId(), brand.getName(), brand.getDescription());
    }
}
