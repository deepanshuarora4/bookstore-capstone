package com.capstone.bookstore.order;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The whole customer journey over real HTTP-level calls (IBM slide 3): register, log in, browse, pick, basket,
 * address, order with gift points, pay, confirmation, history, Buy Again, cancel within 48 hours.
 * Also the gift point endpoint (IBM step 10a).
 */
class CheckoutJourneyIntegrationTest extends AbstractApiTest {

    @Autowired Clock clock;

    private JsonNode login(String email, String password) throws Exception {
        return ok(call(post("/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(map("email", email, "password", password)))), 200);
    }

    @Test
    void theCompleteCustomerJourneyFromRegistrationToCancellation() throws Exception {
        // 1-2. register and log in (a real account, a real BCrypt password, a real JWT)
        String email = "journey" + EMAIL_DOMAIN;
        ok(call(post("/auth/register").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(map("fullName", "Deepanshu Arora", "email", email, "password", "Journey123")))), 201);
        JsonNode session = login(email, "Journey123");
        String token = session.get("accessToken").asText();
        assertThat(session.get("user").get("role").asText()).isEqualTo("CUSTOMER");

        // fixtures for the catalogue: two books in the CHK category from the same publisher
        Product novel = product("Journey Novel", "450.00", 10, 2, true);
        Product sequel = product("Journey Sequel", "299.50", 10, 4, true);

        // 3, 5, 6. browse by category and by brand
        assertThat(titles(getJson("/products?categoryId=" + category.getId(), token).get("content")))
                .contains("CHK Journey Novel", "CHK Journey Sequel");
        assertThat(titles(getJson("/brands/" + brand.getId() + "/products", token).get("content")))
                .contains("CHK Journey Novel");

        // 7. select a product: tentative delivery date = today + dispatch days + transit days
        JsonNode selected = getJson("/products/" + novel.getId(), token);
        assertThat(LocalDate.parse(selected.get("estimatedDeliveryDate").asText()))
                .isEqualTo(LocalDate.now(clock).plusDays(2 + properties.delivery().transitDays()));

        // 7a. related products
        assertThat(ids(getJson("/products/" + novel.getId() + "/related", token))).contains(sequel.getId());

        // 8. basket
        ok(call(post("/cart/items"), token, map("productId", novel.getId(), "quantity", 2)), 201);
        JsonNode cart = ok(call(post("/cart/items"), token, map("productId", sequel.getId(), "quantity", 1)), 201);
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("1199.50");
        assertThat(cart.get("itemCount").asInt()).isEqualTo(3);

        // 9. delivery address
        long addressId = ok(call(post("/addresses"), token, map("label", "Home", "line1", "12 MG Road", "city", "Bengaluru",
                "state", "Karnataka", "postalCode", "560038")), 201).get("id").asLong();

        // the customer has some gift points from earlier purchases (10a)
        long userId = session.get("user").get("id").asLong();
        jdbc.update("UPDATE gift_point_accounts SET balance = 500 WHERE user_id = ?", userId);
        assertThat(getJson("/cart", token).get("maxRedeemablePoints").asInt()).isEqualTo(500);   // 50% of 1199.50 = 599

        // 10, 10a. place the order, redeeming 200 points
        JsonNode placed = ok(call(post("/orders"), token, map("addressId", addressId, "pointsToRedeem", 200)), 201);
        long orderId = placed.get("id").asLong();
        assertThat(placed.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(money(placed.get("subtotal"))).isEqualByComparingTo("1199.50");
        assertThat(money(placed.get("pointsDiscount"))).isEqualByComparingTo("200.00");
        assertThat(money(placed.get("totalAmount"))).isEqualByComparingTo("999.50");
        assertThat(getJson("/cart", token).get("items")).isEmpty();
        assertThat(stockOf(novel)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT balance FROM gift_point_accounts WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(300);

        // 10, 11. a declined card first, then a good one
        error(call(post("/orders/" + orderId + "/payments"), token, cardPayment(DECLINED_CARD)), 402, "PAYMENT_DECLINED");
        assertThat(getJson("/orders/" + orderId, token).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        JsonNode confirmation = ok(call(post("/orders/" + orderId + "/payments"), token, cardPayment(VALID_CARD)), 201);

        // 11, 12. payment confirmation and purchase confirmation
        assertThat(confirmation.get("payment").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(confirmation.get("payment").get("cardLast4").asText()).isEqualTo("1111");
        assertThat(money(confirmation.get("payment").get("amount"))).isEqualByComparingTo("999.50");
        assertThat(confirmation.get("order").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmation.get("pointsEarned").asInt()).isEqualTo(99);                       // floor(999.50 / 10)
        assertThat(confirmation.get("message").asText()).contains(placed.get("orderNumber").asText());

        // 10a. the gift point account reflects the redemption and the earning, with a ledger
        JsonNode points = getJson("/gift-points", token);
        assertThat(points.get("balance").asInt()).isEqualTo(500 - 200 + 99);
        assertThat(types(points)).containsExactlyInAnyOrder("REDEEM", "EARN");

        // 2a. order history and Buy Again
        JsonNode history = getJson("/orders", token);
        assertThat(history.get("content")).hasSize(1);
        assertThat(history.get("content").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(ids(getJson("/orders/buy-again", token))).containsExactlyInAnyOrder(novel.getId(), sequel.getId());

        // 12a. cancel within 48 hours: everything is given back
        JsonNode cancelled = ok(call(post("/orders/" + orderId + "/cancel"), token), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("payment").get("status").asText()).isEqualTo("REFUNDED");
        assertThat(stockOf(novel)).isEqualTo(10);
        assertThat(stockOf(sequel)).isEqualTo(10);
        JsonNode afterCancel = getJson("/gift-points", token);
        assertThat(afterCancel.get("balance").asInt()).isEqualTo(500);   // 500 - 200 redeemed + 99 earned + 200 refunded - 99 reversed
        assertThat(types(afterCancel)).containsExactlyInAnyOrder("REDEEM", "EARN", "REFUND", "REVERSE");
        assertThat(getJson("/orders/buy-again", token)).isEmpty();
    }

    // ------------------------------------------------------------- GET /gift-points

    @Test
    void aNewCustomerHasAnEmptyAccountAndSeesTheConfiguredRules() throws Exception {
        JsonNode points = getJson("/gift-points", token(customer("Newbie")));

        assertThat(points.get("balance").asInt()).isZero();
        JsonNode rules = points.get("rules");
        assertThat(rules.get("earnPointsPerUnit").asInt()).isEqualTo(properties.points().earnPointsPerUnit());
        assertThat(money(rules.get("earnSpendUnit"))).isEqualByComparingTo(properties.points().earnSpendUnit());
        assertThat(money(rules.get("pointValue"))).isEqualByComparingTo(properties.points().pointValue());
        assertThat(rules.get("maxRedemptionPercent").asInt()).isEqualTo(properties.points().maxRedemptionPercent());
        assertThat(points.get("transactions").get("content")).isEmpty();
        assertThat(points.get("transactions").get("totalElements").asLong()).isZero();
    }

    @Test
    void theLedgerIsNewestFirstPagedSignedAndLinkedToOrders() throws Exception {
        User user = user("Ledger", com.capstone.bookstore.user.Role.CUSTOMER, 300);
        Product book = product("Ledger Book", "400.00", 10);
        addToCart(user, book, 1);
        long orderId = placeOrder(user, addressFor(user), 100).get("id").asLong();
        pay(user, orderId, VALID_CARD);                                                     // pays 300 -> earns 30

        JsonNode points = getJson("/gift-points", token(user));
        JsonNode first = points.get("transactions").get("content").get(0);
        JsonNode second = points.get("transactions").get("content").get(1);

        assertThat(points.get("balance").asInt()).isEqualTo(300 - 100 + 30);
        assertThat(first.get("type").asText()).isEqualTo("EARN");
        assertThat(first.get("points").asInt()).isEqualTo(30);
        assertThat(first.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(second.get("type").asText()).isEqualTo("REDEEM");
        assertThat(second.get("points").asInt()).isEqualTo(-100);
        assertThat(getJson("/gift-points?size=1", token(user)).get("transactions").get("content")).hasSize(1);
        assertThat(getJson("/gift-points?size=1", token(user)).get("transactions").get("totalPages").asInt()).isEqualTo(2);
        assertThat(getJson("/gift-points?size=1&page=1", token(user)).get("transactions").get("content").get(0).get("type").asText())
                .isEqualTo("REDEEM");
    }

    @Test
    void aUserOnlySeesTheirOwnPointsAndLedger() throws Exception {
        User rich = user("Rich Points", com.capstone.bookstore.user.Role.CUSTOMER, 1000);
        User other = customer("Other Points");
        addToCart(rich, product("Rich Book", "400.00", 10), 1);
        placeOrder(rich, addressFor(rich), 100);

        JsonNode points = getJson("/gift-points", token(other));

        assertThat(points.get("balance").asInt()).isZero();
        assertThat(points.get("transactions").get("content")).isEmpty();
        assertThat(getJson("/gift-points", token(rich)).get("transactions").get("content")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gift-points?page=-1", "/gift-points?size=0", "/gift-points?size=101", "/gift-points?page=x"})
    void invalidPagingOnTheGiftPointLedgerIs400(String url) throws Exception {
        error(call(get(url), token(customer("Bad Paging"))), 400, "VALIDATION_FAILED");
    }

    @Test
    void theBalanceCanNeverBeDrivenNegativeThroughTheApi() throws Exception {
        User user = user("Never Negative", com.capstone.bookstore.user.Role.CUSTOMER, 50);
        long address = addressFor(user);
        addToCart(user, product("Negative Book", "1000.00", 10), 1);

        error(placeOrderRaw(user, address, 51), 400, "INSUFFICIENT_GIFT_POINTS");
        error(placeOrderRaw(user, address, 2147483647), 400, "INSUFFICIENT_GIFT_POINTS");
        placeOrder(user, address, 50);

        assertThat(balanceOf(user)).isZero();
        addToCart(user, product("Negative Book Two", "1000.00", 10), 1);
        error(placeOrderRaw(user, address, 1), 400, "INSUFFICIENT_GIFT_POINTS");
        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void twoSimultaneousOrdersCannotSpendTheSamePointsTwice() throws Exception {
        User user = user("Double Spender", com.capstone.bookstore.user.Role.CUSTOMER, 100);
        Product book = product("Double Book", "400.00", 10);
        long address = addressFor(user);
        // two checkouts race with the same basket content; each wants 80 of the 100 points
        addToCart(user, book, 1);

        List<Integer> statuses = concurrently(2, () -> placeOrderRaw(user, address, 80));

        assertThat(balanceOf(user)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM gift_point_transactions WHERE account_id IN "
                + "(SELECT id FROM gift_point_accounts WHERE user_id = ?) AND type = 'REDEEM'", Integer.class, user.getId()))
                .isGreaterThanOrEqualTo(-100);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isLessThanOrEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 400 || s == 409);
    }

    // ------------------------------------------------------------- helpers

    private static List<String> titles(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("title").asText()).toList();
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.has("product") ? n.get("product").get("id").asLong() : n.get("id").asLong()).toList();
    }

    private static List<String> types(JsonNode points) {
        return StreamSupport.stream(points.get("transactions").get("content").spliterator(), false)
                .map(n -> n.get("type").asText()).toList();
    }
}
