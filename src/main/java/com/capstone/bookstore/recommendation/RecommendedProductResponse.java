package com.capstone.bookstore.recommendation;

import com.capstone.bookstore.catalog.ProductResponse;

/** RecommendedProduct in openapi.yaml: the product and a human-readable reason. */
public record RecommendedProductResponse(ProductResponse product, String reason) {
}
