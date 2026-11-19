package com.capstone.bookstore.order;

import static com.capstone.bookstore.TestSupport.get;
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
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/** Order placement (IBM steps 9, 10a), order history and Buy Again (2a), order detail (12). */
class OrderApiIntegrationTest extends AbstractApiTest {

    @Autowired Clock clock;

    // ------------------------------------------------------------- placement

    @Test
    void placingAnOrderSnapshotsTheBasketReducesStockAndClearsTheCart() throws Exception {
        User user = customer("Buyer");
        Product a = product("Alpha", "250.00", 10, 2, true);
        Product b = product("Beta", "99.50", 5, 6, true);
        long address = addressFor(user);
        addToCart(user, a, 2);
        addToCart(user, b, 1);

        JsonNode order = placeOrder(user, address, 0);

        assertThat(order.get("id").asLong()).isPositive();
        assertThat(order.get("orderNumber").asText()).matches("BK-\\d{8}-\\d{6}")
                .startsWith("BK-" + LocalDate.now(clock).toString().replace("-", ""));
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("items")).hasSize(2);
        JsonNode first = order.get("items").get(0);
        assertThat(first.get("productId").asLong()).isEqualTo(a.getId());
        assertThat(first.get("title").asText()).isEqualTo("CHK Alpha");
        assertThat(money(first.get("unitPrice"))).isEqualByComparingTo("250.00");
        assertThat(first.get("quantity").asInt()).isEqualTo(2);
        assertThat(money(first.get("lineTotal"))).isEqualByComparingTo("500.00");
        assertThat(money(order.get("subtotal"))).isEqualByComparingTo("599.50");
        assertThat(order.get("pointsRedeemed").asInt()).isZero();
        assertThat(money(order.get("pointsDiscount"))).isEqualByComparingTo("0");
        assertThat(money(order.get("totalAmount"))).isEqualByComparingTo("599.50");
        assertThat(order.get("pointsEarned").asInt()).isZero();
        assertThat(order.get("currency").asText()).isEqualTo("INR");
        assertThat(order.get("cancellable").asBoolean()).isTrue();
        assertThat(order.has("payment")).isFalse();
        assertThat(order.get("cancelledAt")).isNull();
        // the latest estimate over all lines: today + 6 dispatch days + 3 transit days
        assertThat(LocalDate.parse(order.get("estimatedDeliveryDate").asText())).isEqualTo(LocalDate.now(clock).plusDays(9));
        assertThat(LocalDate.parse(first.get("estimatedDeliveryDate").asText())).isEqualTo(LocalDate.now(clock).plusDays(5));
        assertThat(order.get("shippingAddress").get("city").asText()).isEqualTo("Bengaluru");

