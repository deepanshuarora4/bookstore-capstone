package com.capstone.bookstore.order;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.capstone.bookstore.catalog.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An order line with title and unit price snapshotted at purchase time. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(length = 150, updatable = false)
    private String author;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    @Column(name = "estimated_delivery_date", nullable = false, updatable = false)
    private LocalDate estimatedDeliveryDate;

    protected OrderItem() {
    }

    /** Snapshots title, author and price from the product at creation time. */
    public OrderItem(Order order, Product product, int quantity, LocalDate estimatedDeliveryDate) {
        this.order = order;
        this.product = product;
        this.title = product.getTitle();
        this.author = product.getAuthor();
        this.unitPrice = product.getPrice();
        this.quantity = quantity;
        this.lineTotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        this.estimatedDeliveryDate = estimatedDeliveryDate;
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public Product getProduct() { return product; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public LocalDate getEstimatedDeliveryDate() { return estimatedDeliveryDate; }
}
