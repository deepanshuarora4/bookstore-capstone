package com.capstone.bookstore.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Product in openapi.yaml. A DTO: category and brand are nested response objects and no JPA entity is serialized.
 * {@code version} lets an admin client send it back on update for stale-edit detection.
 */
public record ProductResponse(
        Long id,
        String title,
        String author,
        String isbn,
        String description,
        BigDecimal price,
        String imageUrl,
        CategoryResponse category,
        BrandResponse brand,
        int stockQty,
        boolean inStock,
        LocalDate estimatedDeliveryDate,
        long version) {
}
