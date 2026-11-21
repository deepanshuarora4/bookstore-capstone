package com.capstone.bookstore.recommendation;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.catalog.Brand;
import com.capstone.bookstore.catalog.Category;
import com.capstone.bookstore.catalog.Product;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * GET /recommendations (IBM step 2b): active, in-stock products in the categories and brands of the user's
 * purchased books that the user has not bought yet; newest products when there is no order history.
 * Fixture: {@code category} "CHK Fiction" and {@code brand} "CHK Publisher" come from {@link AbstractApiTest}.
 */
class RecommendationApiIntegrationTest extends AbstractApiTest {

    private static final AtomicInteger ISBN = new AtomicInteger(1);

    private Category crime;
    private Brand otherPublisher;

    // ------------------------------------------------------------- fixtures

    private Category newCategory(String name) {
        return categories.save(new Category("CHK " + name, "fixture"));
    }

    private Brand newBrand(String name) {
        return brands.save(new Brand("CHK " + name, "fixture"));
    }

    /** A product in an arbitrary category and brand (the base fixture only creates one of each). */
    private Product book(String title, Category c, Brand b, int stock, boolean active) {
        return products.save(new Product("CHK " + title, "CHK Author", "97801" + String.format("%08d", ISBN.getAndIncrement()),
                "d", new BigDecimal("250.00"), null, stock, 1, active, c, b));
    }

    private Product book(String title, Category c, Brand b) {
        return book(title, c, b, 10, true);
    }

    /** The user places an order for one copy of the product (PENDING_PAYMENT, which counts as purchased). */
    private long buy(User user, Product product) throws Exception {
        addToCart(user, product, 1);
        return placeOrder(user, addressFor(user), 0).get("id").asLong();
    }