        assertThat(stockOf(a)).isEqualTo(8);
        assertThat(stockOf(b)).isEqualTo(4);
        assertThat(getJson("/cart", token(user)).get("items")).isEmpty();
    }

    @Test
    void cancellableUntilIsPlacedAtPlusTheConfiguredWindow() throws Exception {
        User user = customer("Window");
        addToCart(user, product("Gamma", "100.00", 5), 1);

        JsonNode order = placeOrder(user, addressFor(user), 0);

        java.time.Instant placed = java.time.Instant.parse(order.get("placedAt").asText());
        java.time.Instant until = java.time.Instant.parse(order.get("cancellableUntil").asText());
        assertThat(java.time.Duration.between(placed, until).toHours()).isEqualTo(properties.order().cancellationWindowHours());
    }

    @Test
    void orderKeepsThePriceTitleAndAddressItHadWhenPlaced() throws Exception {
        User user = customer("Snapshot");
        Product book = product("Delta", "300.00", 10);
        long address = addressFor(user);
        addToCart(user, book, 1);
        long orderId = placeOrder(user, address, 0).get("id").asLong();

        jdbc.update("UPDATE products SET price = 999.00, title = 'CHK Renamed' WHERE id = ?", book.getId());
        call(com.capstone.bookstore.TestSupport.put("/addresses/" + address), token(user), map("line1", "Somewhere else", "city", "Pune", "state", "MH",
                        "postalCode", "411001"));
        call(com.capstone.bookstore.TestSupport.delete("/addresses/" + address), token(user));

        JsonNode order = order(user, orderId);
        assertThat(order.get("items").get(0).get("title").asText()).isEqualTo("CHK Delta");
        assertThat(money(order.get("items").get(0).get("unitPrice"))).isEqualByComparingTo("300.00");
        assertThat(money(order.get("totalAmount"))).isEqualByComparingTo("300.00");
        assertThat(order.get("shippingAddress").get("line1").asText()).isEqualTo("12 MG Road");
        assertThat(order.get("shippingAddress").get("city").asText()).isEqualTo("Bengaluru");
    }

    @Test
    void emptyBasketIs400EmptyCart() throws Exception {
        User user = customer("Empty");

        error(placeOrderRaw(user, addressFor(user), 0), 400, "EMPTY_CART");
    }

    @Test
    void addressMustBelongToTheUserAndNotBeDeleted() throws Exception {
        User alice = customer("Alice Order");
        User mallory = customer("Mallory Order");
        long aliceAddress = addressFor(alice);
        Product book = product("Epsilon", "100.00", 5);
        addToCart(mallory, book, 1);

        error(placeOrderRaw(mallory, aliceAddress, 0), 404, "NOT_FOUND");
        error(placeOrderRaw(mallory, 987654321L, 0), 404, "NOT_FOUND");
        long own = addressFor(mallory);
        call(com.capstone.bookstore.TestSupport.delete("/addresses/" + own), token(mallory));
        error(placeOrderRaw(mallory, own, 0), 404, "NOT_FOUND");

        assertThat(stockOf(book)).isEqualTo(5);
        assertThat(getJson("/cart", token(mallory)).get("items")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"addressId\":0}", "{\"addressId\":-1}", "{\"addressId\":\"x\"}",
            "{\"addressId\":1,\"pointsToRedeem\":-1}", "{\"addressId\":1,\"pointsToRedeem\":1.5}",
            "{\"addressId\":1,\"pointsToRedeem\":\"ten\"}", "not json"})
    void invalidRequestsAreRejectedWith400AndChangeNothing(String rawBody) throws Exception {
        User user = customer("Invalid Order");
        Product book = product("Zeta", "100.00", 5);
        addToCart(user, book, 1);

        error(callRaw(com.capstone.bookstore.TestSupport.post("/orders"), token(user), rawBody), 400, "VALIDATION_FAILED");

        assertThat(stockOf(book)).isEqualTo(5);
        assertThat(getJson("/cart", token(user)).get("items")).hasSize(1);
    }

    @Test
    void stockThatDisappearedAfterAddingToTheCartIs409AndNothingIsChanged() throws Exception {
        User user = user("Unlucky", Role.CUSTOMER, 100);
        Product first = product("Eta", "100.00", 5);
        Product second = product("Theta", "100.00", 5);
        long address = addressFor(user);
        addToCart(user, first, 2);
        addToCart(user, second, 3);
        jdbc.update("UPDATE products SET stock_qty = 1 WHERE id = ?", second.getId());     // someone else bought it

        error(placeOrderRaw(user, address, 10), 409, "INSUFFICIENT_STOCK");

        assertThat(stockOf(first)).isEqualTo(5);                       // the first line's decrement was rolled back
        assertThat(stockOf(second)).isEqualTo(1);
        assertThat(balanceOf(user)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user.getId())).isZero();
        assertThat(getJson("/cart", token(user)).get("items")).hasSize(2);
    }

    @Test
    void aProductThatBecameInactiveCannotBeOrdered() throws Exception {
        User user = customer("Late");
        Product book = product("Iota", "100.00", 5);
        addToCart(user, book, 1);
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", book.getId());

        error(placeOrderRaw(user, addressFor(user), 0), 409, "PRODUCT_INACTIVE");

        assertThat(stockOf(book)).isEqualTo(5);
    }

    @Test
    void twoCustomersCompetingForTheLastCopyCannotOversell() throws Exception {
        Product lastCopy = product("Kappa", "100.00", 1);
        List<User> buyers = List.of(customer("Racer A"), customer("Racer B"), customer("Racer C"), customer("Racer D"));
        for (User buyer : buyers) {
            addToCart(buyer, lastCopy, 1);
        }
        List<Long> addresses = new java.util.ArrayList<>();
        for (User buyer : buyers) {
            addresses.add(addressFor(buyer));
        }
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

        List<Integer> statuses = concurrently(buyers.size(), () -> {
            int i = next.getAndIncrement();
            return placeOrderRaw(buyers.get(i), addresses.get(i), 0);
        });

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
        assertThat(stockOf(lastCopy)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE product_id = ?", Integer.class, lastCopy.getId()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------- gift points at placement

    @Test
    void redeemingPointsLowersTheTotalDebitsTheBalanceAndWritesOneLedgerEntry() throws Exception {
        User user = user("Redeemer", Role.CUSTOMER, 100);
        addToCart(user, product("Lambda", "400.00", 5), 1);

        JsonNode order = placeOrder(user, addressFor(user), 80);

        assertThat(order.get("pointsRedeemed").asInt()).isEqualTo(80);
        assertThat(money(order.get("pointsDiscount"))).isEqualByComparingTo("80.00");
        assertThat(money(order.get("subtotal"))).isEqualByComparingTo("400.00");
        assertThat(money(order.get("totalAmount"))).isEqualByComparingTo("320.00");
        assertThat(balanceOf(user)).isEqualTo(20);
        long id = order.get("id").asLong();
        assertThat(ledgerCount(id, "REDEEM")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT points FROM gift_point_transactions WHERE order_id = ? AND type = 'REDEEM'",
                Integer.class, id)).isEqualTo(-80);
    }

    @Test
    void redemptionAtExactlyTheLimitIsAcceptedAndOnePointMoreIsRefused() throws Exception {
        User user = user("Limit", Role.CUSTOMER, 1000);
        Product book = product("Mu", "199.00", 5);                      // 50% of 199 = 99.5 -> at most 99 points
        long address = addressFor(user);
        addToCart(user, book, 1);

        error(placeOrderRaw(user, address, 100), 400, "POINTS_LIMIT_EXCEEDED");
        assertThat(balanceOf(user)).isEqualTo(1000);
        assertThat(stockOf(book)).isEqualTo(5);

        JsonNode order = placeOrder(user, address, 99);
        assertThat(money(order.get("totalAmount"))).isEqualByComparingTo("100.00");
        assertThat(money(order.get("totalAmount"))).isPositive();
        assertThat(balanceOf(user)).isEqualTo(901);
    }

    @Test
    void redeemingMoreThanTheBalanceIs400InsufficientGiftPointsAndNothingChanges() throws Exception {
        User user = user("Broke", Role.CUSTOMER, 30);
        Product book = product("Nu", "1000.00", 5);
        long address = addressFor(user);
        addToCart(user, book, 1);

        error(placeOrderRaw(user, address, 31), 400, "INSUFFICIENT_GIFT_POINTS");

        assertThat(balanceOf(user)).isEqualTo(30);
        assertThat(stockOf(book)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_point_transactions WHERE account_id IN "
                + "(SELECT id FROM gift_point_accounts WHERE user_id = ?)", Integer.class, user.getId())).isZero();
        assertThat(getJson("/cart", token(user)).get("items")).hasSize(1);
    }

    @Test
    void thePointsLimitFollowsTheSubtotalNotTheBalance() throws Exception {
        User user = user("Small Basket", Role.CUSTOMER, 5000);
        addToCart(user, product("Xi", "100.00", 5), 1);

        error(placeOrderRaw(user, addressFor(user), 51), 400, "POINTS_LIMIT_EXCEEDED");
        assertThat(getJson("/cart", token(user)).get("maxRedeemablePoints").asInt()).isEqualTo(50);
    }

    @Test
    void redemptionNeverMakesTheTotalZeroEvenForTheCheapestBook() throws Exception {
        User user = user("Penny", Role.CUSTOMER, 1000);
        addToCart(user, product("Omicron", "0.01", 5), 1);

        error(placeOrderRaw(user, addressFor(user), 1), 400, "POINTS_LIMIT_EXCEEDED");
        assertThat(getJson("/cart", token(user)).get("maxRedeemablePoints").asInt()).isZero();
        JsonNode order = placeOrder(user, addressFor(user), 0);
        assertThat(money(order.get("totalAmount"))).isEqualByComparingTo("0.01");
    }

    // ------------------------------------------------------------- history

    @Test
    void historyListsOwnOrdersNewestFirstWithSummaries() throws Exception {
        User user = customer("Historian");
        long address = addressFor(user);
        Product a = product("Pi", "100.00", 20);
        Product b = product("Rho", "50.00", 20);
        addToCart(user, a, 2);
        addToCart(user, b, 1);
        long first = placeOrder(user, address, 0).get("id").asLong();
        addToCart(user, b, 3);
        long second = placeOrder(user, address, 0).get("id").asLong();

        JsonNode page = getJson("/orders", token(user));

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(page.get("content").get(1).get("id").asLong()).isEqualTo(first);
        JsonNode summary = page.get("content").get(1);
        assertThat(summary.get("itemCount").asInt()).isEqualTo(3);                  // 2 + 1 copies
        assertThat(summary.get("firstItemTitle").asText()).isEqualTo("CHK Pi");
        assertThat(money(summary.get("totalAmount"))).isEqualByComparingTo("250.00");
        assertThat(summary.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(summary.get("cancellable").asBoolean()).isTrue();
        assertThat(summary.has("items")).isFalse();
    }

    @Test
    void historyIsPagedAndCanBeFilteredByStatus() throws Exception {
        User user = customer("Pager");
        long address = addressFor(user);
        Product book = product("Sigma", "10.00", 50);
        for (int i = 0; i < 3; i++) {
            addToCart(user, book, 1);
            placeOrder(user, address, 0);
        }
        long cancelled = getJson("/orders", token(user)).get("content").get(0).get("id").asLong();
        ok(call(com.capstone.bookstore.TestSupport.post("/orders/" + cancelled + "/cancel"), token(user)), 200);

        JsonNode firstPage = getJson("/orders?size=2", token(user));
        JsonNode secondPage = getJson("/orders?size=2&page=1", token(user));
        JsonNode onlyCancelled = getJson("/orders?status=CANCELLED", token(user));
        JsonNode onlyPending = getJson("/orders?status=PENDING_PAYMENT", token(user));
        JsonNode noneConfirmed = getJson("/orders?status=CONFIRMED", token(user));

        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(secondPage.get("content")).hasSize(1);
        assertThat(onlyCancelled.get("content")).hasSize(1);
        assertThat(onlyCancelled.get("content").get(0).get("cancellable").asBoolean()).isFalse();
        assertThat(onlyPending.get("content")).hasSize(2);
        assertThat(noneConfirmed.get("content")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders?page=-1", "/orders?size=0", "/orders?size=101", "/orders?status=BOGUS",
            "/orders?status=pending_payment", "/orders?page=abc", "/orders/buy-again?limit=0", "/orders/buy-again?limit=51",
            "/orders/buy-again?limit=x", "/orders/0", "/orders/abc"})
    void invalidHistoryParametersAreRejectedWith400(String url) throws Exception {
        User user = customer("Bad Params");

        error(call(get(url), token(user)), 400, "VALIDATION_FAILED");
    }

    @Test
    void historyOfANewUserIsEmpty() throws Exception {
        JsonNode page = getJson("/orders", token(customer("Newcomer")));

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    void aUserNeverSeesAnotherUsersOrders() throws Exception {
        User alice = customer("Alice History");
        User bob = customer("Bob History");
        addToCart(alice, product("Tau", "10.00", 5), 1);
        long aliceOrder = placeOrder(alice, addressFor(alice), 0).get("id").asLong();

        assertThat(getJson("/orders", token(bob)).get("content")).isEmpty();
        error(call(get("/orders/" + aliceOrder), token(bob)), 404, "NOT_FOUND");
        error(call(get("/orders/" + aliceOrder + "/payment"), token(bob)), 404, "NOT_FOUND");
        error(call(com.capstone.bookstore.TestSupport.post("/orders/" + aliceOrder + "/cancel"), token(bob)), 404, "NOT_FOUND");
        assertThat(order(alice, aliceOrder).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void thereIsNoAdminBypassForCustomerOrders() throws Exception {
        User customer = customer("Customer Owner");
        User admin = user("Admin Peeker", Role.ADMIN, 0);
        addToCart(customer, product("Upsilon", "10.00", 5), 1);
        long orderId = placeOrder(customer, addressFor(customer), 0).get("id").asLong();

        error(call(get("/orders/" + orderId), token(admin)), 404, "NOT_FOUND");
        assertThat(getJson("/orders", token(admin)).get("content")).isEmpty();
    }

    @Test
    void unknownOrderIs404() throws Exception {
        error(call(get("/orders/987654321"), token(customer("Nobody"))), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------- buy again

    @Test
    void buyAgainListsDistinctPurchasedProductsMostRecentFirstWithCounts() throws Exception {
        User user = customer("Repeat Buyer");
        long address = addressFor(user);
        Product a = product("Phi", "10.00", 50);
        Product b = product("Chi", "20.00", 50);
        addToCart(user, a, 1);
        addToCart(user, b, 1);
        placeOrder(user, address, 0);
        addToCart(user, a, 2);
        long newest = placeOrder(user, address, 0).get("id").asLong();
        jdbc.update("UPDATE orders SET placed_at = ? WHERE id <> ? AND user_id = ?",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(3), newest, user.getId());

        JsonNode items = getJson("/orders/buy-again", token(user));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("product").get("id").asLong()).isEqualTo(a.getId());     // ordered most recently
        assertThat(items.get(0).get("timesOrdered").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("product").get("id").asLong()).isEqualTo(b.getId());
        assertThat(items.get(1).get("timesOrdered").asInt()).isEqualTo(1);
        assertThat(items.get(0).has("lastOrderedAt")).isTrue();
        assertThat(items.get(0).get("product").has("estimatedDeliveryDate")).isTrue();
        assertThat(items.get(0).get("product").get("category").get("name").asText()).isEqualTo("CHK Fiction");
    }

    @Test
    void buyAgainSkipsCancelledOrdersAndProductsThatAreInactiveOrOutOfStock() throws Exception {
        User user = customer("Picky");
        long address = addressFor(user);
        Product available = product("Psi", "10.00", 50);
        Product soldOut = product("Omega", "10.00", 50);
        Product withdrawn = product("Alef", "10.00", 50);
        Product cancelledOnly = product("Bet", "10.00", 50);
        addToCart(user, available, 1);
        addToCart(user, soldOut, 1);
        addToCart(user, withdrawn, 1);
        placeOrder(user, address, 0);
        addToCart(user, cancelledOnly, 1);
        long toCancel = placeOrder(user, address, 0).get("id").asLong();
        ok(call(com.capstone.bookstore.TestSupport.post("/orders/" + toCancel + "/cancel"), token(user)), 200);
        jdbc.update("UPDATE products SET stock_qty = 0 WHERE id = ?", soldOut.getId());
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", withdrawn.getId());

        JsonNode items = getJson("/orders/buy-again", token(user));

        List<Long> ids = StreamSupport.stream(items.spliterator(), false).map(n -> n.get("product").get("id").asLong()).toList();
        assertThat(ids).containsExactly(available.getId());
    }

    @Test
    void buyAgainRespectsTheLimitAndIsEmptyForNewUsers() throws Exception {
        User user = customer("Limited");
        long address = addressFor(user);
        for (int i = 0; i < 3; i++) {
            addToCart(user, product("Lim" + i, "10.00", 50), 1);
        }
        placeOrder(user, address, 0);

        assertThat(getJson("/orders/buy-again?limit=2", token(user))).hasSize(2);
        assertThat(getJson("/orders/buy-again", token(customer("Fresh")))).isEmpty();
    }

    @Test
    void buyAgainShowsOnlyTheCallersOwnPurchases() throws Exception {
        User alice = customer("Alice Again");
        User bob = customer("Bob Again");
        addToCart(alice, product("Mine", "10.00", 50), 1);
        placeOrder(alice, addressFor(alice), 0);

        assertThat(getJson("/orders/buy-again", token(bob))).isEmpty();
        assertThat(getJson("/orders/buy-again", token(alice))).hasSize(1);
    }
}
