package com.capstone.bookstore.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.payment.Payment;
import com.capstone.bookstore.payment.PaymentResponse;

/** Entity to API model for orders, including the cancellation window (bookstore.order.cancellation-window-hours). */
@Component
public class OrderMapper {

    private final Clock clock;
    private final Duration cancellationWindow;

    public OrderMapper(Clock clock, BookstoreProperties properties) {
        this.clock = clock;
        this.cancellationWindow = Duration.ofHours(properties.order().cancellationWindowHours());
    }

    /** The last moment at which the customer may still cancel. */
    public Instant cancellableUntil(Order order) {
        return order.getPlacedAt().plus(cancellationWindow);
    }

    public boolean withinCancellationWindow(Order order) {
        return !Instant.now(clock).isAfter(cancellableUntil(order));
    }

    /** True when the status allows cancellation and the window has not expired. */
    public boolean isCancellable(Order order) {
        return order.getStatus().isCancellable() && withinCancellationWindow(order);
    }

    /** Items must be loaded (entity graph / fetch) or the call must run inside a transaction. */
    public OrderResponse toResponse(Order order, Payment latestPayment) {
        List<OrderResponse.Item> items = order.getItems().stream()
                .map(i -> new OrderResponse.Item(i.getProduct().getId(), i.getTitle(), i.getAuthor(), i.getUnitPrice(),
                        i.getQuantity(), i.getLineTotal(), i.getEstimatedDeliveryDate()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                items,
                new OrderResponse.ShippingAddress(order.getShipLabel(), order.getShipLine1(), order.getShipLine2(),
                        order.getShipCity(), order.getShipState(), order.getShipPostalCode(), order.getShipCountry()),
                order.getSubtotal(),
                order.getPointsRedeemed(),
                order.getPointsDiscount(),
                order.getTotalAmount(),
                order.getPointsEarned(),
                "INR",
                order.getPlacedAt(),
                order.getEstimatedDeliveryDate(),
                isCancellable(order),
                cancellableUntil(order),
                order.getCancelledAt(),
                latestPayment == null ? null : PaymentResponse.from(latestPayment));
    }
}
