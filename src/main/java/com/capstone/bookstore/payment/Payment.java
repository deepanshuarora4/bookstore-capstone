package com.capstone.bookstore.payment;

import java.math.BigDecimal;
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

/**
 * One (simulated) payment attempt for an order. An order can have several FAILED attempts followed by
 * one SUCCESS. SECURITY: the only card data kept is {@code cardLast4}. The full card number and CVV have
 * no field here and no database column; they exist only in the transient request DTO.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "transaction_ref", length = 40, unique = true)
    private String transactionRef;

    @Column(name = "card_last4", nullable = false, length = 4, updatable = false)
    private String cardLast4;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(Order order, PaymentMethod method, PaymentStatus status, BigDecimal amount,
                   String transactionRef, String cardLast4, String failureReason) {
        this.order = order;
        this.method = method;
        this.status = status;
        this.amount = amount;
        this.transactionRef = transactionRef;
        this.cardLast4 = cardLast4;
        this.failureReason = failureReason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public String getTransactionRef() { return transactionRef; }
    public String getCardLast4() { return cardLast4; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
