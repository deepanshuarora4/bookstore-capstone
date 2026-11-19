package com.capstone.bookstore.giftpoints;

import java.time.Instant;

import com.capstone.bookstore.order.Order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Append-only ledger entry. The unique (order_id, type) constraint allows at most one EARN, REDEEM, REFUND
 * and REVERSE entry per order, which is what prevents duplicate deductions.
 * {@code points} is the signed change applied to the balance (negative for REDEEM and REVERSE).
 */
@Entity
@Table(name = "gift_point_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uq_gift_point_txn_order_type", columnNames = {"order_id", "type"}))
public class GiftPointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private GiftPointAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", updatable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private GiftPointTransactionType type;

    @Column(nullable = false, updatable = false)
    private int points;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GiftPointTransaction() {
    }

    public GiftPointTransaction(GiftPointAccount account, Order order, GiftPointTransactionType type, int points) {
        this.account = account;
        this.order = order;
        this.type = type;
        this.points = points;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public GiftPointAccount getAccount() { return account; }
    public Order getOrder() { return order; }
    public GiftPointTransactionType getType() { return type; }
    public int getPoints() { return points; }
    public Instant getCreatedAt() { return createdAt; }
}
