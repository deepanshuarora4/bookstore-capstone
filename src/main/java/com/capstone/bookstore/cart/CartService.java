package com.capstone.bookstore.cart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.catalog.ProductMapper;
import com.capstone.bookstore.catalog.ProductRepository;
import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.ErrorCode;
import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.giftpoints.GiftPointService;
import com.capstone.bookstore.user.UserRepository;

/**
 * The basket (IBM step 8). The cart is always resolved from the authenticated user's id, so one user can never see
 * or change another user's basket. Quantities are checked against stock and bookstore.cart.max-quantity-per-item;
 * stock itself is only reduced at checkout.
 */
@Service
public class CartService {

    private final CartRepository carts;
    private final ProductRepository products;
    private final UserRepository users;
    private final ProductMapper productMapper;
    private final GiftPointService giftPoints;
    private final int maxQuantityPerItem;

    public CartService(CartRepository carts, ProductRepository products, UserRepository users,
                       ProductMapper productMapper, GiftPointService giftPoints, BookstoreProperties properties) {
        this.carts = carts;
        this.products = products;
        this.users = users;
        this.productMapper = productMapper;
        this.giftPoints = giftPoints;
        this.maxQuantityPerItem = properties.cart().maxQuantityPerItem();
    }

    @Transactional(readOnly = true)
    public CartResponse get(Long userId) {
        return carts.findDetailedByUserId(userId).map(this::toResponse).orElseGet(() -> toResponse(null, userId));
    }

    /** Adding a product that is already in the basket increases its quantity. */
    @Transactional
    public CartResponse add(Long userId, CartItemAddRequest request) {
        Product product = products.findById(request.productId())
                .orElseThrow(() -> ApiException.notFound("Product " + request.productId() + " not found"));
        requireActive(product);
        Cart cart = cartOf(userId);
        CartItem existing = find(cart, product.getId());
        long wanted = (long) request.quantity() + (existing == null ? 0 : existing.getQuantity());
        checkQuantity(product, wanted);
        if (existing == null) {
            cart.addItem(new CartItem(cart, product, (int) wanted));
        } else {
            existing.setQuantity((int) wanted);
        }
        carts.saveAndFlush(cart);
        return get(userId);
    }

    /** Sets the quantity of a line that is already in the basket. */
    @Transactional
    public CartResponse update(Long userId, Long productId, CartItemUpdateRequest request) {
        Cart cart = cartOf(userId);
        CartItem item = find(cart, productId);
        if (item == null) {
            throw notInBasket(productId);
        }
        requireActive(item.getProduct());
        checkQuantity(item.getProduct(), request.quantity());
        item.setQuantity(request.quantity());
        carts.saveAndFlush(cart);
        return get(userId);
    }

    @Transactional
    public CartResponse remove(Long userId, Long productId) {
        Cart cart = cartOf(userId);
        CartItem item = find(cart, productId);
        if (item == null) {
            throw notInBasket(productId);
        }
        cart.removeItem(item);
        carts.saveAndFlush(cart);
        return get(userId);
    }

    @Transactional
    public void clear(Long userId) {
        carts.findByUserId(userId).ifPresent(cart -> {
            cart.clear();
            carts.saveAndFlush(cart);
        });
    }

    // ----------------------------------------------------------------- helpers

    private Cart cartOf(Long userId) {
        return carts.findByUserId(userId)
                .orElseGet(() -> carts.saveAndFlush(new Cart(users.getReferenceById(userId))));
    }

    private static CartItem find(Cart cart, Long productId) {
        return cart.getItems().stream().filter(i -> i.getProduct().getId().equals(productId)).findFirst().orElse(null);
    }

    private static ApiException notInBasket(Long productId) {
        return ApiException.notFound("Product " + productId + " is not in the basket");
    }

    private static void requireActive(Product product) {
        if (!product.isActive()) {
            throw ApiException.conflict(ErrorCode.PRODUCT_INACTIVE, "Product " + product.getId() + " is not available");
        }
    }

    private void checkQuantity(Product product, long quantity) {
        if (quantity > maxQuantityPerItem) {
            throw ApiException.validation("quantity", "must be at most " + maxQuantityPerItem + " per product");
        }
        if (quantity > product.getStockQty()) {
            throw ApiException.conflict(ErrorCode.INSUFFICIENT_STOCK,
                    "Only " + product.getStockQty() + " copies of product " + product.getId() + " are in stock");
        }
    }

    private CartResponse toResponse(Cart cart) {
        return toResponse(cart, cart.getUser().getId());
    }

    private CartResponse toResponse(Cart cart, Long userId) {
        List<CartItem> items = cart == null ? List.of()
                : cart.getItems().stream().sorted(Comparator.comparing(CartItem::getId)).toList();
        List<CartResponse.Line> lines = items.stream().map(i -> {
            BigDecimal unit = i.getProduct().getPrice();
            return new CartResponse.Line(productMapper.toResponse(i.getProduct()), i.getQuantity(), unit,
                    unit.multiply(BigDecimal.valueOf(i.getQuantity())));
        }).toList();
        BigDecimal subtotal = lines.stream().map(CartResponse.Line::lineTotal)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        int itemCount = lines.stream().mapToInt(CartResponse.Line::quantity).sum();
        LocalDate delivery = items.stream().map(i -> productMapper.estimatedDelivery(i.getProduct()))
                .max(Comparator.naturalOrder()).orElse(null);
        int maxRedeemable = lines.isEmpty() ? 0 : giftPoints.maxRedeemable(subtotal, giftPoints.balanceOf(userId));
        return new CartResponse(lines, itemCount, subtotal, maxRedeemable, delivery, "INR");
    }
}
