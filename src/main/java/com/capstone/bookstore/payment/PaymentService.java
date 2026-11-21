package com.capstone.bookstore.payment;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.ErrorCode;
import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.giftpoints.GiftPointService;
import com.capstone.bookstore.order.Order;
import com.capstone.bookstore.order.OrderMapper;
import com.capstone.bookstore.order.OrderRepository;
import com.capstone.bookstore.order.OrderStatus;

/**
 * Simulated card payment (IBM steps 10, 11, 12). There is no real gateway: a card number ending with
 * {@code bookstore.payment.decline-card-suffix} is declined, every other valid card succeeds.
 * <p>
 * SECURITY: the full card number and CVV are only read from the request object to validate and to take the last
 * four digits. They are never stored, logged, returned or put in an error message.
 * <p>
 * A decline commits the FAILED payment record (the exception is excluded from rollback) and leaves the order
 * PENDING_PAYMENT, so the customer can retry. A success commits payment SUCCESS, order CONFIRMED and the earned
 * gift points in one transaction. The order row version makes a concurrent cancel or second payment fail with 409.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final GiftPointService giftPoints;
    private final OrderMapper orderMapper;
    private final Clock clock;
    private final String declineSuffix;

    public PaymentService(OrderRepository orders, PaymentRepository payments, GiftPointService giftPoints,
                          OrderMapper orderMapper, Clock clock, BookstoreProperties properties) {
        this.orders = orders;
        this.payments = payments;
        this.giftPoints = giftPoints;
        this.orderMapper = orderMapper;
        this.clock = clock;
        this.declineSuffix = properties.payment().declineCardSuffix();
    }

    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public PaymentConfirmationResponse pay(Long userId, Long orderId, PaymentRequest request) {
        Order order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATE,
                    "Payment is only possible for an order that is PENDING_PAYMENT, not " + order.getStatus());
        }
        validateCard(request);

        String last4 = request.cardNumber().substring(request.cardNumber().length() - 4);
        if (request.cardNumber().endsWith(declineSuffix)) {
            payments.saveAndFlush(new Payment(order, request.method(), PaymentStatus.FAILED, order.getTotalAmount(),
                    null, last4, "Card declined by the issuer"));
            log.info("Payment declined orderId={} userId={}", orderId, userId);
            throw new PaymentDeclinedException("Payment was declined by the issuer");
        }

        Payment payment = payments.saveAndFlush(new Payment(order, request.method(), PaymentStatus.SUCCESS,
                order.getTotalAmount(), newTransactionRef(), last4, null));
        order.setStatus(OrderStatus.CONFIRMED);
        int earned = giftPoints.earn(userId, order, order.getTotalAmount());
        order.setPointsEarned(earned);
        orders.saveAndFlush(order);

        log.info("Payment succeeded paymentId={} orderId={} userId={} pointsEarned={}", payment.getId(), orderId, userId, earned);
        return new PaymentConfirmationResponse(PaymentResponse.from(payment), orderMapper.toResponse(order, payment),
                earned, "Payment successful. Your order " + order.getOrderNumber() + " is confirmed.");
    }

    /** GET /orders/{orderId}/payment: the most recent attempt of one of the user's own orders. */
    @Transactional(readOnly = true)
    public PaymentResponse latest(Long userId, Long orderId) {
        orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        return payments.findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId).map(PaymentResponse::from)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " has no payment yet"));
    }

    // -------------------------------------------------------------- validation

    /** Luhn check and expiry. Messages are fixed text: they never contain card data. */
    private void validateCard(PaymentRequest request) {
        if (!passesLuhn(request.cardNumber())) {
            throw ApiException.badRequest(ErrorCode.INVALID_PAYMENT_DETAILS, "The card number is not valid");
        }
        YearMonth expiry = YearMonth.of(request.expiryYear(), request.expiryMonth());
        if (expiry.isBefore(YearMonth.now(clock))) {
            throw ApiException.badRequest(ErrorCode.INVALID_PAYMENT_DETAILS, "The card has expired");
        }
    }

    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static String newTransactionRef() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "TXN-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
