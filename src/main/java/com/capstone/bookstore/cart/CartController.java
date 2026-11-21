package com.capstone.bookstore.cart;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/** /cart: the basket of the authenticated user only (IBM step 8). */
@RestController
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/cart")
    public CartResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.get(user.id());
    }

    @DeleteMapping("/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal AuthenticatedUser user) {
        cartService.clear(user.id());
    }

    @PostMapping("/cart/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse add(@AuthenticationPrincipal AuthenticatedUser user,
                            @Valid @RequestBody CartItemAddRequest request) {
        return cartService.add(user.id(), request);
    }

    @PutMapping("/cart/items/{productId}")
    public CartResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable @Min(value = 1, message = "must be at least 1") Long productId,
                               @Valid @RequestBody CartItemUpdateRequest request) {
        return cartService.update(user.id(), productId, request);
    }

    @DeleteMapping("/cart/items/{productId}")
    public CartResponse remove(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable @Min(value = 1, message = "must be at least 1") Long productId) {
        return cartService.remove(user.id(), productId);
    }
}
