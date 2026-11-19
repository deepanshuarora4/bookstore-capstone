package com.capstone.bookstore.giftpoints;

/**
 * EARN (payment success, +), REDEEM (order placement, -),
 * REFUND (redeemed points returned on cancellation, +), REVERSE (earned points taken back on cancellation, -).
 */
public enum GiftPointTransactionType {
    EARN,
    REDEEM,
    REFUND,
    REVERSE
}
