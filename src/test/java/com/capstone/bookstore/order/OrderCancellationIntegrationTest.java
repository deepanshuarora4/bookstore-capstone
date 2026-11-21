package com.capstone.bookstore.order;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/** Cancellation within the window (IBM step 12a): stock, gift points, payment refund, state rules, concurrency. */
class OrderCancellationIntegrationTest extends AbstractApiTest {

    private long place(User user, Product book, int quantity, int points) throws Exception {
        addToCart(user, book, quantity);
        return placeOrder(user, addressFor(user), points).get("id").asLong();
    }

    private JsonNode cancel(User user, long orderId) throws Exception {
        return ok(call(post("/orders/" + orderId + "/cancel"), token(user)), 200);
    }

    // ------------------------------------------------------------- unpaid orders

    @Test
    void cancellingAnUnpaidOrderRestoresStockAndRefundsRedeemedPoints() throws Exception {
        User user = user("Canceller", Role.CUSTOMER, 100);
        Product book = product("Alpha", "400.00", 10);
        long orderId = place(user, book, 2, 80);
        assertThat(stockOf(book)).isEqualTo(8);
        assertThat(balanceOf(user)).isEqualTo(20);

        JsonNode cancelled = ok(call(post("/orders/" + orderId + "/cancel"), token(user), Map.of("reason", "Changed my mind")), 200);

        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancellable").asBoolean()).isFalse();
        assertThat(Instant.parse(cancelled.get("cancelledAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(stockOf(book)).isEqualTo(10);
        assertThat(balanceOf(user)).isEqualTo(100);
        assertThat(ledgerCount(orderId, "REDEEM")).isEqualTo(1);
        assertThat(ledgerCount(orderId, "REFUND")).isEqualTo(1);
        assertThat(ledgerCount(orderId, "REVERSE")).isZero();
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("Changed my mind");
        assertThat(order(user, orderId).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void theRequestBodyIsOptionalAndAnEmptyJsonObjectWorksToo() throws Exception {
        User user = customer("No Body");
        long first = place(user, product("Beta", "100.00", 10), 1, 0);
        long second = place(user, product("Gamma", "100.00", 10), 1, 0);

        assertThat(cancel(user, first).get("status").asText()).isEqualTo("CANCELLED");
        ok(call(post("/orders/" + second + "/cancel"), token(user), Map.of()), 200);
    }

    @Test
    void aTooLongReasonIs400AndTheOrderStaysOpen() throws Exception {
        User user = customer("Rambler");
        long orderId = place(user, product("Delta", "100.00", 10), 1, 0);

        error(call(post("/orders/" + orderId + "/cancel"), token(user), Map.of("reason", "x".repeat(256))),
                400, "VALIDATION_FAILED");

        assertThat(order(user, orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    // ------------------------------------------------------------- paid orders

    @Test
    void cancellingAPaidOrderRefundsThePaymentReversesEarnedPointsAndRestoresEverything() throws Exception {
        User user = user("Paid Canceller", Role.CUSTOMER, 100);
        Product book = product("Epsilon", "400.00", 10);
        long orderId = place(user, book, 1, 80);                          // total 320 -> earns 32
        pay(user, orderId, VALID_CARD);
        assertThat(balanceOf(user)).isEqualTo(52);                        // 100 - 80 + 32

        JsonNode cancelled = cancel(user, orderId);

        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("payment").get("status").asText()).isEqualTo("REFUNDED");
        assertThat(getJson("/orders/" + orderId + "/payment", token(user)).get("status").asText()).isEqualTo("REFUNDED");
        assertThat(stockOf(book)).isEqualTo(10);
        assertThat(balanceOf(user)).isEqualTo(100);                       // 52 + 80 refunded - 32 reversed
        assertThat(ledgerCount(orderId, "REFUND")).isEqualTo(1);
        assertThat(ledgerCount(orderId, "REVERSE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT points FROM gift_point_transactions WHERE order_id = ? AND type = 'REVERSE'",
                Integer.class, orderId)).isEqualTo(-32);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void reversalOfEarnedPointsIsCappedAtTheCurrentBalanceSoItNeverGoesNegative() throws Exception {
        User user = customer("Spender");
        long first = place(user, product("Zeta", "1000.00", 10), 1, 0);
        pay(user, first, VALID_CARD);                                      // earns 100
        assertThat(balanceOf(user)).isEqualTo(100);
        // the customer spends 80 of those points on a second order
        long second = place(user, product("Eta", "400.00", 10), 1, 80);
        assertThat(balanceOf(user)).isEqualTo(20);

        JsonNode cancelled = cancel(user, first);                          // 100 should be reversed, only 20 remain

        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(balanceOf(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT points FROM gift_point_transactions WHERE order_id = ? AND type = 'REVERSE'",
                Integer.class, first)).isEqualTo(-20);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void whenNoPointsRemainToReverseNoLedgerEntryIsWrittenAndTheBalanceStaysZero() throws Exception {
        User user = customer("Fully Spent");
        long first = place(user, product("Theta", "100.00", 10), 1, 0);
        pay(user, first, VALID_CARD);                                      // earns 10
        place(user, product("Iota", "100.00", 10), 1, 10);                 // spends all 10
        assertThat(balanceOf(user)).isZero();

        cancel(user, first);

        assertThat(balanceOf(user)).isZero();
        assertThat(ledgerCount(first, "REVERSE")).isZero();
    }

    @Test
    void anOrderPaidAfterAFailedAttemptIsRefundedExactlyOnce() throws Exception {
        User user = customer("Retry Then Cancel");
        long orderId = place(user, product("Kappa", "100.00", 10), 1, 0);
        payRaw(user, orderId, cardPayment(DECLINED_CARD));
        pay(user, orderId, VALID_CARD);

        cancel(user, orderId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'REFUNDED'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'FAILED'",
                Integer.class, orderId)).isEqualTo(1);
    }

    // ------------------------------------------------------------- the 48-hour window

    @ParameterizedTest(name = "placed {0}h ago -> cancel {1}")
    @CsvSource({"0,200", "1,200", "24,200", "47,200", "48,409", "49,409", "72,409", "1000,409"})
    void cancellationIsAllowedWithinTheWindowAndRefusedAfterIt(long hoursAgo, int expectedStatus) throws Exception {
        User user = customer("Window " + hoursAgo);
        Product book = product("Window", "100.00", 10);
        long orderId = place(user, book, 1, 0);
        // exactly 48h ago has already passed by the time the request runs, so it is refused; 47h is safely inside
        placedHoursAgo(orderId, hoursAgo);

        var result = call(post("/orders/" + orderId + "/cancel"), token(user));

        if (expectedStatus == 200) {
            assertThat(ok(result, 200).get("status").asText()).isEqualTo("CANCELLED");
            assertThat(stockOf(book)).isEqualTo(10);
        } else {
            error(result, 409, "CANCELLATION_WINDOW_EXPIRED");
            assertThat(order(user, orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(stockOf(book)).isEqualTo(9);
        }
    }

    @Test
    void anExpiredWindowChangesNothingAndTheOrderReportsItIsNoLongerCancellable() throws Exception {
        User user = user("Too Late", Role.CUSTOMER, 100);
        Product book = product("Lambda", "400.00", 10);
        long orderId = place(user, book, 1, 80);
        pay(user, orderId, VALID_CARD);
        placedHoursAgo(orderId, 60);
        int balance = balanceOf(user);

        JsonNode b = error(call(post("/orders/" + orderId + "/cancel"), token(user)), 409, "CANCELLATION_WINDOW_EXPIRED");

        assertThat(b.get("message").asText()).contains("48 hours");
        JsonNode order = order(user, orderId);
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(order.get("payment").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(balanceOf(user)).isEqualTo(balance);
        assertThat(stockOf(book)).isEqualTo(9);
        assertThat(getJson("/orders", token(user)).get("content").get(0).get("cancellable").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------- state rules

    @Test
    void cancellingTwiceIs409InvalidOrderStateAndNothingIsRestoredTwice() throws Exception {
        User user = user("Twice", Role.CUSTOMER, 100);
        Product book = product("Mu", "400.00", 10);
        long orderId = place(user, book, 1, 80);
        cancel(user, orderId);

        error(call(post("/orders/" + orderId + "/cancel"), token(user)), 409, "INVALID_ORDER_STATE");

        assertThat(stockOf(book)).isEqualTo(10);
        assertThat(balanceOf(user)).isEqualTo(100);
        assertThat(ledgerCount(orderId, "REFUND")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SHIPPED", "DELIVERED"})
    void shippedAndDeliveredOrdersCannotBeCancelledEvenInsideTheWindow(String status) throws Exception {
        User user = customer("Shipped " + status);
        Product book = product("Nu", "100.00", 10);
        long orderId = place(user, book, 1, 0);
        pay(user, orderId, VALID_CARD);
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status, orderId);

        error(call(post("/orders/" + orderId + "/cancel"), token(user)), 409, "INVALID_ORDER_STATE");

        assertThat(order(user, orderId).get("status").asText()).isEqualTo(status);
        assertThat(order(user, orderId).get("cancellable").asBoolean()).isFalse();
        assertThat(stockOf(book)).isEqualTo(9);
    }

    @Test
    void anExpiredAndShippedOrderReportsTheStateErrorFirst() throws Exception {
        User user = customer("Both");
        long orderId = place(user, product("Xi", "100.00", 10), 1, 0);
        pay(user, orderId, VALID_CARD);
        jdbc.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", orderId);
        placedHoursAgo(orderId, 500);

        error(call(post("/orders/" + orderId + "/cancel"), token(user)), 409, "INVALID_ORDER_STATE");
    }

    @Test
    void aCancelledOrderCannotBePaidAndCancelledAgainAfterPaymentAttempt() throws Exception {
        User user = customer("Pay After Cancel");
        long orderId = place(user, product("Omicron", "100.00", 10), 1, 0);
        cancel(user, orderId);

        error(payRaw(user, orderId, cardPayment(VALID_CARD)), 409, "INVALID_ORDER_STATE");
        error(call(post("/orders/" + orderId + "/cancel"), token(user)), 409, "INVALID_ORDER_STATE");
    }

    @Test
    void anotherUsersOrderCannotBeCancelledAndAdminsHaveNoBypass() throws Exception {
        User owner = customer("Cancel Owner");
        User mallory = customer("Cancel Mallory");
        User admin = user("Cancel Admin", Role.ADMIN, 0);
        Product book = product("Pi", "100.00", 10);
        long orderId = place(owner, book, 1, 0);

        error(call(post("/orders/" + orderId + "/cancel"), token(mallory)), 404, "NOT_FOUND");
        error(call(post("/orders/" + orderId + "/cancel"), token(admin)), 404, "NOT_FOUND");
        error(call(post("/orders/987654321/cancel"), token(owner)), 404, "NOT_FOUND");

        assertThat(order(owner, orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(stockOf(book)).isEqualTo(9);
    }

    @Test
    void theCancellationWindowFollowsTheConfiguration() throws Exception {
        // 48 is the documented default; the rule is read from bookstore.order.cancellation-window-hours
        assertThat(properties.order().cancellationWindowHours()).isEqualTo(48);
        User user = customer("Config");
        long orderId = place(user, product("Rho", "100.00", 10), 1, 0);
        JsonNode order = order(user, orderId);

        Instant placed = Instant.parse(order.get("placedAt").asText());
        Instant until = Instant.parse(order.get("cancellableUntil").asText());
        assertThat(until).isEqualTo(placed.plusSeconds(properties.order().cancellationWindowHours() * 3600L));
    }

    // ------------------------------------------------------------- concurrency

    @Test
    void simultaneousCancellationsRestoreStockAndPointsExactlyOnce() throws Exception {
        User user = user("Racing Canceller", Role.CUSTOMER, 100);
        Product book = product("Sigma", "400.00", 10);
        long orderId = place(user, book, 2, 80);

        List<Integer> statuses = concurrently(4, () -> call(post("/orders/" + orderId + "/cancel"), token(user)));

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
        assertThat(stockOf(book)).isEqualTo(10);
        assertThat(balanceOf(user)).isEqualTo(100);
        assertThat(ledgerCount(orderId, "REFUND")).isEqualTo(1);
    }

    @Test
    void aCancellationRacingAPaymentLeavesAConsistentOrder() throws Exception {
        for (int round = 0; round < 3; round++) {
            User user = user("Pay Cancel Race " + round, Role.CUSTOMER, 100);
            Product book = product("Tau" + round, "400.00", 10);
            long orderId = place(user, book, 1, 80);
            java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

            List<Integer> statuses = concurrently(2, () -> turn.getAndIncrement() == 0
                    ? call(post("/orders/" + orderId + "/cancel"), token(user))
                    : payRaw(user, orderId, cardPayment(VALID_CARD)));

            String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
            int balance = balanceOf(user);
            int stock = stockOf(book);
            assertThat(statuses).as("statuses " + statuses + " order " + status).hasSize(2);
            if (status.equals("CANCELLED")) {
                assertThat(stock).as("cancelled -> stock back").isEqualTo(10);
                assertThat(balance).as("cancelled -> points back").isEqualTo(100);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'SUCCESS'",
                        Integer.class, orderId)).as("no live payment on a cancelled order").isZero();
            } else {
                assertThat(status).isEqualTo("CONFIRMED");
                assertThat(stock).isEqualTo(9);
                assertThat(balance).isEqualTo(100 - 80 + 32);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'SUCCESS'",
                        Integer.class, orderId)).isEqualTo(1);
            }
            assertThat(balance).isGreaterThanOrEqualTo(0);
        }
    }

    // ------------------------------------------------------------- Buy Again after cancel

    @Test
    void aCancelledOrderNoLongerAppearsInBuyAgain() throws Exception {
        User user = customer("Buy Again Cancel");
        Product book = product("Upsilon", "100.00", 10);
        long orderId = place(user, book, 1, 0);
        assertThat(getJson("/orders/buy-again", token(user))).hasSize(1);

        cancel(user, orderId);

        assertThat(getJson("/orders/buy-again", token(user))).isEmpty();
        assertThat(getJson("/orders?status=CANCELLED", token(user)).get("content")).hasSize(1);
        assertThat(get("/orders")).isNotNull();
    }
}
