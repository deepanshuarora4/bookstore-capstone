package com.capstone.bookstore;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.capstone.bookstore.catalog.Brand;
import com.capstone.bookstore.catalog.BrandRepository;
import com.capstone.bookstore.catalog.Category;
import com.capstone.bookstore.catalog.CategoryRepository;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.catalog.ProductRepository;
import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.giftpoints.GiftPointAccount;
import com.capstone.bookstore.giftpoints.GiftPointAccountRepository;
import com.capstone.bookstore.security.JwtService;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.capstone.bookstore.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the customer-journey tests (cart, addresses, orders, payments, gift points): full stack on H2
 * with the real Flyway schema, the real filter chain and real JWTs. Everything the tests create is named with the
 * prefix "CHK" / the e-mail domain "@chk.example" and is deleted after every test, in foreign-key order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiTest {

    protected static final String EMAIL_DOMAIN = "@chk.example";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtService jwtService;
    @Autowired protected BookstoreProperties properties;
    @Autowired protected UserRepository users;
    @Autowired protected GiftPointAccountRepository accounts;
    @Autowired protected CategoryRepository categories;
    @Autowired protected BrandRepository brands;
    @Autowired protected ProductRepository products;

    protected Category category;
    protected Brand brand;

    @BeforeEach
    void baseSetUp() {
        cleanUpFixtures();
        category = categories.save(new Category("CHK Fiction", "fixture"));
        brand = brands.save(new Brand("CHK Publisher", "fixture"));
    }

    @AfterEach
    void baseTearDown() {
        cleanUpFixtures();
    }

    private void cleanUpFixtures() {
        String mine = "(SELECT id FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "')";
        String myOrders = "(SELECT id FROM orders WHERE user_id IN " + mine + ")";
        String myAccounts = "(SELECT id FROM gift_point_accounts WHERE user_id IN " + mine + ")";
        jdbc.update("DELETE FROM payments WHERE order_id IN " + myOrders);
        jdbc.update("DELETE FROM gift_point_transactions WHERE account_id IN " + myAccounts);
        jdbc.update("DELETE FROM order_items WHERE order_id IN " + myOrders);
        jdbc.update("DELETE FROM orders WHERE user_id IN " + mine);
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id IN " + mine + ")");
        jdbc.update("DELETE FROM carts WHERE user_id IN " + mine);
        jdbc.update("DELETE FROM addresses WHERE user_id IN " + mine);
        jdbc.update("DELETE FROM gift_point_accounts WHERE user_id IN " + mine);
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "'");
        jdbc.update("DELETE FROM order_items WHERE product_id IN (SELECT id FROM products WHERE title LIKE 'CHK %')");
        jdbc.update("DELETE FROM cart_items WHERE product_id IN (SELECT id FROM products WHERE title LIKE 'CHK %')");
        jdbc.update("DELETE FROM products WHERE title LIKE 'CHK %'");
        jdbc.update("DELETE FROM categories WHERE name LIKE 'CHK %'");
        jdbc.update("DELETE FROM brands WHERE name LIKE 'CHK %'");
    }

    // ----------------------------------------------------------------- fixtures

    /** A saved user with a gift point account holding {@code points}. */
    protected User user(String name, Role role, int points) {
        User user = users.save(new User(name, name.toLowerCase().replace(' ', '.') + SEQUENCE.incrementAndGet()
                + EMAIL_DOMAIN, "$2a$10$irrelevant", null, role));
        GiftPointAccount account = new GiftPointAccount(user);
        if (points > 0) {
            account.credit(points);
        }
        accounts.save(account);
        return user;
    }

    protected User customer(String name) {
        return user(name, Role.CUSTOMER, 0);
    }

    protected String token(User user) {
        return jwtService.generateToken(user);
    }

    /** An active product with the given price (INR), stock and dispatch days. */
    protected Product product(String title, String price, int stock) {
        return product(title, price, stock, 2, true);
    }

    protected Product product(String title, String price, int stock, int dispatchDays, boolean active) {
        int n = SEQUENCE.incrementAndGet();
        return products.save(new Product("CHK " + title, "CHK Author", "97800" + String.format("%08d", n), "d",
                new BigDecimal(price), null, stock, dispatchDays, active, category, brand));
    }

    protected int stockOf(Product product) {
        return jdbc.queryForObject("SELECT stock_qty FROM products WHERE id = ?", Integer.class, product.getId());
    }

    protected int balanceOf(User user) {
        return jdbc.queryForObject("SELECT balance FROM gift_point_accounts WHERE user_id = ?", Integer.class, user.getId());
    }

    // ----------------------------------------------------------------- requests

    protected MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    protected MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
    }

    protected MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    protected MvcResult callRaw(MockHttpServletRequestBuilder request, String token, String rawBody) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(rawBody)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Asserts the status and returns the JSON body. */
    protected JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        String text = result.getResponse().getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    /** Asserts the documented ErrorResponse shape, status and code. Returns the body. */
    protected JsonNode error(MvcResult result, int status, String code) throws Exception {
        String text = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode b = json.readTree(text);
        assertThat(b.fieldNames()).toIterable().contains("timestamp", "status", "error", "code", "message", "path");
        assertThat(b.get("code").asText()).as(text).isEqualTo(code);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        return b;
    }

    protected JsonNode getJson(String path, String token) throws Exception {
        return ok(call(get(path), token), 200);
    }

    // ------------------------------------------------------------ journey steps

    protected static final String VALID_CARD = "4111111111111111";
    /** Luhn-valid and ending in the configured decline suffix 0002. */
    protected static final String DECLINED_CARD = "4000000000000002";

    protected long addressFor(User user) throws Exception {
        return ok(call(post("/addresses"), token(user), map("label", "Home", "line1", "12 MG Road", "line2", "Indiranagar",
                "city", "Bengaluru", "state", "Karnataka", "postalCode", "560038", "country", "India",
                "isDefault", true)), 201).get("id").asLong();
    }

    protected JsonNode addToCart(User user, Product product, int quantity) throws Exception {
        return ok(call(post("/cart/items"), token(user), map("productId", product.getId(), "quantity", quantity)), 201);
    }

    protected MvcResult placeOrderRaw(User user, long addressId, int points) throws Exception {
        return call(post("/orders"), token(user), map("addressId", addressId, "pointsToRedeem", points));
    }

    /** Places an order from the current basket; expects 201. */
    protected JsonNode placeOrder(User user, long addressId, int points) throws Exception {
        return ok(placeOrderRaw(user, addressId, points), 201);
    }

    protected Map<String, Object> cardPayment(String cardNumber) {
        return map("method", "CREDIT_CARD", "cardHolderName", "Deepanshu Arora", "cardNumber", cardNumber,
                "expiryMonth", 12, "expiryYear", java.time.Year.now().getValue() + 3, "cvv", "123");
    }

    protected MvcResult payRaw(User user, long orderId, Map<String, Object> payment) throws Exception {
        return call(post("/orders/" + orderId + "/payments"), token(user), payment);
    }

    /** Pays with the given card; expects 201. */
    protected JsonNode pay(User user, long orderId, String cardNumber) throws Exception {
        return ok(payRaw(user, orderId, cardPayment(cardNumber)), 201);
    }

    protected JsonNode order(User user, long orderId) throws Exception {
        return getJson("/orders/" + orderId, token(user));
    }

    protected int ledgerCount(long orderId, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gift_point_transactions WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
    }

    /** Moves the placement time into the past, to test the cancellation window without waiting. */
    protected void placedHoursAgo(long orderId, long hours) {
        jdbc.update("UPDATE orders SET placed_at = ? WHERE id = ?",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(hours), orderId);
    }

    /** Runs the same action on several threads at once and returns the HTTP statuses. */
    protected java.util.List<Integer> concurrently(int threads, java.util.concurrent.Callable<MvcResult> action)
            throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var ready = new java.util.concurrent.CountDownLatch(threads);
        var go = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.call().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var f : futures) {
                statuses.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }


    protected static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    protected static BigDecimal money(JsonNode node) {
        return node.decimalValue().setScale(2);
    }
}
