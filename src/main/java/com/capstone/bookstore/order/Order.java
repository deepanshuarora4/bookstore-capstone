package com.capstone.bookstore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.capstone.bookstore.address.Address;
import com.capstone.bookstore.user.User;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A placed order. Title/price/address are snapshots so order history never changes when the catalogue
 * or the customer's address book does. {@code totalAmount = subtotal - pointsDiscount} and is always > 0
 * because gift point redemption is capped below 100% of the subtotal.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Reference to the address book entry; the snapshot below is authoritative. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id", updatable = false)
    private Address address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(name = "ship_label", length = 50)
    private String shipLabel;

    @Column(name = "ship_line1", nullable = false, length = 150)
    private String shipLine1;

    @Column(name = "ship_line2", length = 150)
    private String shipLine2;

    @Column(name = "ship_city", nullable = false, length = 80)
    private String shipCity;

    @Column(name = "ship_state", nullable = false, length = 80)
    private String shipState;

    @Column(name = "ship_postal_code", nullable = false, length = 6)
    private String shipPostalCode;

    @Column(name = "ship_country", nullable = false, length = 60)
    private String shipCountry;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "points_redeemed", nullable = false)
    private int pointsRedeemed;

    @Column(name = "points_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal pointsDiscount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "points_earned", nullable = false)
    private int pointsEarned;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "estimated_delivery_date", nullable = false)
    private LocalDate estimatedDeliveryDate;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    /** Optimistic lock: prevents two concurrent state changes (e.g. cancel + pay) from both winning. */
    @Version
    private long version;

    protected Order() {
    }

    public Order(User user, Address address, String orderNumber, Instant placedAt) {
        this.user = user;
        this.address = address;
        this.orderNumber = orderNumber;
        this.placedAt = placedAt;
        this.shipLabel = address.getLabel();
        this.shipLine1 = address.getLine1();
        this.shipLine2 = address.getLine2();
        this.shipCity = address.getCity();
        this.shipState = address.getState();
        this.shipPostalCode = address.getPostalCode();
        this.shipCountry = address.getCountry();
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }

    /** Sets the money figures. Redemption discount is applied once, at order creation. */
    public void applyTotals(BigDecimal subtotal, int pointsRedeemed, BigDecimal pointsDiscount) {
        this.subtotal = subtotal;
        this.pointsRedeemed = pointsRedeemed;
        this.pointsDiscount = pointsDiscount;
        this.totalAmount = subtotal.subtract(pointsDiscount);
    }

    public void markCancelled(Instant when, String reason) {
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = when;
        this.cancelReason = reason;
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public User getUser() { return user; }
    public Address getAddress() { return address; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getShipLabel() { return shipLabel; }
    public String getShipLine1() { return shipLine1; }
    public String getShipLine2() { return shipLine2; }
    public String getShipCity() { return shipCity; }
    public String getShipState() { return shipState; }
    public String getShipPostalCode() { return shipPostalCode; }
    public String getShipCountry() { return shipCountry; }
    public BigDecimal getSubtotal() { return subtotal; }
    public int getPointsRedeemed() { return pointsRedeemed; }
    public BigDecimal getPointsDiscount() { return pointsDiscount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public int getPointsEarned() { return pointsEarned; }
    public void setPointsEarned(int pointsEarned) { this.pointsEarned = pointsEarned; }
    public Instant getPlacedAt() { return placedAt; }
    public LocalDate getEstimatedDeliveryDate() { return estimatedDeliveryDate; }
    public void setEstimatedDeliveryDate(LocalDate estimatedDeliveryDate) { this.estimatedDeliveryDate = estimatedDeliveryDate; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public List<OrderItem> getItems() { return items; }
    public long getVersion() { return version; }
}
