package com.capstone.bookstore.giftpoints;

import java.math.BigDecimal;
import java.time.Instant;

import com.capstone.bookstore.common.PageResponse;

/** GiftPointsResponse in openapi.yaml: balance, the active rules and a page of the ledger. */
public record GiftPointsResponse(int balance, Rules rules, PageResponse<Entry> transactions) {

    public record Rules(int earnPointsPerUnit, BigDecimal earnSpendUnit, BigDecimal pointValue,
                        int maxRedemptionPercent) {
    }

    /** GiftPointTransaction in openapi.yaml. */
    public record Entry(Long id, GiftPointTransactionType type, int points, Long orderId, Instant createdAt) {

        static Entry from(GiftPointTransaction t) {
            return new Entry(t.getId(), t.getType(), t.getPoints(),
                    t.getOrder() == null ? null : t.getOrder().getId(), t.getCreatedAt());
        }
    }
}
