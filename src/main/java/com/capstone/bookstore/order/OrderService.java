package com.capstone.bookstore.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.address.Address;
import com.capstone.bookstore.address.AddressService;
import com.capstone.bookstore.cart.Cart;
import com.capstone.bookstore.cart.CartItem;
import com.capstone.bookstore.cart.CartRepository;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.catalog.ProductMapper;
import com.capstone.bookstore.catalog.ProductRepository;
import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.ErrorCode;
import com.capstone.bookstore.common.PageResponse;
import com.capstone.bookstore.giftpoints.GiftPointService;
import com.capstone.bookstore.payment.Payment;
import com.capstone.bookstore.payment.PaymentRepository;
import com.capstone.bookstore.payment.PaymentStatus;
import com.capstone.bookstore.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order placement, history, Buy Again and cancellation (IBM steps 2a, 9, 10, 10a, 12, 12a).
 * <p>
 * Placement and cancellation each run in ONE transaction: stock, gift points, the order and the basket change
 * together or not at all. Every lookup includes the owner id, so another user's order is a 404.
 * Concurrency: stock is decremented with a conditional UPDATE (no overselling), the gift point account and the
 * order carry a version (no double redemption, no cancel-and-pay race), and the ledger has a unique
 * (order, type) constraint (no duplicate EARN / REDEEM / REFUND / REVERSE).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "placedAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;
    private final CartRepository carts;
    private final ProductRepository products;
    private final UserRepository users;
    private final AddressService addressService;
    private final GiftPointService giftPoints;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderItemRepository orderItems, PaymentRepository payments,
                        CartRepository carts, ProductRepository products, UserRepository users,
                        AddressService addressService, GiftPointService giftPoints, ProductMapper productMapper,
                        OrderMapper orderMapper, Clock clock) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
        this.carts = carts;
        this.products = products;
        this.users = users;
        this.addressService = addressService;
        this.giftPoints = giftPoints;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ place

    /** POST /orders: checkout step 1 of 2. The order is created PENDING_PAYMENT. */
    @Transactional
    public OrderResponse create(Long userId, CreateOrderRequest request) {
        Cart cart = carts.findDetailedByUserId(userId).filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.EMPTY_CART, "The basket is empty"));
        Address address = addressService.ownedAddress(userId, request.addressId());

        // Lines in product id order: stock rows are always updated in the same order, so checkouts cannot deadlock.
        List<CartItem> lines = cart.getItems().stream()
                .sorted(Comparator.comparing(i -> i.getProduct().getId())).toList();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        for (CartItem line : lines) {
            Product product = line.getProduct();
            if (!product.isActive()) {
                throw ApiException.conflict(ErrorCode.PRODUCT_INACTIVE,
                        "Product " + product.getId() + " is no longer available. Remove it from the basket");
            }
            subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
        }

        int points = request.pointsOrZero();
        if (points > 0) {
            int balance = giftPoints.balanceOf(userId);
            if (points > balance) {
                throw ApiException.badRequest(ErrorCode.INSUFFICIENT_GIFT_POINTS,
                        "Not enough gift points. Available: " + balance);
            }
            int cap = giftPoints.redemptionCap(subtotal);
            if (points > cap) {
                throw ApiException.badRequest(ErrorCode.POINTS_LIMIT_EXCEEDED,
                        "At most " + cap + " gift points can be redeemed on this order");
            }
        }

        for (CartItem line : lines) {
            if (products.decrementStock(line.getProduct().getId(), line.getQuantity()) == 0) {
                throw ApiException.conflict(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough stock for product " + line.getProduct().getId());
            }
        }

        Instant now = Instant.now(clock);
        // The readable number needs the generated id, so a unique placeholder is stored first.
        Order order = new Order(users.getReferenceById(userId), address,
                "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), now);
        LocalDate latestDelivery = null;
        for (CartItem line : lines) {
            LocalDate delivery = productMapper.estimatedDelivery(line.getProduct());
            order.addItem(new OrderItem(order, line.getProduct(), line.getQuantity(), delivery));
            if (latestDelivery == null || delivery.isAfter(latestDelivery)) {
                latestDelivery = delivery;
            }
        }
        order.setEstimatedDeliveryDate(latestDelivery);
        order.applyTotals(subtotal, points, giftPoints.discountFor(points));
        orders.saveAndFlush(order);
        order.setOrderNumber("BK-" + LocalDate.now(clock).format(ORDER_DATE) + "-" + String.format("%06d", order.getId()));

        giftPoints.redeem(userId, order, points);        // debits the account and writes the REDEEM ledger entry once
        cart.clear();
        carts.saveAndFlush(cart);

        log.info("Order placed orderId={} userId={} items={} points={}", order.getId(), userId, lines.size(), points);
        return orderMapper.toResponse(order, null);
    }

    // ---------------------------------------------------------------- history

    /** GET /orders: the user's orders, newest first, optionally filtered by status. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(Long userId, OrderStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        Page<Order> result = status == null ? orders.findByUserId(userId, pageable)
                : orders.findByUserIdAndStatus(userId, status, pageable);
        List<Long> ids = result.getContent().stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = ids.isEmpty() ? Map.of()
                : orderItems.findByOrderIdInOrderByIdAsc(ids).stream()
                        .collect(Collectors.groupingBy(i -> i.getOrder().getId()));
        return PageResponse.of(result, order -> {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            return new OrderSummary(order.getId(), order.getOrderNumber(), order.getStatus(),
                    items.stream().mapToInt(OrderItem::getQuantity).sum(),
                    items.isEmpty() ? null : items.get(0).getTitle(), order.getTotalAmount(), order.getPlacedAt(),
                    order.getEstimatedDeliveryDate(), orderMapper.isCancellable(order));
        });
    }

    /** GET /orders/{orderId}: order detail and purchase confirmation view. */
    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, Long orderId) {
        Order order = owned(userId, orderId);
        return orderMapper.toResponse(order, latestPayment(orderId));
    }

    /** GET /orders/buy-again: distinct products of non-cancelled orders, newest first, still active and in stock. */
    @Transactional(readOnly = true)
    public List<BuyAgainItem> buyAgain(Long userId, int limit) {
        List<Object[]> rows = orderItems.findBuyAgain(userId, PageRequest.of(0, limit));
        Map<Long, Product> byId = products
                .findWithCategoryAndBrandByIdIn(rows.stream().map(r -> (Long) r[0]).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return rows.stream()
                .map(r -> new BuyAgainItem(productMapper.toResponse(byId.get((Long) r[0])), ((Long) r[1]).intValue(),
                        (Instant) r[2]))
                .toList();
    }

    // ----------------------------------------------------------------- cancel

    /**
     * POST /orders/{orderId}/cancel. Allowed while the order is PENDING_PAYMENT or CONFIRMED and now is not after
     * placedAt + bookstore.order.cancellation-window-hours. One transaction: status, stock, gift points, payment.
     */
    @Transactional
    public OrderResponse cancel(Long userId, Long orderId, String reason) {
        Order order = owned(userId, orderId);
        if (!order.getStatus().isCancellable()) {
            throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATE,
                    "An order that is " + order.getStatus() + " cannot be cancelled");
        }
        if (!orderMapper.withinCancellationWindow(order)) {
            throw ApiException.conflict(ErrorCode.CANCELLATION_WINDOW_EXPIRED,
                    "Order can only be cancelled within " + hours(order) + " hours of placement");
        }
        order.markCancelled(Instant.now(clock), reason == null || reason.isBlank() ? null : reason.trim());

        order.getItems().stream().sorted(Comparator.comparing(i -> i.getProduct().getId()))
                .forEach(i -> products.incrementStock(i.getProduct().getId(), i.getQuantity()));
        giftPoints.settleCancellation(userId, order);
        payments.findByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS).forEach(p -> p.setStatus(PaymentStatus.REFUNDED));
        orders.saveAndFlush(order);

        log.info("Order cancelled orderId={} userId={}", orderId, userId);
        return orderMapper.toResponse(order, latestPayment(orderId));
    }

    // ---------------------------------------------------------------- helpers

    private Order owned(Long userId, Long orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    private Payment latestPayment(Long orderId) {
        return payments.findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId).orElse(null);
    }

    private long hours(Order order) {
        return java.time.Duration.between(order.getPlacedAt(), orderMapper.cancellableUntil(order)).toHours();
    }
}
