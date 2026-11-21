package com.capstone.bookstore.order;

import java.time.Instant;

import com.capstone.bookstore.catalog.ProductResponse;

/** BuyAgainItem in openapi.yaml. */
public record BuyAgainItem(ProductResponse product, int timesOrdered, Instant lastOrderedAt) {
}
