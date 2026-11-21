package com.capstone.bookstore.cart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.capstone.bookstore.catalog.ProductResponse;

/** CartResponse and CartItem in openapi.yaml. */
public record CartResponse(
        List<Line> items,
        int itemCount,
        BigDecimal subtotal,
        int maxRedeemablePoints,
        LocalDate estimatedDeliveryDate,
        String currency) {

    public record Line(ProductResponse product, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
