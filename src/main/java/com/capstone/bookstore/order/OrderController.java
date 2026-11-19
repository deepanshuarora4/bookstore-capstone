package com.capstone.bookstore.order;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.common.PageResponse;
import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * /orders: placement, history, Buy Again, detail and cancellation of the authenticated user's own orders.
 * The user id always comes from the JWT, never from the request.
 */
@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                @Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(user.id(), request);
    }

    @GetMapping("/orders")
    public PageResponse<OrderSummary> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 100")
                    @Max(value = 100, message = "must be between 1 and 100") int size) {
        return orderService.list(user.id(), status, page, size);
    }

    @GetMapping("/orders/buy-again")
    public List<BuyAgainItem> buyAgain(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "8") @Min(value = 1, message = "must be between 1 and 50")
                    @Max(value = 50, message = "must be between 1 and 50") int limit) {
        return orderService.buyAgain(user.id(), limit);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable @Min(value = 1, message = "must be at least 1") Long orderId) {
        return orderService.get(user.id(), orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable @Min(value = 1, message = "must be at least 1") Long orderId,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancel(user.id(), orderId, request == null ? null : request.reason());
    }
}
