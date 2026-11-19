package com.capstone.bookstore.cart;

import static com.capstone.bookstore.TestSupport.delete;
import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static com.capstone.bookstore.TestSupport.put;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;

/** Basket (IBM step 8): add, update, remove, clear, totals, limits and per-user isolation. */
class CartApiIntegrationTest extends AbstractApiTest {

    @Autowired Clock clock;

    private JsonNode add(String token, Product product, int quantity) throws Exception {
        return ok(call(post("/cart/items"), token, map("productId", product.getId(), "quantity", quantity)), 201);
    }

    @Test
    void emptyCartIsReturnedForAUserWhoNeverAddedAnything() throws Exception {
        User user = customer("Empty Cart");

        JsonNode cart = getJson("/cart", token(user));

        assertThat(cart.get("items")).isEmpty();
        assertThat(cart.get("itemCount").asInt()).isZero();
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("0");
        assertThat(cart.get("maxRedeemablePoints").asInt()).isZero();
        assertThat(cart.get("currency").asText()).isEqualTo("INR");
        assertThat(cart.has("estimatedDeliveryDate")).isFalse();
    }

    @Test
    void addingAProductReturns201WithLineTotalsSubtotalAndDeliveryDate() throws Exception {
        User user = customer("Adder");
        Product book = product("Alpha", "250.00", 20, 2, true);
        Product other = product("Beta", "99.50", 5, 6, true);

        add(token(user), book, 2);
        JsonNode cart = add(token(user), other, 3);

        assertThat(cart.get("items")).hasSize(2);
        JsonNode first = cart.get("items").get(0);
        assertThat(first.get("product").get("id").asLong()).isEqualTo(book.getId());
        assertThat(first.get("quantity").asInt()).isEqualTo(2);
        assertThat(money(first.get("unitPrice"))).isEqualByComparingTo("250.00");
        assertThat(money(first.get("lineTotal"))).isEqualByComparingTo("500.00");
        assertThat(cart.get("itemCount").asInt()).isEqualTo(5);
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("798.50");     // 500.00 + 298.50
        // the latest estimate over all lines: today + 6 dispatch days + 3 transit days
        assertThat(LocalDate.parse(cart.get("estimatedDeliveryDate").asText()))
                .isEqualTo(LocalDate.now(clock).plusDays(9));
        assertThat(cart.get("items").get(1).get("product").has("estimatedDeliveryDate")).isTrue();
    }