    private JsonNode recommendations(User user, String query) throws Exception {
        return getJson("/recommendations" + query, token(user));
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("product").get("id").asLong()).toList();
    }

    private static List<String> reasons(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("reason").asText()).toList();
    }

    private void extraTaxonomy() {
        crime = newCategory("Crime");
        otherPublisher = newBrand("Other Publisher");
    }

    // ------------------------------------------------------------- security

    @Test
    void anonymousAndInvalidTokensAreRejectedWith401() throws Exception {
        error(call(get("/recommendations")), 401, "AUTHENTICATION_FAILED");
        error(call(get("/recommendations"), "not.a.jwt"), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void aCustomerGetsAJsonArrayOfProductAndReason() throws Exception {
        book("Shape", category, brand);

        JsonNode result = recommendations(customer("Shape Reader"), "");

        assertThat(result.isArray()).isTrue();
        JsonNode first = result.get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("product", "reason");
        assertThat(first.get("product").fieldNames()).toIterable().contains("id", "title", "author", "isbn", "price",
                "category", "brand", "stockQty", "inStock", "estimatedDeliveryDate");
        assertThat(first.get("product").get("inStock").asBoolean()).isTrue();
        assertThat(first.get("reason").asText()).isNotBlank();
    }

    // ------------------------------------------------------------- empty history

    @Test
    void withoutOrderHistoryTheNewestAvailableProductsAreReturnedNewestFirst() throws Exception {
        Product older = book("Older", category, brand);
        Product newer = book("Newer", category, brand);
        Product newest = book("Newest", category, brand);

        JsonNode result = recommendations(customer("Fresh Customer"), "?limit=3");

        assertThat(ids(result)).containsExactly(newest.getId(), newer.getId(), older.getId());
        assertThat(reasons(result)).containsOnly("New arrival");
    }

    @Test
    void withoutHistoryInactiveAndOutOfStockProductsAreNeverRecommended() throws Exception {
        Product available = book("Available", category, brand);
        Product inactive = book("Inactive", category, brand, 10, false);
        Product soldOut = book("Sold Out", category, brand, 0, true);

        List<Long> ids = ids(recommendations(customer("Fresh Two"), "?limit=50"));

        assertThat(ids).contains(available.getId()).doesNotContain(inactive.getId(), soldOut.getId());
    }

    @Test
    void theLimitDefaultsToEightAndCanBeSet() throws Exception {
        for (int i = 0; i < 10; i++) {
            book("Bulk " + i, category, brand);
        }
        User user = customer("Limit User");

        assertThat(recommendations(user, "")).hasSize(8);
        assertThat(recommendations(user, "?limit=1")).hasSize(1);
        assertThat(recommendations(user, "?limit=10")).hasSize(10);
        assertThat(recommendations(user, "?limit=50").size()).isGreaterThanOrEqualTo(10).isLessThanOrEqualTo(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=-1", "?limit=51", "?limit=abc"})
    void anInvalidLimitIs400(String query) throws Exception {
        error(call(get("/recommendations" + query), token(customer("Bad Limit"))), 400, "VALIDATION_FAILED");
    }

    // ------------------------------------------------------------- with history

    @Test
    void booksInTheCategoryOrBrandOfPurchasedBooksAreRecommendedWithAReason() throws Exception {
        extraTaxonomy();
        Brand third = newBrand("Third Publisher");
        Category history = newCategory("History");
        Product bought = book("Bought", category, brand);
        Product sameBoth = book("Same Both", category, brand);
        Product sameCategory = book("Same Category", category, otherPublisher);
        Product sameBrand = book("Same Brand", crime, brand);
        Product unrelated = book("Unrelated", history, third);
        User user = customer("Reader");
        buy(user, bought);

        JsonNode result = recommendations(user, "?limit=50");

        // category + brand first, then single matches (newest first within the same rank)
        assertThat(ids(result)).containsExactly(sameBoth.getId(), sameBrand.getId(), sameCategory.getId());
        assertThat(ids(result)).doesNotContain(unrelated.getId(), bought.getId());
        assertThat(reasons(result)).containsExactly(
                "Because you bought books in CHK Fiction from CHK Publisher",
                "Because you bought books from CHK Publisher",
                "Because you bought books in CHK Fiction");
    }

    @Test
    void productsTheUserHasAlreadyBoughtAreNeverRecommended() throws Exception {
        Product one = book("Bought One", category, brand);
        Product two = book("Bought Two", category, brand);
        Product candidate = book("Candidate", category, brand);
        User user = customer("Buyer");
        buy(user, one);
        buy(user, two);

        assertThat(ids(recommendations(user, "?limit=50"))).containsExactly(candidate.getId());
    }

    @Test
    void inactiveAndOutOfStockProductsAreNeverRecommendedEvenWhenTheyMatch() throws Exception {
        Product bought = book("Bought", category, brand);
        Product good = book("Good Match", category, brand);
        Product inactive = book("Inactive Match", category, brand, 10, false);
        Product soldOut = book("Sold Out Match", category, brand, 0, true);
        User user = customer("Picky");
        buy(user, bought);

        assertThat(ids(recommendations(user, "?limit=50"))).containsExactly(good.getId())
                .doesNotContain(inactive.getId(), soldOut.getId());
    }

    @Test
    void aMatchThatSellsOutAfterwardsDisappearsFromTheRecommendations() throws Exception {
        Product bought = book("Bought", category, brand);
        Product last = book("Last Copy", category, brand, 1, true);
        User user = customer("Late Reader");
        buy(user, bought);
        assertThat(ids(recommendations(user, ""))).containsExactly(last.getId());

        User rival = customer("Rival Buyer");
        buy(rival, last);

        assertThat(recommendations(user, "")).isEmpty();
    }

    @Test
    void whenNothingMatchesTheAnswerIsAnEmptyArrayNotTheNewestFallback() throws Exception {
        Product bought = book("Only Book", category, brand);
        User user = customer("Exhausted");
        buy(user, bought);
        extraTaxonomy();
        book("Elsewhere", crime, otherPublisher);

        JsonNode result = recommendations(user, "?limit=50");

        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void buyingARecommendationRemovesItFromTheList() throws Exception {
        Product bought = book("First", category, brand);
        Product next = book("Next", category, brand);
        Product after = book("After", category, brand);
        User user = customer("Serial Buyer");
        buy(user, bought);
        assertThat(ids(recommendations(user, ""))).containsExactly(after.getId(), next.getId());

        buy(user, after);

        assertThat(ids(recommendations(user, ""))).containsExactly(next.getId());
    }

    @Test
    void aCancelledOrderDoesNotCountAsAPurchase() throws Exception {
        extraTaxonomy();
        Product bought = book("Bought Then Cancelled", category, brand);
        book("Fiction Candidate", category, otherPublisher);
        Product newestElsewhere = book("Newest Elsewhere", crime, otherPublisher);
        User user = customer("Canceller");
        long orderId = buy(user, bought);
        ok(call(post("/orders/" + orderId + "/cancel"), token(user)), 200);

        JsonNode result = recommendations(user, "?limit=1");

        // no purchases left: back to the newest-products fallback, and the cancelled book is available again
        assertThat(reasons(result)).containsExactly("New arrival");
        assertThat(ids(result)).containsExactly(newestElsewhere.getId());
        assertThat(ids(recommendations(user, "?limit=50"))).contains(bought.getId());
    }

    @Test
    void aConfirmedPaidOrderAlsoDrivesRecommendations() throws Exception {
        extraTaxonomy();
        Product bought = book("Paid Book", crime, otherPublisher);
        Product match = book("Crime Match", crime, newBrand("Third Publisher"));
        book("Fiction Elsewhere", category, brand);
        User user = customer("Paying Reader");
        long orderId = buy(user, bought);
        pay(user, orderId, VALID_CARD);

        assertThat(ids(recommendations(user, "?limit=50"))).containsExactly(match.getId());
    }

    // ------------------------------------------------------------- ownership / isolation

    @Test
    void recommendationsAreBasedOnlyOnTheCallersOwnOrders() throws Exception {
        extraTaxonomy();
        Product fictionBook = book("Fiction Bought", category, brand);
        Product crimeBook = book("Crime Bought", crime, otherPublisher);
        Product fictionCandidate = book("Fiction Candidate", category, brand);
        Product crimeCandidate = book("Crime Candidate", crime, otherPublisher);
        User fictionFan = customer("Fiction Fan");
        User crimeFan = customer("Crime Fan");
        buy(fictionFan, fictionBook);
        buy(crimeFan, crimeBook);

        assertThat(ids(recommendations(fictionFan, "?limit=50"))).containsExactly(fictionCandidate.getId());
        assertThat(ids(recommendations(crimeFan, "?limit=50"))).containsExactly(crimeCandidate.getId());
    }

    @Test
    void anotherUsersPurchaseNeitherExcludesNorTriggersRecommendations() throws Exception {
        Product bought = book("Someone Elses", category, brand);
        Product other = book("Other", category, brand);
        User buyer = customer("The Buyer");
        User newcomer = customer("The Newcomer");
        buy(buyer, bought);

        JsonNode result = recommendations(newcomer, "?limit=50");

        assertThat(ids(result)).contains(bought.getId(), other.getId());           // nothing excluded for the newcomer
        assertThat(reasons(result)).containsOnly("New arrival");                   // history of others is not used
    }

    @Test
    void aUserIdInTheQueryOrHeadersCannotSelectAnotherUsersHistory() throws Exception {
        extraTaxonomy();
        Product bought = book("Victim Book", crime, otherPublisher);
        Product victimCandidate = book("Victim Candidate", crime, otherPublisher);
        User victim = customer("Victim");
        User attacker = customer("Attacker");
        buy(victim, bought);

        JsonNode result = ok(call(get("/recommendations?limit=50&userId=" + victim.getId() + "&user_id=" + victim.getId())
                .header("X-User-Id", victim.getId()), token(attacker)), 200);

        assertThat(reasons(result)).containsOnly("New arrival");                   // the attacker has no history
        assertThat(ids(result)).contains(victimCandidate.getId(), bought.getId());
    }
}
