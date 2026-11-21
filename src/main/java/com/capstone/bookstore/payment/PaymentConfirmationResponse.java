package com.capstone.bookstore.payment;

import com.capstone.bookstore.order.OrderResponse;

/** PaymentConfirmationResponse in openapi.yaml: payment confirmation (IBM step 11) and purchase confirmation (step 12). */
public record PaymentConfirmationResponse(PaymentResponse payment, OrderResponse order, int pointsEarned, String message) {
}
