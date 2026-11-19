package com.capstone.bookstore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.capstone.bookstore.payment.PaymentResponse;

/** OrderResponse in openapi.yaml (with OrderItem and AddressSnapshot). A DTO: no entity is serialized. */
public record OrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        List<Item> items,
        ShippingAddress shippingAddress,
        BigDecimal subtotal,
        int pointsRedeemed,
        BigDecimal pointsDiscount,
        BigDecimal totalAmount,
        int pointsEarned,
        String currency,
        Instant placedAt,
        LocalDate estimatedDeliveryDate,
        boolean cancellable,
        Instant cancellableUntil,
        Instant cancelledAt,
        PaymentResponse payment) {

    public record Item(Long productId, String title, String author, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, LocalDate estimatedDeliveryDate) {
    }

    public record ShippingAddress(String label, String line1, String line2, String city, String state,
                                  String postalCode, String country) {
    }
}