    @Test
    void addingTheSameProductAgainIncreasesTheQuantityInsteadOfAddingALine() throws Exception {
        User user = customer("Re Adder");
        Product book = product("Gamma", "100.00", 20);

        add(token(user), book, 2);
        JsonNode cart = add(token(user), book, 3);

        assertThat(cart.get("items")).hasSize(1);
        assertThat(cart.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE product_id = ?", Integer.class, book.getId()))
                .isEqualTo(1);
    }

    @Test
    void cartPersistsBetweenRequests() throws Exception {
        User user = customer("Persistent");
        Product book = product("Delta", "10.00", 20);
        add(token(user), book, 1);

        JsonNode cart = getJson("/cart", token(user));

        assertThat(cart.get("items")).hasSize(1);
    }

    @Test
    void stockIsNotReducedByAddingToTheCart() throws Exception {
        User user = customer("Stock Keeper");
        Product book = product("Epsilon", "10.00", 7);

        add(token(user), book, 5);

        assertThat(stockOf(book)).isEqualTo(7);
    }

    // ------------------------------------------------------------- limits

    @Test
    void quantityAboveStockIsRefusedWithInsufficientStockAndChangesNothing() throws Exception {
        User user = customer("Greedy");
        Product book = product("Zeta", "10.00", 3);
        add(token(user), book, 2);

        error(call(post("/cart/items"), token(user), map("productId", book.getId(), "quantity", 2)),
                409, "INSUFFICIENT_STOCK");

        assertThat(getJson("/cart", token(user)).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void quantityAboveTheConfiguredMaximumPerLineIsRefusedAsValidationError() throws Exception {
        User user = customer("Bulk Buyer");
        int max = properties.cart().maxQuantityPerItem();
        Product book = product("Eta", "10.00", max + 50);

        JsonNode b = error(call(post("/cart/items"), token(user), map("productId", book.getId(), "quantity", max + 1)),
                400, "VALIDATION_FAILED");
        assertThat(b.get("fieldErrors").get(0).get("field").asText()).isEqualTo("quantity");

        add(token(user), book, max);                                          // exactly the maximum is fine
        error(call(post("/cart/items"), token(user), map("productId", book.getId(), "quantity", 1)),
                400, "VALIDATION_FAILED");                                    // the second add would exceed it
        error(call(put("/cart/items/" + book.getId()), token(user), map("quantity", max + 1)),
                400, "VALIDATION_FAILED");
    }

    @Test
    void inactiveProductsCannotBeAdded() throws Exception {
        User user = customer("Inactive Fan");
        Product hidden = product("Theta", "10.00", 10, 1, false);

        error(call(post("/cart/items"), token(user), map("productId", hidden.getId(), "quantity", 1)),
                409, "PRODUCT_INACTIVE");
    }

    @Test
    void unknownProductIs404() throws Exception {
        User user = customer("Lost");

        error(call(post("/cart/items"), token(user), map("productId", 999999999L, "quantity", 1)), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productId\":1}", "{\"quantity\":1}", "{\"productId\":0,\"quantity\":1}",
            "{\"productId\":1,\"quantity\":0}", "{\"productId\":1,\"quantity\":-3}", "{\"productId\":1,\"quantity\":1.5}",
            "{\"productId\":\"x\",\"quantity\":1}", "not json"})
    void invalidAddRequestsAreRejectedWith400(String rawBody) throws Exception {
        User user = customer("Invalid Adder");

        error(callRaw(post("/cart/items"), token(user), rawBody), 400, "VALIDATION_FAILED");

        assertThat(getJson("/cart", token(user)).get("items")).isEmpty();
    }

    // ------------------------------------------------------------- update / remove / clear

    @Test
    void updateSetsTheQuantityAndRecalculatesTotals() throws Exception {
        User user = customer("Updater");
        Product book = product("Iota", "40.00", 9);
        add(token(user), book, 1);

        JsonNode cart = ok(call(put("/cart/items/" + book.getId()), token(user), map("quantity", 4)), 200);

        assertThat(cart.get("items").get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("160.00");
    }

    @Test
    void updateAboveStockIs409AndUpdateOfAMissingLineIs404() throws Exception {
        User user = customer("Update Limits");
        Product book = product("Kappa", "40.00", 3);
        Product notAdded = product("Lambda", "40.00", 3);
        add(token(user), book, 1);

        error(call(put("/cart/items/" + book.getId()), token(user), map("quantity", 4)), 409, "INSUFFICIENT_STOCK");
        error(call(put("/cart/items/" + notAdded.getId()), token(user), map("quantity", 1)), 404, "NOT_FOUND");
        error(call(put("/cart/items/" + book.getId()), token(user), map("quantity", 0)), 400, "VALIDATION_FAILED");
    }

    @Test
    void removeDeletesOneLineAndReturnsTheUpdatedCart() throws Exception {
        User user = customer("Remover");
        Product a = product("Mu", "10.00", 9);
        Product b = product("Nu", "20.00", 9);
        add(token(user), a, 1);
        add(token(user), b, 2);

        JsonNode cart = ok(call(delete("/cart/items/" + a.getId()), token(user)), 200);

        assertThat(cart.get("items")).hasSize(1);
        assertThat(cart.get("items").get(0).get("product").get("id").asLong()).isEqualTo(b.getId());
        assertThat(money(cart.get("subtotal"))).isEqualByComparingTo("40.00");
        error(call(delete("/cart/items/" + a.getId()), token(user)), 404, "NOT_FOUND");
    }

    @Test
    void clearEmptiesTheCartWith204AndIsRepeatable() throws Exception {
        User user = customer("Clearer");
        add(token(user), product("Xi", "10.00", 9), 1);

        assertThat(call(delete("/cart"), token(user)).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(delete("/cart"), token(user)).getResponse().getStatus()).isEqualTo(204);
        assertThat(getJson("/cart", token(user)).get("items")).isEmpty();
    }

    // ------------------------------------------------------------- isolation

    @Test
    void eachUserHasAnIndependentCart() throws Exception {
        User alice = customer("Alice");
        User bob = customer("Bob");
        Product book = product("Omicron", "10.00", 20);
        add(token(alice), book, 3);

        assertThat(getJson("/cart", token(bob)).get("items")).isEmpty();
        add(token(bob), book, 1);
        assertThat(getJson("/cart", token(alice)).get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        call(delete("/cart"), token(bob));
        assertThat(getJson("/cart", token(alice)).get("items")).hasSize(1);
    }

    @Test
    void aUserIdInTheRequestBodyIsIgnored() throws Exception {
        User alice = customer("Alice Two");
        User bob = customer("Bob Two");
        Product book = product("Pi", "10.00", 20);

        call(post("/cart/items"), token(alice), map("productId", book.getId(), "quantity", 1, "userId", bob.getId()));

        assertThat(getJson("/cart", token(alice)).get("items")).hasSize(1);
        assertThat(getJson("/cart", token(bob)).get("items")).isEmpty();
    }

    // ------------------------------------------------------------- maxRedeemablePoints

    @Test
    void maxRedeemablePointsIsTheSmallerOfBalanceAndFiftyPercentOfTheSubtotal() throws Exception {
        User rich = user("Rich", com.capstone.bookstore.user.Role.CUSTOMER, 1000);
        User poor = user("Poor", com.capstone.bookstore.user.Role.CUSTOMER, 30);
        Product book = product("Rho", "199.00", 20);                         // subtotal 199 -> 50% = 99.5 -> 99 points
        add(token(rich), book, 1);
        add(token(poor), book, 1);

        assertThat(getJson("/cart", token(rich)).get("maxRedeemablePoints").asInt()).isEqualTo(99);
        assertThat(getJson("/cart", token(poor)).get("maxRedeemablePoints").asInt()).isEqualTo(30);
    }

    @Test
    void cartResponseNeverExposesInternalFields() throws Exception {
        User user = customer("Safe");
        add(token(user), product("Sigma", "10.00", 9), 1);

        String text = call(get("/cart"), token(user)).getResponse().getContentAsString();

        assertThat(text).doesNotContain("passwordHash").doesNotContain("password").doesNotContain("\"user\"");
        assertThat(new BigDecimal(getJson("/cart", token(user)).get("subtotal").asText())).isPositive();
    }
}
