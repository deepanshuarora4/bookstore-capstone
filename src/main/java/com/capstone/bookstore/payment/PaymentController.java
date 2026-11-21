package com.capstone.bookstore.payment;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/** /orders/{orderId}/payments and /orders/{orderId}/payment: simulated card payment of the user's own orders. */
@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{orderId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentConfirmationResponse pay(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable @Min(value = 1, message = "must be at least 1") Long orderId,
                                           @Valid @RequestBody PaymentRequest request) {
        return paymentService.pay(user.id(), orderId, request);
    }

    @GetMapping("/orders/{orderId}/payment")
    public PaymentResponse latest(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable @Min(value = 1, message = "must be at least 1") Long orderId) {
        return paymentService.latest(user.id(), orderId);
    }
}
