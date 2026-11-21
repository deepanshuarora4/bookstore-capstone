package com.capstone.bookstore.payment;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/** Simulated card payment (IBM steps 10, 11, 12): success, decline and retry, validation, card-data safety. */
@ExtendWith(OutputCaptureExtension.class)
class PaymentApiIntegrationTest extends AbstractApiTest {

    private static final String CARD = "4242424242424242";
    private static final String CVV = "987";

    @Autowired Clock clock;

    /** A user with a basket of one book of the given price, an order of it, and the order id. */
    private long pendingOrder(User user, String price, int points) throws Exception {
        addToCart(user, product("Pay " + price, price, 20), 1);
        return placeOrder(user, addressFor(user), points).get("id").asLong();
    }

    private Map<String, Object> payment(String card, String cvv) {
        Map<String, Object> m = cardPayment(card);
        m.put("cvv", cvv);
        return m;
    }

    // ------------------------------------------------------------- success

    @Test
    void successfulPaymentConfirmsTheOrderAndReturnsPaymentAndPurchaseConfirmation() throws Exception {
        User user = customer("Payer");
        long orderId = pendingOrder(user, "599.50", 0);

        JsonNode confirmation = ok(payRaw(user, orderId, payment(CARD, CVV)), 201);

        JsonNode payment = confirmation.get("payment");
        assertThat(payment.get("id").asLong()).isPositive();
        assertThat(payment.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payment.get("method").asText()).isEqualTo("CREDIT_CARD");
        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(money(payment.get("amount"))).isEqualByComparingTo("599.50");
        assertThat(payment.get("transactionRef").asText()).matches("TXN-[0-9A-F]{8}");
        assertThat(payment.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(payment.has("failureReason")).isFalse();
        assertThat(payment.has("createdAt")).isTrue();
        assertThat(confirmation.get("order").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmation.get("order").get("id").asLong()).isEqualTo(orderId);
        assertThat(confirmation.get("order").get("payment").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(confirmation.get("pointsEarned").asInt()).isEqualTo(59);          // floor(599.50 / 10) * 1
        assertThat(confirmation.get("order").get("pointsEarned").asInt()).isEqualTo(59);
        assertThat(confirmation.get("message").asText()).startsWith("Payment successful. Your order BK-")
                .endsWith("is confirmed.").contains(confirmation.get("order").get("orderNumber").asText());
        assertThat(balanceOf(user)).isEqualTo(59);
    }

    @Test
    void debitCardsAreAcceptedToo() throws Exception {
        User user = customer("Debit Payer");
        long orderId = pendingOrder(user, "100.00", 0);
        Map<String, Object> request = payment(CARD, CVV);
        request.put("method", "DEBIT_CARD");

        JsonNode confirmation = ok(payRaw(user, orderId, request), 201);

        assertThat(confirmation.get("payment").get("method").asText()).isEqualTo("DEBIT_CARD");
    }

    @Test
    void getPaymentAndGetOrderShowTheConfirmedPaymentAfterwards() throws Exception {
        User user = customer("Checker");
        long orderId = pendingOrder(user, "250.00", 0);
        pay(user, orderId, CARD);

        JsonNode payment = getJson("/orders/" + orderId + "/payment", token(user));
        JsonNode order = order(user, orderId);

        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payment.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("payment").get("id").asLong()).isEqualTo(payment.get("id").asLong());
        assertThat(order.get("pointsEarned").asInt()).isEqualTo(25);
        assertThat(getJson("/orders", token(user)).get("content").get(0).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void pointsAreEarnedOnThePaidAmountAfterRedemptionAndOnlyOnce() throws Exception {
        User user = user("Redeem And Earn", Role.CUSTOMER, 100);
        long orderId = pendingOrder(user, "400.00", 80);                 // total 320.00 -> 32 points

        JsonNode confirmation = pay(user, orderId, CARD);

        assertThat(confirmation.get("pointsEarned").asInt()).isEqualTo(32);
        assertThat(balanceOf(user)).isEqualTo(100 - 80 + 32);
        assertThat(ledgerCount(orderId, "REDEEM")).isEqualTo(1);
        assertThat(ledgerCount(orderId, "EARN")).isEqualTo(1);
        error(payRaw(user, orderId, payment(CARD, CVV)), 409, "INVALID_ORDER_STATE");
        assertThat(balanceOf(user)).isEqualTo(52);
        assertThat(ledgerCount(orderId, "EARN")).isEqualTo(1);
    }

    @Test
    void anAmountBelowOneSpendUnitEarnsNothingAndWritesNoLedgerEntry() throws Exception {
        User user = customer("Cheap Buyer");
        long orderId = pendingOrder(user, "9.99", 0);

        JsonNode confirmation = pay(user, orderId, CARD);

        assertThat(confirmation.get("pointsEarned").asInt()).isZero();
        assertThat(ledgerCount(orderId, "EARN")).isZero();
        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void stockStaysReducedAfterPayment() throws Exception {
        User user = customer("Stock After Pay");
        Product book = product("Stocked", "100.00", 5);
        addToCart(user, book, 2);
        long orderId = placeOrder(user, addressFor(user), 0).get("id").asLong();

        pay(user, orderId, CARD);

        assertThat(stockOf(book)).isEqualTo(3);
    }

    // ------------------------------------------------------------- decline and retry

    @Test
    void aDeclinedCardIs402KeepsAFailedRecordAndLeavesTheOrderPayable() throws Exception {
        User user = customer("Declined");
        long orderId = pendingOrder(user, "300.00", 0);

        JsonNode b = error(payRaw(user, orderId, payment(DECLINED_CARD, CVV)), 402, "PAYMENT_DECLINED");

        assertThat(b.get("error").asText()).isEqualTo("Payment Required");
        assertThat(b.get("path").asText()).isEqualTo("/api/v1/orders/" + orderId + "/payments");
        JsonNode latest = getJson("/orders/" + orderId + "/payment", token(user));
        assertThat(latest.get("status").asText()).isEqualTo("FAILED");
        assertThat(latest.get("cardLast4").asText()).isEqualTo("0002");
        assertThat(latest.get("failureReason").asText()).isNotBlank();
        assertThat(latest.has("transactionRef")).isFalse();
        JsonNode order = order(user, orderId);
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("pointsEarned").asInt()).isZero();
        assertThat(order.get("cancellable").asBoolean()).isTrue();
        assertThat(ledgerCount(orderId, "EARN")).isZero();
        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void afterADeclineTheCustomerCanRetryWithAnotherCardAndSucceed() throws Exception {
        User user = customer("Retrier");
        long orderId = pendingOrder(user, "300.00", 0);
        error(payRaw(user, orderId, payment(DECLINED_CARD, CVV)), 402, "PAYMENT_DECLINED");
        error(payRaw(user, orderId, payment(DECLINED_CARD, CVV)), 402, "PAYMENT_DECLINED");

        JsonNode confirmation = pay(user, orderId, CARD);

        assertThat(confirmation.get("payment").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(order(user, orderId).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'FAILED'",
                Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'SUCCESS'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(getJson("/orders/" + orderId + "/payment", token(user)).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(balanceOf(user)).isEqualTo(30);
    }

    // ------------------------------------------------------------- validation

    @Test
    void aCardThatFailsTheLuhnCheckIs400InvalidPaymentDetailsAndNothingIsRecorded() throws Exception {
        User user = customer("Typo");
        long orderId = pendingOrder(user, "100.00", 0);

        JsonNode b = error(payRaw(user, orderId, payment("4242424242424241", CVV)), 400, "INVALID_PAYMENT_DETAILS");

        assertThat(b.toString()).doesNotContain("4242424242424241");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isZero();
        assertThat(order(user, orderId).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void expiryIsCheckedAgainstTheServerClockMonthByMonth() throws Exception {
        User user = customer("Expiry");
        long orderId = pendingOrder(user, "100.00", 0);
        YearMonth now = YearMonth.now(clock);

        for (YearMonth past : List.of(now.minusMonths(1), now.minusYears(1), YearMonth.of(2000, 1))) {
            Map<String, Object> request = payment(CARD, CVV);
            request.put("expiryMonth", past.getMonthValue());
            request.put("expiryYear", past.getYear());
            error(payRaw(user, orderId, request), 400, "INVALID_PAYMENT_DETAILS");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isZero();

        Map<String, Object> thisMonth = payment(CARD, CVV);                  // valid through the last day of this month
        thisMonth.put("expiryMonth", now.getMonthValue());
        thisMonth.put("expiryYear", now.getYear());
        ok(payRaw(user, orderId, thisMonth), 201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cardNumber=abcdefghijklmnop", "cardNumber=4242", "cardNumber=42424242424242424242",
            "cardNumber=4242 4242 4242 4242", "cardNumber=", "cvv=12", "cvv=12345", "cvv=abc", "cvv=",
            "cardHolderName=A", "cardHolderName=", "expiryMonth=0", "expiryMonth=13", "expiryYear=999",
            "expiryYear=10000"})
    void malformedCardFieldsAreRejectedWithTheFieldNameAndNeverEchoTheValue(String override) throws Exception {
        User user = customer("Malformed");
        long orderId = pendingOrder(user, "100.00", 0);
        String field = override.substring(0, override.indexOf('='));
        String value = override.substring(override.indexOf('=') + 1);
        Map<String, Object> request = payment(CARD, CVV);
        request.put(field, field.startsWith("expiry") ? Integer.valueOf(value) : value);

        MvcResult result = payRaw(user, orderId, request);

        JsonNode b = error(result, 400, "VALIDATION_FAILED");
        assertThat(StreamSupport.stream(b.get("fieldErrors").spliterator(), false).map(e -> e.get("field").asText()))
                .contains(field);
        if (!value.isEmpty()) {
            // the field-error text is fixed wording; it must not quote what was rejected
            assertThat(StreamSupport.stream(b.get("fieldErrors").spliterator(), false)
                    .map(e -> e.get("message").asText()).toList()).noneMatch(m -> m.contains(value));
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContain(CARD);
        assertNoCvv(result.getResponse().getContentAsString(), CVV);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isZero();
    }

    @Test
    void missingFieldsAreAllReported() throws Exception {
        User user = customer("Blank");
        long orderId = pendingOrder(user, "100.00", 0);

        JsonNode b = error(payRaw(user, orderId, Map.of()), 400, "VALIDATION_FAILED");

        assertThat(StreamSupport.stream(b.get("fieldErrors").spliterator(), false).map(e -> e.get("field").asText()))
                .contains("method", "cardHolderName", "cardNumber", "expiryMonth", "expiryYear", "cvv");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASH", "UPI", "NET_BANKING", "WALLET", "GIFT_CARD", "credit_card", ""})
    void onlyCreditAndDebitCardsAreSupported(String method) throws Exception {
        User user = customer("Method " + method.length());
        long orderId = pendingOrder(user, "100.00", 0);
        Map<String, Object> request = payment(CARD, CVV);
        request.put("method", method);

        error(payRaw(user, orderId, request), 400, "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isZero();
    }

    @Test
    void anAmountInTheRequestCannotChangeWhatIsCharged() throws Exception {
        User user = customer("Cheat");
        long orderId = pendingOrder(user, "500.00", 0);
        Map<String, Object> request = payment(CARD, CVV);
        request.put("amount", 1);
        request.put("status", "SUCCESS");

        JsonNode confirmation = ok(payRaw(user, orderId, request), 201);

        assertThat(money(confirmation.get("payment").get("amount"))).isEqualByComparingTo("500.00");
    }

    // ------------------------------------------------------------- state and ownership

    @Test
    void payingAnAlreadyPaidOrderIs409AndDoesNotChargeTwice() throws Exception {
        User user = customer("Double Payer");
        long orderId = pendingOrder(user, "100.00", 0);
        pay(user, orderId, CARD);

        error(payRaw(user, orderId, payment(CARD, CVV)), 409, "INVALID_ORDER_STATE");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void anInvalidCardOnAnAlreadyPaidOrderIsStill409NotAValidationError() throws Exception {
        User user = customer("State First");
        long orderId = pendingOrder(user, "100.00", 0);
        pay(user, orderId, CARD);

        error(payRaw(user, orderId, payment("4242424242424241", CVV)), 409, "INVALID_ORDER_STATE");
    }

    @Test
    void aCancelledOrderCannotBePaid() throws Exception {
        User user = customer("Cancel Then Pay");
        long orderId = pendingOrder(user, "100.00", 0);
        ok(call(post("/orders/" + orderId + "/cancel"), token(user)), 200);

        error(payRaw(user, orderId, payment(CARD, CVV)), 409, "INVALID_ORDER_STATE");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isZero();
    }

    @Test
    void anotherUsersOrderIs404ForPaymentAndForReadingThePayment() throws Exception {
        User owner = customer("Owner");
        User mallory = customer("Mallory Pay");
        long orderId = pendingOrder(owner, "100.00", 0);

        error(payRaw(mallory, orderId, payment(CARD, CVV)), 404, "NOT_FOUND");
        pay(owner, orderId, CARD);
        error(call(get("/orders/" + orderId + "/payment"), token(mallory)), 404, "NOT_FOUND");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void paymentOfAnUnknownOrderIs404AndAnOrderWithoutPaymentHasNoPaymentResource() throws Exception {
        User user = customer("Unknown");
        long orderId = pendingOrder(user, "100.00", 0);

        error(payRaw(user, 987654321L, payment(CARD, CVV)), 404, "NOT_FOUND");
        error(call(get("/orders/" + orderId + "/payment"), token(user)), 404, "NOT_FOUND");
    }

    @Test
    void twoSimultaneousPaymentsOfTheSameOrderChargeAndRewardOnlyOnce() throws Exception {
        User user = customer("Racing Payer");
        long orderId = pendingOrder(user, "1000.00", 0);

        List<Integer> statuses = concurrently(4, () -> payRaw(user, orderId, payment(CARD, CVV)));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'SUCCESS'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(ledgerCount(orderId, "EARN")).isEqualTo(1);
        assertThat(balanceOf(user)).isEqualTo(100);
    }

    // ------------------------------------------------------------- card data safety

    @Test
    void theFullCardNumberAndCvvAreNeverStoredReturnedOrLogged(CapturedOutput output) throws Exception {
        User user = customer("Secret Keeper");
        long orderId = pendingOrder(user, "100.00", 0);
        String declinedFlow = DECLINED_CARD;

        MvcResult declined = payRaw(user, orderId, payment(declinedFlow, "654"));
        MvcResult invalid = payRaw(user, orderId, payment("4242424242424241", "321"));
        MvcResult success = payRaw(user, orderId, payment(CARD, CVV));
        MvcResult read = call(get("/orders/" + orderId + "/payment"), token(user));
        MvcResult orderRead = call(get("/orders/" + orderId), token(user));

        for (MvcResult result : List.of(declined, invalid, success, read, orderRead)) {
            String text = result.getResponse().getContentAsString();
            assertThat(text).doesNotContain(CARD).doesNotContain(declinedFlow).doesNotContain("4242424242424241")
                    .doesNotContain("\"cvv\"").doesNotContain("\"cardNumber\"");
            assertNoCvv(text, CVV, "654", "321");
        }
        // the database: no column for it, and no value anywhere in the payment rows
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE LOWER(table_name) = 'payments'", String.class).stream().map(String::toLowerCase).toList();
        assertThat(columns).doesNotContain("card_number", "cardnumber", "cvv", "cvc", "pan");
        String rows = jdbc.queryForList("SELECT * FROM payments WHERE order_id = ?", orderId).toString();
        assertThat(rows).doesNotContain(CARD).doesNotContain(declinedFlow);
        jdbc.queryForList("SELECT * FROM payments WHERE order_id = ?", orderId).forEach(row -> row.values()
                .forEach(v -> assertThat(String.valueOf(v)).isNotIn(CVV, "654", "321")));
        assertThat(rows).contains("4242").contains("0002");                     // only the last four digits
        // the log
        String log = output.getAll();
        assertThat(log).contains("Payment succeeded").contains("Payment declined");
        assertThat(log).doesNotContain(CARD).doesNotContain(declinedFlow).doesNotContain("4242424242424241");
        assertNoCvv(log, CVV, "654", "321");
    }

    /** A 3-digit CVV occurs by chance inside timestamps and ids, so check it by key and as an exact quoted value. */
    private static void assertNoCvv(String text, String... cvvs) {
        for (String cvv : cvvs) {
            assertThat(text).doesNotContainPattern("(?i)cvv\\W{0,6}" + cvv).doesNotContain("\"" + cvv + "\"");
        }
    }

    @Test
    void thePaymentRequestMasksTheCardNumberAndCvvInToString() {
        PaymentRequest request = new PaymentRequest(PaymentMethod.CREDIT_CARD, "Deepanshu Arora", CARD, 12, 2031, CVV);

        assertThat(request.toString()).doesNotContain(CARD).doesNotContain(CVV).contains("***");
    }

    @Test
    void theLuhnCheckAcceptsKnownGoodNumbersAndRejectsABrokenCheckDigit() {
        for (String good : List.of("4111111111111111", "4242424242424242", "4000000000000002", "5555555555554444",
                "378282246310005", "79927398713")) {
            assertThat(PaymentService.passesLuhn(good)).as(good).isTrue();
        }
        for (String bad : List.of("4111111111111112", "4242424242424241", "79927398710", "1234567890123456")) {
            assertThat(PaymentService.passesLuhn(bad)).as(bad).isFalse();
        }
    }
}
