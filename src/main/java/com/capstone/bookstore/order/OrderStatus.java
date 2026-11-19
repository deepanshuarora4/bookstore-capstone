package com.capstone.bookstore.order;

/**
 * PENDING_PAYMENT, then CONFIRMED, then SHIPPED, then DELIVERED.
 * CANCELLED is reachable only from PENDING_PAYMENT and CONFIRMED.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /** Whether the order is still in a state from which the customer may cancel it. */
    public boolean isCancellable() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
