package com.capstone.bookstore.catalog;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static com.capstone.bookstore.TestSupport.put;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.security.JwtService;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests (H2, real Flyway schema, real filter chain and JWTs) for the catalogue endpoints of openapi.yaml:
 * public browsing, search, filtering, pagination, details, related products and the ADMIN operations.
 * All fixture rows use the prefix "CTX" so they never collide with other test classes and are removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiIntegrationTest {

    private static final String PREFIX = "CTX";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired BookstoreProperties properties;
    @Autowired Clock clock;
    @Autowired CategoryRepository categories;
    @Autowired BrandRepository brands;
    @Autowired ProductRepository products;
    @MockitoSpyBean ProductService productService;

    private Category fiction;
    private Category science;
    private Category emptyCategory;
    private Brand brandA;
    private Brand brandB;
    private Brand emptyBrand;
    private Product alpha;      // Fiction / A, 5 in stock, dispatch 2
    private Product bravo;      // Fiction / A, author "Zed Smith"
    private Product charlie;    // Fiction / B
    private Product delta;      // Science / A, OUT of stock
    private Product echo;       // Science / B, cheapest
    private Product foxtrot;    // Fiction / A, INACTIVE
    private Product golf;       // Fiction / A, newest

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void createFixtures() {
        cleanUp();
        fiction = categories.save(new Category(PREFIX + " Fiction", "Novels"));
        science = categories.save(new Category(PREFIX + " Science", null));
        emptyCategory = categories.save(new Category(PREFIX + " Empty", null));
        brandA = brands.save(new Brand(PREFIX + " Brand A", "Publisher A"));
        brandB = brands.save(new Brand(PREFIX + " Brand B", null));
        emptyBrand = brands.save(new Brand(PREFIX + " Brand Empty", null));

        alpha = product("CTX Alpha", "Ann Writer", "9780000001001", "100.00", 5, 2, true, fiction, brandA);
        bravo = product("CTX Bravo", "Zed Smith", "9780000001002", "200.00", 3, 0, true, fiction, brandA);
        charlie = product("CTX Charlie", "Ann Writer", "9780000001003", "300.00", 4, 0, true, fiction, brandB);
        delta = product("CTX Delta", "Ann Writer", "9780000001004", "150.00", 0, 5, true, science, brandA);
        echo = product("CTX Echo", "Ann Writer", "9780000001005", "50.00", 9, 0, true, science, brandB);
        foxtrot = product("CTX Foxtrot", "Ann Writer", "9780000001006", "75.00", 6, 0, false, fiction, brandA);
        golf = product("CTX Golf", "Ann Writer", "9780000001007", "120.00", 7, 0, true, fiction, brandA);

        adminToken = tokenFor(Role.ADMIN, 9001L);
        customerToken = tokenFor(Role.CUSTOMER, 9002L);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM products WHERE title LIKE ? OR isbn LIKE '97800000%'", PREFIX + "%");
        jdbc.update("DELETE FROM categories WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM brands WHERE name LIKE ?", PREFIX + "%");
    }

    // ------------------------------------------------------------------ helpers

    private Product product(String title, String author, String isbn, String price, int stock, int dispatchDays,
                            boolean active, Category category, Brand brand) {
        return products.save(new Product(title, author, isbn, "About " + title, new BigDecimal(price), null, stock,
                dispatchDays, active, category, brand));
    }

    private long alphaVersion() {
        return products.findById(alpha.getId()).orElseThrow().getVersion();
    }

    private String tokenFor(Role role, long id) {
        User user = new User("Test " + role, role.name().toLowerCase() + "@catalog.example", "$2a$10$irrelevant", null, role);
        ReflectionTestUtils.setField(user, "id", id);
        return jwtService.generateToken(user);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private MvcResult getJson(String path) throws Exception {
        return call(get(path));
    }

    /**
     * GET with query parameters given as name/value PAIRS. They go through MockMvc {@code .param()}, which is the
     * decoded form the servlet container hands to Spring, so nothing is URL-encoded twice. Putting "%20" into a
     * URI string instead would reach the application as the literal text "%20".
     */
    private MvcResult getWith(String path, String... nameValuePairs) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return call(request);
    }

    /** GET /products with the given filter pairs, for example {@code search("q", "ctx alpha", "size", "5")}. */
    private MvcResult search(String... nameValuePairs) throws Exception {
        return getWith("/products", nameValuePairs);
    }

    /** Titles of the products on the first page of a search (asserts the call itself succeeded). */
    private List<String> found(String... nameValuePairs) throws Exception {
        MvcResult result = search(nameValuePairs);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return titles(body(result).get("content"));
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
        return call(bearer(builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), token));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        JsonNode b = body(result);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(b.fieldNames()).toIterable().contains("timestamp", "status", "error", "code", "message", "path");
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("status").asInt()).isEqualTo(status);
    }

    private void assertValidationError(MvcResult result, String field) throws Exception {
        assertError(result, 400, "VALIDATION_FAILED");
        assertThat(StreamSupport.stream(body(result).get("fieldErrors").spliterator(), false)
                .map(e -> e.get("field").asText())).contains(field);
    }

    private static List<String> titles(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("title").asText()).toList();
    }

    private String productList(String query) {
        return "/products?" + query;
    }

    private void assertBlankQueryError(MvcResult result) throws Exception {
        assertError(result, 400, "VALIDATION_FAILED");
        JsonNode b = body(result);
        assertThat(b.get("error").asText()).isEqualTo("Bad Request");
        assertThat(b.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(b.get("path").asText()).isEqualTo("/api/v1/products");
        assertThat(b.get("fieldErrors")).hasSize(1);
        assertThat(b.get("fieldErrors").get(0).get("field").asText()).isEqualTo("q");
        assertThat(b.get("fieldErrors").get(0).get("message").asText()).isNotBlank();
    }

    private Map<String, Object> productRequest(String title, String isbn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("author", "New Author");
        m.put("isbn", isbn);
        m.put("description", "A new book");
        m.put("price", 499.00);
        m.put("imageUrl", "https://img.example.com/book.png");
        m.put("stockQty", 12);
        m.put("dispatchDays", 1);
        m.put("categoryId", fiction.getId());
        m.put("brandId", brandA.getId());
        return m;
    }

    // =================================================================== PUBLIC

    @Test
    void categoriesArePublicSortedByNameAndShowDescription() throws Exception {
        MvcResult result = getJson("/categories");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode all = body(result);
        List<String> names = StreamSupport.stream(all.spliterator(), false).map(n -> n.get("name").asText()).toList();
        assertThat(names).containsSubsequence(PREFIX + " Empty", PREFIX + " Fiction", PREFIX + " Science");
        assertThat(names).isSortedAccordingTo(String::compareTo);
        JsonNode fictionNode = StreamSupport.stream(all.spliterator(), false)
                .filter(n -> n.get("id").asLong() == fiction.getId()).findFirst().orElseThrow();
        assertThat(fictionNode.get("description").asText()).isEqualTo("Novels");
        assertThat(fictionNode.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "description");
    }

    @Test
    void brandsArePublicAndSortedByName() throws Exception {
        MvcResult result = getJson("/brands");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> names = StreamSupport.stream(body(result).spliterator(), false).map(n -> n.get("name").asText()).toList();
        assertThat(names).containsSubsequence(PREFIX + " Brand A", PREFIX + " Brand B", PREFIX + " Brand Empty");
        assertThat(names).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void productListHasExactlyTheDocumentedShapeAndNoEntityInternals() throws Exception {
        MvcResult result = getJson(productList("categoryId=" + science.getId() + "&sort=price_asc"));

        JsonNode page = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.fieldNames()).toIterable().containsExactlyInAnyOrder("page", "size", "totalElements", "totalPages", "content");
        JsonNode first = page.get("content").get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "title", "author", "isbn", "description",
                "price", "category", "brand", "stockQty", "inStock", "estimatedDeliveryDate", "version");
        assertThat(first.get("category").fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name");
        assertThat(first.get("brand").fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hibernate", "createdAt", "dispatchDays", "\"active\"");
    }

    @Test
    void productListOnlyContainsActiveProducts() throws Exception {
        JsonNode page = body(getJson(productList("categoryId=" + fiction.getId())));

        assertThat(titles(page.get("content"))).containsExactly("CTX Alpha", "CTX Bravo", "CTX Charlie", "CTX Golf");
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void productListUsesDefaultPagingFromTheSpec() throws Exception {
        JsonNode page = body(getJson(productList("categoryId=" + fiction.getId())));

        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void paginationSplitsTheResultAndReportsTotals() throws Exception {
        String base = "categoryId=" + fiction.getId() + "&size=3";

        JsonNode first = body(getJson(productList(base + "&page=0")));
        JsonNode second = body(getJson(productList(base + "&page=1")));
        JsonNode beyond = body(getJson(productList(base + "&page=2")));

        assertThat(titles(first.get("content"))).containsExactly("CTX Alpha", "CTX Bravo", "CTX Charlie");
        assertThat(titles(second.get("content"))).containsExactly("CTX Golf");
        assertThat(beyond.get("content")).isEmpty();
        for (JsonNode p : new JsonNode[] {first, second, beyond}) {
            assertThat(p.get("totalElements").asLong()).isEqualTo(4);
            assertThat(p.get("totalPages").asInt()).isEqualTo(2);
            assertThat(p.get("size").asInt()).isEqualTo(3);
        }
        assertThat(second.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void sortOrdersAreApplied() throws Exception {
        String base = "categoryId=" + fiction.getId() + "&sort=";

        assertThat(titles(body(getJson(productList(base + "title_asc"))).get("content")))
                .containsExactly("CTX Alpha", "CTX Bravo", "CTX Charlie", "CTX Golf");
        assertThat(titles(body(getJson(productList(base + "title_desc"))).get("content")))
                .containsExactly("CTX Golf", "CTX Charlie", "CTX Bravo", "CTX Alpha");
        assertThat(titles(body(getJson(productList(base + "price_asc"))).get("content")))
                .containsExactly("CTX Alpha", "CTX Golf", "CTX Bravo", "CTX Charlie");
        assertThat(titles(body(getJson(productList(base + "price_desc"))).get("content")))
                .containsExactly("CTX Charlie", "CTX Bravo", "CTX Golf", "CTX Alpha");
        assertThat(titles(body(getJson(productList(base + "newest"))).get("content")))
                .containsExactly("CTX Golf", "CTX Charlie", "CTX Bravo", "CTX Alpha");
    }

    @Test
    void filterByBrandAndByCategoryAndBoth() throws Exception {
        assertThat(titles(body(getJson(productList("brandId=" + brandB.getId()))).get("content")))
                .containsExactly("CTX Charlie", "CTX Echo");
        assertThat(titles(body(getJson(productList("categoryId=" + science.getId()))).get("content")))
                .containsExactly("CTX Delta", "CTX Echo");
        assertThat(titles(body(getJson(productList("categoryId=" + fiction.getId() + "&brandId=" + brandB.getId()))).get("content")))
                .containsExactly("CTX Charlie");
        assertThat(body(getJson(productList("categoryId=" + emptyCategory.getId()))).get("content")).isEmpty();
    }

    @Test
    void filteringByAnUnknownCategoryOrBrandReturnsAnEmptyPage() throws Exception {
        MvcResult result = getJson(productList("categoryId=999999"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("content")).isEmpty();
        assertThat(body(result).get("totalElements").asLong()).isZero();
    }

    @Test
    void searchMatchesTitleAuthorAndIsbnCaseInsensitively() throws Exception {
        // a space inside the term, in three letter cases (this is what the double-encoded version never exercised)
        assertThat(found("q", "ctx alpha")).containsExactly("CTX Alpha");
        assertThat(found("q", "CTX ALPHA")).containsExactly("CTX Alpha");
        assertThat(found("q", "Ctx AlPhA")).containsExactly("CTX Alpha");
        assertThat(found("q", "zed SMITH")).containsExactly("CTX Bravo");
        assertThat(found("q", "9780000001003")).containsExactly("CTX Charlie");
        assertThat(found("q", "nothing-matches-this-zzz")).isEmpty();
    }

    @Test
    void searchMatchesPartOfAWordAnywhereInTheField() throws Exception {
        assertThat(found("q", "lph")).containsExactly("CTX Alpha");          // middle of the title
        assertThat(found("q", "smi")).containsExactly("CTX Bravo");          // middle of the author
        assertThat(found("q", "00000100")).hasSize(6);                       // part of the ISBN: 6 active fixtures
    }

    @Test
    void searchTrimsSurroundingWhitespace() throws Exception {
        assertThat(found("q", "  ctx alpha  ")).containsExactly("CTX Alpha");
    }

    @Test
    void searchCombinesWithFiltersAndNeverReturnsInactiveProducts() throws Exception {
        assertThat(found("q", "ctx", "categoryId", String.valueOf(science.getId()))).containsExactly("CTX Delta", "CTX Echo");
        assertThat(found("q", "ctx", "brandId", String.valueOf(brandB.getId()))).containsExactly("CTX Charlie", "CTX Echo");
        assertThat(found("q", "foxtrot")).isEmpty();
    }

    // ------------------------------------------------- LIKE wildcards are plain text

    /**
     * Seeds products whose title/author/ISBN-free text contains a literal % or _ or \ and neighbours that WOULD match
     * if the character were treated as a SQL LIKE wildcard. A broken escape makes the neighbours (or everything)
     * show up, so these assertions are not satisfied by an empty result.
     */
    private void seedWildcardProducts() {
        product("CTX 50% Off Guide", "Ann Writer", "9780000003001", "10.00", 1, 0, true, fiction, brandA);
        product("CTX 50 Percent Guide", "Ann Writer", "9780000003002", "10.00", 1, 0, true, fiction, brandA);
        product("CTX 5000 Digits Guide", "Ann Writer", "9780000003003", "10.00", 1, 0, true, fiction, brandA);
        product("CTX snake_case Handbook", "Ann Writer", "9780000003004", "10.00", 1, 0, true, fiction, brandA);
        product("CTX snakeXcase Handbook", "Ann Writer", "9780000003005", "10.00", 1, 0, true, fiction, brandA);
        product("CTX Path C:\\Temp Notes", "Ann Writer", "9780000003006", "10.00", 1, 0, true, fiction, brandA);
        product("CTX Path C:Temp Notes", "Ann Writer", "9780000003007", "10.00", 1, 0, true, fiction, brandA);
        product("CTX Plain Title", "100% Honest_Writer", "9780000003008", "10.00", 1, 0, true, fiction, brandA);
    }

    @Test
    void percentSignIsALiteralCharacterNotAWildcard() throws Exception {
        seedWildcardProducts();

        // only the title that really contains "50%": not "50 Percent", not "5000"
        assertThat(found("q", "50%")).containsExactly("CTX 50% Off Guide");
        assertThat(found("q", "50% off")).containsExactly("CTX 50% Off Guide");
        // "5%0" would match "5000" and "50 Percent" if % were a wildcard
        assertThat(found("q", "5%0")).isEmpty();
        // "%" alone matches only the products that contain a percent sign (title of one, author of another)
        assertThat(found("q", "%")).containsExactlyInAnyOrder("CTX 50% Off Guide", "CTX Plain Title");
        assertThat(found("q", "%%")).isEmpty();
    }

    @Test
    void underscoreIsALiteralCharacterNotASingleCharacterWildcard() throws Exception {
        seedWildcardProducts();

        assertThat(found("q", "snake_case")).containsExactly("CTX snake_case Handbook");   // not snakeXcase
        assertThat(found("q", "snake_")).containsExactly("CTX snake_case Handbook");
        assertThat(found("q", "_")).containsExactlyInAnyOrder("CTX snake_case Handbook", "CTX Plain Title");
        assertThat(found("q", "C_X")).isEmpty();                    // would match "CTX" if _ were a wildcard
        assertThat(found("q", "snakeYcase")).isEmpty();
    }

    @Test
    void backslashIsALiteralCharacterNotAnEscapeCharacter() throws Exception {
        seedWildcardProducts();

        assertThat(found("q", "C:\\Temp")).containsExactly("CTX Path C:\\Temp Notes");     // not "C:Temp"
        assertThat(found("q", "\\")).containsExactly("CTX Path C:\\Temp Notes");
        assertThat(found("q", "\\%")).isEmpty();                    // backslash then percent: no such text
        assertThat(found("q", "50\\%")).isEmpty();
    }

    @Test
    void wildcardCharactersInTheAuthorFieldAreAlsoLiteral() throws Exception {
        seedWildcardProducts();

        assertThat(found("q", "Honest_Writer")).containsExactly("CTX Plain Title");
        assertThat(found("q", "Honest-Writer")).isEmpty();
        assertThat(found("q", "100%")).containsExactly("CTX Plain Title");
    }

    @Test
    void aWildcardOnlySearchCannotListTheWholeCatalogue() throws Exception {
        seedWildcardProducts();
        long everything = body(search("size", "100")).get("totalElements").asLong();

        for (String q : new String[] {"%", "_", "%%", "__", "%_%", "_%_"}) {
            long matches = body(search("q", q, "size", "100")).get("totalElements").asLong();
            assertThat(matches).as("q=" + q).isLessThan(everything);
        }
    }

    @ParameterizedTest(name = "q=\"{0}\" matches no unrelated product")
    @ValueSource(strings = {"%%%", "__", "%_%", "_%_", "CTX%Alpha", "CTX_Alpha", "C%X", "A_pha", "%lpha%x"})
    void wildcardPatternsMatchNothingWhenNoProductContainsThemLiterally(String q) throws Exception {
        // the standard fixtures have no % or _ anywhere, so any hit means a wildcard was interpreted
        assertThat(found("q", q)).isEmpty();
    }

    @ParameterizedTest(name = "q=\"{0}\" is data, not SQL")
    @ValueSource(strings = {
            "' OR '1'='1", "'; DROP TABLE products; --", "\" OR \"\"=\"", "') OR 1=1 --", "x' UNION SELECT * FROM users --",
            "1; DELETE FROM products", "\\' OR 1=1 --"})
    void sqlInjectionLikeTextIsTreatedAsPlainSearchText(String q) throws Exception {
        long before = products.count();

        assertThat(found("q", q)).isEmpty();
        assertThat(products.count()).isEqualTo(before);                    // nothing dropped or deleted
        assertThat(found("q", "ctx")).isNotEmpty();                        // and the catalogue still works
    }

    @Test
    void searchTextWithSqlQuotesFindsAProductThatReallyContainsThem() throws Exception {
        product("CTX O'Brien's Guide", "Ann Writer", "9780000003010", "10.00", 1, 0, true, fiction, brandA);

        assertThat(found("q", "o'brien")).containsExactly("CTX O'Brien's Guide");
    }

    @Test
    void rawEncodedQueryStringsAreDecodedExactlyOnce() throws Exception {
        // TestSupport.get() takes a RAW query string like a client would write it and decodes it once
        seedWildcardProducts();

        assertThat(titles(body(call(get("/products?q=ctx%20alpha"))).get("content"))).containsExactly("CTX Alpha");
        assertThat(titles(body(call(get("/products?q=ctx+alpha"))).get("content"))).containsExactly("CTX Alpha");
        assertThat(titles(body(call(get("/products?q=50%25"))).get("content"))).containsExactly("CTX 50% Off Guide");
        assertThat(titles(body(call(get("/products?q=snake_case"))).get("content"))).containsExactly("CTX snake_case Handbook");
        assertThat(titles(body(call(get("/products?q=%25%25"))).get("content"))).isEmpty();
        // "%2520" is the text "%20" (a percent sign followed by 20): decoding twice would turn it into a space
        assertThat(titles(body(call(get("/products?q=%2520"))).get("content"))).isEmpty();
    }

    @Test
    void productDetailsContainTentativeDeliveryDateAndNestedCategoryAndBrand() throws Exception {
        MvcResult result = getJson("/products/" + alpha.getId());

        JsonNode p = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(p.get("id").asLong()).isEqualTo(alpha.getId());
        assertThat(p.get("title").asText()).isEqualTo("CTX Alpha");
        assertThat(p.get("author").asText()).isEqualTo("Ann Writer");
        assertThat(p.get("isbn").asText()).isEqualTo("9780000001001");
        assertThat(p.get("price").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(p.get("category").get("id").asLong()).isEqualTo(fiction.getId());
        assertThat(p.get("category").get("name").asText()).isEqualTo("CTX Fiction");
        assertThat(p.get("brand").get("id").asLong()).isEqualTo(brandA.getId());
        assertThat(p.get("stockQty").asInt()).isEqualTo(5);
        assertThat(p.get("inStock").asBoolean()).isTrue();
        // today + dispatchDays (2) + transit days (3 by default)
        assertThat(LocalDate.parse(p.get("estimatedDeliveryDate").asText()))
                .isEqualTo(LocalDate.now(clock).plusDays(2 + properties.delivery().transitDays()));
    }

    @Test
    void deliveryDateDependsOnTheProductsDispatchDays() throws Exception {
        LocalDate today = LocalDate.now(clock);
        int transit = properties.delivery().transitDays();

        assertThat(LocalDate.parse(body(getJson("/products/" + bravo.getId())).get("estimatedDeliveryDate").asText()))
                .isEqualTo(today.plusDays(transit));
        assertThat(LocalDate.parse(body(getJson("/products/" + delta.getId())).get("estimatedDeliveryDate").asText()))
                .isEqualTo(today.plusDays(5 + transit));
        // the list carries the same date
        JsonNode inList = body(getJson(productList("categoryId=" + science.getId()))).get("content").get(0);
        assertThat(LocalDate.parse(inList.get("estimatedDeliveryDate").asText())).isEqualTo(today.plusDays(5 + transit));
    }

    @Test
    void outOfStockProductIsStillListedButFlagged() throws Exception {
        JsonNode p = body(getJson("/products/" + delta.getId()));

        assertThat(p.get("stockQty").asInt()).isZero();
        assertThat(p.get("inStock").asBoolean()).isFalse();
    }

    @Test
    void unknownAndInactiveProductsAre404() throws Exception {
        assertError(getJson("/products/999999"), 404, "NOT_FOUND");
        assertError(getJson("/products/" + foxtrot.getId()), 404, "NOT_FOUND");
        assertError(getJson("/products/" + foxtrot.getId() + "/related"), 404, "NOT_FOUND");
        assertError(getJson("/products/999999/related"), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------------ related

    @Test
    void relatedProductsRankBothMatchesFirstThenCategoryOrBrandMatches() throws Exception {
        MvcResult result = getJson("/products/" + alpha.getId() + "/related");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // Bravo and Golf: same category AND brand. Charlie: same category only.
        // Not included: Alpha itself, Delta (same brand but out of stock), Echo (no match), Foxtrot (inactive).
        assertThat(titles(body(result))).containsExactly("CTX Bravo", "CTX Golf", "CTX Charlie");
    }

    @Test
    void relatedProductsRespectTheLimit() throws Exception {
        assertThat(titles(body(getJson("/products/" + alpha.getId() + "/related?limit=2")))).containsExactly("CTX Bravo", "CTX Golf");
        assertThat(titles(body(getJson("/products/" + alpha.getId() + "/related?limit=1")))).containsExactly("CTX Bravo");
    }

    @Test
    void relatedProductsMatchOnBrandAloneAndExcludeTheProductItself() throws Exception {
        // Delta is Science / A: Echo matches the category, Alpha, Bravo and Golf match the brand.
        List<String> related = titles(body(getJson("/products/" + delta.getId() + "/related")));

        assertThat(related).containsExactlyInAnyOrder("CTX Alpha", "CTX Bravo", "CTX Echo", "CTX Golf");
        assertThat(related).doesNotContain("CTX Delta", "CTX Foxtrot");
    }

    @Test
    void relatedProductsAreEmptyWhenNothingIsRelated() throws Exception {
        Product lonely = product("CTX Lonely", "Ann Writer", "9780000001099", "10.00", 1, 0, true, emptyCategory, emptyBrand);

        MvcResult result = getJson("/products/" + lonely.getId() + "/related");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).isEmpty();
    }

    @Test
    void relatedProductsHaveTheFullProductShape() throws Exception {
        JsonNode first = body(getJson("/products/" + alpha.getId() + "/related")).get(0);

        assertThat(first.get("category").get("name").asText()).isEqualTo("CTX Fiction");
        assertThat(first.get("brand").get("name").asText()).isEqualTo("CTX Brand A");
        assertThat(first.has("estimatedDeliveryDate")).isTrue();
        assertThat(first.get("inStock").asBoolean()).isTrue();
    }

    // -------------------------------------------------------------- brand browse

    @Test
    void brandProductsListsActiveProductsOfTheBrandWithPaging() throws Exception {
        JsonNode all = body(getJson("/brands/" + brandA.getId() + "/products"));
        JsonNode firstPage = body(getJson("/brands/" + brandA.getId() + "/products?size=2"));
        JsonNode secondPage = body(getJson("/brands/" + brandA.getId() + "/products?size=2&page=1"));

        assertThat(titles(all.get("content"))).containsExactly("CTX Alpha", "CTX Bravo", "CTX Delta", "CTX Golf");
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(titles(firstPage.get("content"))).containsExactly("CTX Alpha", "CTX Bravo");
        assertThat(titles(secondPage.get("content"))).containsExactly("CTX Delta", "CTX Golf");
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void brandWithoutProductsGivesAnEmptyPageButUnknownBrandIs404() throws Exception {
        MvcResult empty = getJson("/brands/" + emptyBrand.getId() + "/products");

        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(empty).get("content")).isEmpty();
        assertError(getJson("/brands/999999/products"), 404, "NOT_FOUND");
    }

    // --------------------------------------------------------------- bad input

    /** Path and query parameter pairs, none of which needs URL encoding. */
    @ParameterizedTest(name = "GET {0} {1} -> 400 VALIDATION_FAILED")
    @CsvSource(delimiter = '|', value = {
            "/products | page=-1", "/products | size=0", "/products | size=101", "/products | sort=bogus",
            "/products | categoryId=0", "/products | brandId=-5", "/products | categoryId=abc", "/products | page=abc",
            "/products | size=abc", "/products | brandId=abc", "/products | sort=TITLE_ASC", "/products | sort=price",
            "/products/0 | ", "/products/abc | ", "/products/1/related | limit=0", "/products/1/related | limit=51",
            "/products/1/related | limit=x", "/products/0/related | ", "/brands/0/products | ", "/brands/abc/products | ",
            "/brands/1/products | size=101", "/brands/1/products | size=0", "/brands/1/products | page=-1"})
    void invalidParametersAreRejectedWith400(String path, String query) throws Exception {
        MockHttpServletRequestBuilder request = get(path.trim());
        if (query != null && !query.isBlank()) {
            String[] nv = query.trim().split("=", 2);
            request.param(nv[0], nv.length > 1 ? nv[1] : "");
        }
        MvcResult result = call(request);

        assertError(result, 400, "VALIDATION_FAILED");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "at com.capstone");
    }

    @ParameterizedTest(name = "q=\"{0}\" (blank) -> 400 VALIDATION_FAILED on field q")
    @ValueSource(strings = {"", " ", "  ", "     ", "\t", "\n", " \t \n "})
    void emptyOrWhitespaceOnlySearchTextIsRejectedWith400(String q) throws Exception {
        assertBlankQueryError(search("q", q));
    }

    @Test
    void whitespaceOnlySearchIsRejectedEvenWhenCombinedWithOtherValidFilters() throws Exception {
        assertBlankQueryError(search("q", "   ", "categoryId", String.valueOf(fiction.getId()), "size", "5"));
    }

    @Test
    void searchTextNeedsAtLeastOneNonBlankCharacterButSingleCharactersAreFine() throws Exception {
        assertThat(search("q", "a").getResponse().getStatus()).isEqualTo(200);
        assertThat(search("q", " a ").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void omittingSearchTextIsNotAnError() throws Exception {
        assertThat(search().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void queryTextOver100CharactersIsRejected() throws Exception {
        assertValidationError(search("q", "a".repeat(101)), "q");
    }

    @Test
    void maximumAllowedValuesAreAccepted() throws Exception {
        assertThat(search("size", "100", "q", "a".repeat(100)).getResponse().getStatus()).isEqualTo(200);
        assertThat(getWith("/products/" + alpha.getId() + "/related", "limit", "50").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void validationErrorBodyNamesTheOffendingParameterAndNeverEchoesTheValue() throws Exception {
        MvcResult result = search("size", "7654321");

        assertValidationError(result, "size");
        // the timestamp is random digits, so only the messages are checked for the rejected value
        for (JsonNode fieldError : body(result).get("fieldErrors")) {
            assertThat(fieldError.get("message").asText()).doesNotContain("7654321");
        }
        assertThat(body(result).get("message").asText()).doesNotContain("7654321");
    }

    // =================================================================== ADMIN

    @Test
    void adminCreatesACategory() throws Exception {
        MvcResult result = send(post("/admin/categories"), adminToken, Map.of("name", " CTX Poetry ", "description", "Verse"));

        JsonNode c = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(c.get("id").asLong()).isPositive();
        assertThat(c.get("name").asText()).isEqualTo("CTX Poetry");   // trimmed
        assertThat(c.get("description").asText()).isEqualTo("Verse");
        // and it is now publicly visible
        assertThat(StreamSupport.stream(body(getJson("/categories")).spliterator(), false)
                .map(n -> n.get("name").asText())).contains("CTX Poetry");
    }

    @Test
    void adminCreatesABrandWithoutDescription() throws Exception {
        MvcResult result = send(post("/admin/brands"), adminToken, Map.of("name", "CTX New Press"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("name").asText()).isEqualTo("CTX New Press");
        assertThat(body(result).has("description")).isFalse();
    }

    @Test
    void duplicateCategoryAndBrandNamesAreConflictsEvenWithDifferentCase() throws Exception {
        assertError(send(post("/admin/categories"), adminToken, Map.of("name", "ctx fiction")), 409, "DUPLICATE_RESOURCE");
        assertError(send(post("/admin/brands"), adminToken, Map.of("name", "CTX BRAND A")), 409, "DUPLICATE_RESOURCE");
    }

    @Test
    void invalidCategoryAndBrandRequestsAre400() throws Exception {
        assertValidationError(send(post("/admin/categories"), adminToken, Map.of("name", "x")), "name");
        assertValidationError(send(post("/admin/categories"), adminToken, Map.of("name", "   ")), "name");
        assertValidationError(send(post("/admin/categories"), adminToken, Map.of("description", "no name")), "name");
        assertValidationError(send(post("/admin/categories"), adminToken, Map.of("name", "CTX " + "n".repeat(100))), "name");
        assertValidationError(send(post("/admin/brands"), adminToken, Map.of("name", "CTX Ok", "description", "d".repeat(501))), "description");
        assertValidationError(send(post("/admin/brands"), adminToken, Map.of("name", "")), "name");
    }

    @Test
    void adminCreatesAProductWithCategoryBrandAndDefaultActive() throws Exception {
        MvcResult result = send(post("/admin/products"), adminToken, productRequest("  CTX Created  ", "9780000002001"));

        JsonNode p = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(p.get("id").asLong()).isPositive();
        assertThat(p.get("title").asText()).isEqualTo("CTX Created");
        assertThat(p.get("price").decimalValue()).isEqualByComparingTo("499.00");
        assertThat(p.get("category").get("id").asLong()).isEqualTo(fiction.getId());
        assertThat(p.get("brand").get("id").asLong()).isEqualTo(brandA.getId());
        assertThat(p.get("stockQty").asInt()).isEqualTo(12);
        assertThat(p.get("inStock").asBoolean()).isTrue();
        assertThat(p.get("version").asLong()).isZero();
        assertThat(LocalDate.parse(p.get("estimatedDeliveryDate").asText()))
                .isEqualTo(LocalDate.now(clock).plusDays(1 + properties.delivery().transitDays()));
        // active was omitted, so the product is visible to customers
        assertThat(getJson("/products/" + p.get("id").asLong()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void adminCanCreateAnInactiveProductWhichCustomersCannotSee() throws Exception {
        Map<String, Object> request = productRequest("CTX Hidden", "9780000002002");
        request.put("active", false);

        MvcResult result = send(post("/admin/products"), adminToken, request);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertError(getJson("/products/" + body(result).get("id").asLong()), 404, "NOT_FOUND");
    }

    @Test
    void zeroStockProductIsAllowedAndReportedAsNotInStock() throws Exception {
        Map<String, Object> request = productRequest("CTX Sold Out", "9780000002003");
        request.put("stockQty", 0);

        JsonNode p = body(send(post("/admin/products"), adminToken, request));

        assertThat(p.get("stockQty").asInt()).isZero();
        assertThat(p.get("inStock").asBoolean()).isFalse();
    }

    @Test
    void tenDigitIsbnIsAccepted() throws Exception {
        assertThat(send(post("/admin/products"), adminToken, productRequest("CTX Ten", "0123456789")).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void missingCategoryOrBrandReferenceIs404AndNothingIsSaved() throws Exception {
        Map<String, Object> badCategory = productRequest("CTX Bad Cat", "9780000002004");
        badCategory.put("categoryId", 999999);
        Map<String, Object> badBrand = productRequest("CTX Bad Brand", "9780000002005");
        badBrand.put("brandId", 999999);

        assertError(send(post("/admin/products"), adminToken, badCategory), 404, "NOT_FOUND");
        assertError(send(post("/admin/products"), adminToken, badBrand), 404, "NOT_FOUND");
        assertThat(products.existsByIsbn("9780000002004")).isFalse();
        assertThat(products.existsByIsbn("9780000002005")).isFalse();
    }

    @Test
    void duplicateIsbnIs409() throws Exception {
        assertError(send(post("/admin/products"), adminToken, productRequest("CTX Dup", alpha.getIsbn())), 409, "DUPLICATE_RESOURCE");
    }

    @Test
    void invalidPricesAreRejected() throws Exception {
        for (Object price : new Object[] {0, 0.0, -1, -0.01, 0.001, 12.345, 100000000.00}) {
            Map<String, Object> request = productRequest("CTX Price", "9780000002010");
            request.put("price", price);
            assertValidationError(send(post("/admin/products"), adminToken, request), "price");
        }
        Map<String, Object> missing = productRequest("CTX Price", "9780000002010");
        missing.remove("price");
        assertValidationError(send(post("/admin/products"), adminToken, missing), "price");
        assertThat(products.existsByIsbn("9780000002010")).isFalse();
    }

    @Test
    void smallestValidPriceIsAccepted() throws Exception {
        Map<String, Object> request = productRequest("CTX Cheap", "9780000002011");
        request.put("price", 0.01);

        assertThat(send(post("/admin/products"), adminToken, request).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void negativeOrFractionalStockIsRejected() throws Exception {
        Map<String, Object> negative = productRequest("CTX Stock", "9780000002020");
        negative.put("stockQty", -1);
        Map<String, Object> missing = productRequest("CTX Stock", "9780000002020");
        missing.remove("stockQty");
        Map<String, Object> fractional = productRequest("CTX Stock", "9780000002020");
        fractional.put("stockQty", 1.5);

        assertValidationError(send(post("/admin/products"), adminToken, negative), "stockQty");
        assertValidationError(send(post("/admin/products"), adminToken, missing), "stockQty");
        assertError(send(post("/admin/products"), adminToken, fractional), 400, "VALIDATION_FAILED");
        assertThat(products.existsByIsbn("9780000002020")).isFalse();
    }
    /** Guards spring.jackson.deserialization.accept-float-as-int=false: no silent truncation of integer fields. */
    @ParameterizedTest
    @CsvSource({"stockQty,1.5", "stockQty,1.0", "dispatchDays,2.0", "dispatchDays,2.9", "categoryId,1.0", "brandId,1.5"})
    void floatsAreRejectedForIntegerFieldsInsteadOfBeingTruncated(String field, String value) throws Exception {
        String payload = json.writeValueAsString(productRequest("CTX Float", "9780000002050"))
                .replaceFirst("\"" + field + "\":[^,}]+", "\"" + field + "\":" + value);
        MvcResult create = call(bearer(post("/admin/products").contentType(MediaType.APPLICATION_JSON).content(payload), adminToken));
        assertError(create, 400, "VALIDATION_FAILED");
        assertThat(products.existsByIsbn("9780000002050")).isFalse();

        long v0 = alphaVersion();
        MvcResult update = call(bearer(put("/admin/products/" + alpha.getId()).contentType(MediaType.APPLICATION_JSON).content(payload), adminToken));
        assertError(update, 400, "VALIDATION_FAILED");
        assertThat(alphaVersion()).isEqualTo(v0);
    }

    @Test
    void aFractionalVersionIsRejectedAndChangesNothing() throws Exception {
        long v0 = alphaVersion();
        Map<String, Object> request = productRequest("CTX Alpha float version", alpha.getIsbn());
        request.put("version", v0 + 0.5);

        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, request), 400, "VALIDATION_FAILED");
        assertThat(alphaVersion()).isEqualTo(v0);
    }



    @Test
    void invalidDispatchDaysAreRejected() throws Exception {
        for (int days : new int[] {-1, 31}) {
            Map<String, Object> request = productRequest("CTX Days", "9780000002030");
            request.put("dispatchDays", days);
            assertValidationError(send(post("/admin/products"), adminToken, request), "dispatchDays");
        }
    }

    @Test
    void invalidIsbnTitleAuthorAndUrlAreRejected() throws Exception {
        for (String isbn : new String[] {"123", "978-0-13-595705-9", "97800000020400", "abcdefghij", "978000000204x"}) {
            assertValidationError(send(post("/admin/products"), adminToken, productRequest("CTX Isbn", isbn)), "isbn");
        }
        Map<String, Object> blankTitle = productRequest("   ", "9780000002041");
        Map<String, Object> longTitle = productRequest("T".repeat(201), "9780000002042");
        Map<String, Object> blankAuthor = productRequest("CTX Author", "9780000002043");
        blankAuthor.put("author", " ");
        Map<String, Object> badUrl = productRequest("CTX Url", "9780000002044");
        badUrl.put("imageUrl", "javascript:alert(1)");
        Map<String, Object> longDescription = productRequest("CTX Desc", "9780000002045");
        longDescription.put("description", "d".repeat(2001));

        assertValidationError(send(post("/admin/products"), adminToken, blankTitle), "title");
        assertValidationError(send(post("/admin/products"), adminToken, longTitle), "title");
        assertValidationError(send(post("/admin/products"), adminToken, blankAuthor), "author");
        assertValidationError(send(post("/admin/products"), adminToken, badUrl), "imageUrl");
        assertValidationError(send(post("/admin/products"), adminToken, longDescription), "description");
    }

    @Test
    void invalidCategoryAndBrandIdsAreRejectedAsValidationErrors() throws Exception {
        Map<String, Object> zeroCategory = productRequest("CTX Ids", "9780000002050");
        zeroCategory.put("categoryId", 0);
        Map<String, Object> noBrand = productRequest("CTX Ids", "9780000002050");
        noBrand.remove("brandId");

        assertValidationError(send(post("/admin/products"), adminToken, zeroCategory), "categoryId");
        assertValidationError(send(post("/admin/products"), adminToken, noBrand), "brandId");
    }

    @Test
    void validationErrorsListEveryBrokenFieldAndNeverEchoTheInput() throws Exception {
        Map<String, Object> request = productRequest("", "bad");
        request.put("price", -5);
        request.put("stockQty", -2);

        MvcResult result = send(post("/admin/products"), adminToken, request);

        assertValidationError(result, "title");
        assertThat(StreamSupport.stream(body(result).get("fieldErrors").spliterator(), false)
                .map(e -> e.get("field").asText())).contains("title", "isbn", "price", "stockQty");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("-5", "\"bad\"");
    }

    // ------------------------------------------------------------------ update

    @Test
    void adminUpdatesAProductAndTheVersionAdvances() throws Exception {
        long before = body(getJson("/products/" + alpha.getId())).get("version").asLong();
        Map<String, Object> request = productRequest("CTX Alpha Second Edition", alpha.getIsbn());
        request.put("price", 129.50);
        request.put("stockQty", 40);
        request.put("categoryId", science.getId());
        request.put("brandId", brandB.getId());
        request.put("dispatchDays", 4);
        request.put("version", before);

        MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, request);

        JsonNode p = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(p.get("id").asLong()).isEqualTo(alpha.getId());
        assertThat(p.get("title").asText()).isEqualTo("CTX Alpha Second Edition");
        assertThat(p.get("price").decimalValue()).isEqualByComparingTo("129.50");
        assertThat(p.get("stockQty").asInt()).isEqualTo(40);
        assertThat(p.get("category").get("id").asLong()).isEqualTo(science.getId());
        assertThat(p.get("brand").get("id").asLong()).isEqualTo(brandB.getId());
        assertThat(p.get("version").asLong()).isEqualTo(before + 1);
        assertThat(LocalDate.parse(p.get("estimatedDeliveryDate").asText()))
                .isEqualTo(LocalDate.now(clock).plusDays(4 + properties.delivery().transitDays()));
        // a public read sees the change
        assertThat(body(getJson("/products/" + alpha.getId())).get("title").asText()).isEqualTo("CTX Alpha Second Edition");
    }

    @Test
    void updateWithAStaleVersionIsRefusedAndChangesNothing() throws Exception {
        long v0 = body(getJson("/products/" + alpha.getId())).get("version").asLong();
        Map<String, Object> first = productRequest("CTX Alpha v1", alpha.getIsbn());
        first.put("version", v0);
        assertThat(send(put("/admin/products/" + alpha.getId()), adminToken, first).getResponse().getStatus()).isEqualTo(200);

        // a second admin still holds version v0
        Map<String, Object> stale = productRequest("CTX Alpha stale write", alpha.getIsbn());
        stale.put("version", v0);
        MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, stale);

        assertError(result, 409, "CONCURRENT_MODIFICATION");
        assertThat(body(getJson("/products/" + alpha.getId())).get("title").asText()).isEqualTo("CTX Alpha v1");
    }

    @Test
    void staleVersionReturns409ConcurrentModificationWithTheDocumentedErrorBody() throws Exception {
        long v0 = alphaVersion();
        Map<String, Object> winner = productRequest("CTX Alpha winner", alpha.getIsbn());
        winner.put("version", v0);
        assertThat(send(put("/admin/products/" + alpha.getId()), adminToken, winner).getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> loser = productRequest("CTX Alpha loser", alpha.getIsbn());
        loser.put("version", v0);
        MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, loser);

        assertError(result, 409, "CONCURRENT_MODIFICATION");
        JsonNode b = body(result);
        assertThat(b.get("error").asText()).isEqualTo("Conflict");
        assertThat(b.get("path").asText()).isEqualTo("/api/v1/admin/products/" + alpha.getId());
        assertThat(b.has("fieldErrors")).isFalse();
        assertThat(alphaVersion()).isEqualTo(v0 + 1);                       // only the winner's write happened
        assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).isEqualTo("CTX Alpha winner");
    }

    @Test
    void aVersionFromTheFutureIsAlsoRefusedAndChangesNothing() throws Exception {
        long v0 = alphaVersion();
        Map<String, Object> request = productRequest("CTX Alpha future", alpha.getIsbn());
        request.put("version", v0 + 5);

        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, request), 409, "CONCURRENT_MODIFICATION");
        assertThat(alphaVersion()).isEqualTo(v0);
        assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).isEqualTo("CTX Alpha");
    }

    /**
     * Real concurrency: several admins all read version v and all try to save it at the same moment. Exactly one
     * write can win; every other request must get 409 CONCURRENT_MODIFICATION (never a 500) and must not overwrite
     * the winner. Repeated for several rounds.
     */
    @Test
    void concurrentUpdatesWithTheSameVersionCannotOverwriteEachOther() throws Exception {
        int threads = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 4; round++) {
                long v = alphaVersion();
                java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<Object[]>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    String title = "CTX Alpha round " + round + " writer " + t;
                    Map<String, Object> request = productRequest(title, alpha.getIsbn());
                    request.put("version", v);
                    request.put("stockQty", 100 + t);
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        MvcResult r = send(put("/admin/products/" + alpha.getId()), adminToken, request);
                        return new Object[] {r.getResponse().getStatus(), title, r.getResponse().getContentAsString()};
                    }));
                }
                ready.await();
                go.countDown();

                List<String> winners = new java.util.ArrayList<>();
                int conflicts = 0;
                for (java.util.concurrent.Future<Object[]> f : futures) {
                    Object[] outcome = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    int status = (int) outcome[0];
                    assertThat(status).as("round %d: %s", round, outcome[2]).isIn(200, 409);
                    if (status == 200) {
                        winners.add((String) outcome[1]);
                    } else {
                        conflicts++;
                        assertThat(json.readTree((String) outcome[2]).get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
                    }
                }
                assertThat(winners).as("round %d: exactly one writer wins", round).hasSize(1);
                assertThat(conflicts).isEqualTo(threads - 1);
                Product stored = products.findById(alpha.getId()).orElseThrow();
                String winner = winners.get(0);
                assertThat(stored.getTitle()).as("the stored row is exactly the winner's, no mixed write").isEqualTo(winner);
                assertThat(stored.getStockQty()).isEqualTo(100 + Integer.parseInt(winner.substring(winner.lastIndexOf(' ') + 1)));
                assertThat(stored.getVersion()).isEqualTo(v + 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** No client version: requests are allowed, but a concurrent change is still never silently overwritten. */
    @Test
    void concurrentUpdatesWithoutAVersionNeverFailWithAServerErrorAndEveryAcceptedWriteCountsOnce() throws Exception {
        int threads = 6;
        long v0 = alphaVersion();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Map<String, Object> request = productRequest("CTX Alpha free " + t, alpha.getIsbn());
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return send(put("/admin/products/" + alpha.getId()), adminToken, request).getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();

            int accepted = 0;
            for (java.util.concurrent.Future<Integer> f : futures) {
                int status = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(status).isIn(200, 409);
                if (status == 200) {
                    accepted++;
                }
            }
            assertThat(accepted).isGreaterThanOrEqualTo(1);
            // every accepted write advanced the version by exactly one, so no write was lost silently
            assertThat(alphaVersion()).isEqualTo(v0 + accepted);
            assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).startsWith("CTX Alpha free ");
        } finally {
            pool.shutdownNow();
        }
    }

    /** The JPA @Version guard itself: a copy read earlier cannot be saved over a newer row. */
    @Test
    void aStaleEntityCopyCannotBeSavedOverANewerRow() throws Exception {
        Product staleCopy = products.findById(alpha.getId()).orElseThrow();        // read at version v0
        assertThat(send(put("/admin/products/" + alpha.getId()), adminToken, productRequest("CTX Alpha newer", alpha.getIsbn()))
                .getResponse().getStatus()).isEqualTo(200);

        staleCopy.setTitle("CTX Alpha overwrite attempt");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> products.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).isEqualTo("CTX Alpha newer");
    }

    /**
     * The second layer end to end: when the JPA version check fails while saving (a change slipped in after the row
     * was loaded), the API answers 409 CONCURRENT_MODIFICATION, not 500, and exposes no internals.
     */
    @Test
    void aVersionConflictRaisedWhileSavingIsReportedAs409NotAs500() throws Exception {
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException(Product.class, alpha.getId()))
                .when(productService).update(org.mockito.ArgumentMatchers.eq(alpha.getId()), org.mockito.ArgumentMatchers.any());
        try {
            MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, productRequest("CTX Alpha", alpha.getIsbn()));

            assertError(result, 409, "CONCURRENT_MODIFICATION");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Hibernate", "Optimistic", "Product");
        } finally {
            org.mockito.Mockito.reset(productService);
        }
    }

    /** Documented behaviour when version is omitted: the update applies to the CURRENT state and succeeds. */
    @Test
    void omittedVersionAppliesToTheCurrentStateEvenAfterOtherAdminsChangedTheProduct() throws Exception {
        long v0 = alphaVersion();
        Map<String, Object> other = productRequest("CTX Alpha by someone else", alpha.getIsbn());
        other.put("version", v0);
        assertThat(send(put("/admin/products/" + alpha.getId()), adminToken, other).getResponse().getStatus()).isEqualTo(200);

        MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, productRequest("CTX Alpha no version", alpha.getIsbn()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("title").asText()).isEqualTo("CTX Alpha no version");
        assertThat(body(result).get("version").asLong()).isEqualTo(v0 + 2);
    }

    @Test
    void theVersionInTheResponseIsTheOneToSendBackOnTheNextUpdate() throws Exception {
        JsonNode afterFirst = body(send(put("/admin/products/" + alpha.getId()), adminToken,
                productRequest("CTX Alpha first", alpha.getIsbn())));

        Map<String, Object> second = productRequest("CTX Alpha second", alpha.getIsbn());
        second.put("version", afterFirst.get("version").asLong());
        MvcResult result = send(put("/admin/products/" + alpha.getId()), adminToken, second);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("version").asLong()).isEqualTo(afterFirst.get("version").asLong() + 1);
    }

    @Test
    void aStockChangeMadeBehindTheAdminsBack_forExampleByAnOrder_makesTheirStaleUpdateFail() throws Exception {
        long v0 = alphaVersion();
        Product row = products.findById(alpha.getId()).orElseThrow();
        row.setStockQty(row.getStockQty() - 1);                                   // what a purchase will do in Phase 4
        products.saveAndFlush(row);

        Map<String, Object> request = productRequest("CTX Alpha admin edit", alpha.getIsbn());
        request.put("version", v0);

        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, request), 409, "CONCURRENT_MODIFICATION");
        assertThat(products.findById(alpha.getId()).orElseThrow().getStockQty()).isEqualTo(4);
    }


    @Test
    void updateWithoutVersionStillWorks() throws Exception {
        MvcResult result = send(put("/admin/products/" + bravo.getId()), adminToken, productRequest("CTX Bravo Renamed", bravo.getIsbn()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("title").asText()).isEqualTo("CTX Bravo Renamed");
    }

    @Test
    void adminCanDeactivateAProductWhichThenDisappearsFromThePublicCatalogue() throws Exception {
        Map<String, Object> request = productRequest("CTX Bravo", bravo.getIsbn());
        request.put("active", false);

        assertThat(send(put("/admin/products/" + bravo.getId()), adminToken, request).getResponse().getStatus()).isEqualTo(200);

        assertError(getJson("/products/" + bravo.getId()), 404, "NOT_FOUND");
        assertThat(titles(body(getJson(productList("categoryId=" + fiction.getId()))).get("content"))).doesNotContain("CTX Bravo");
        assertThat(titles(body(getJson("/products/" + alpha.getId() + "/related")))).doesNotContain("CTX Bravo");
    }

    @Test
    void updateKeepingItsOwnIsbnIsFineButTakingAnotherProductsIsbnIs409() throws Exception {
        assertThat(send(put("/admin/products/" + alpha.getId()), adminToken, productRequest("CTX Alpha", alpha.getIsbn()))
                .getResponse().getStatus()).isEqualTo(200);

        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, productRequest("CTX Alpha", bravo.getIsbn())),
                409, "DUPLICATE_RESOURCE");
    }

    @Test
    void updateOfAnUnknownProductOrWithUnknownReferencesIs404() throws Exception {
        assertError(send(put("/admin/products/999999"), adminToken, productRequest("CTX Nope", "9780000002060")), 404, "NOT_FOUND");

        Map<String, Object> badCategory = productRequest("CTX Alpha", alpha.getIsbn());
        badCategory.put("categoryId", 999999);
        Map<String, Object> badBrand = productRequest("CTX Alpha", alpha.getIsbn());
        badBrand.put("brandId", 999999);
        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, badCategory), 404, "NOT_FOUND");
        assertError(send(put("/admin/products/" + alpha.getId()), adminToken, badBrand), 404, "NOT_FOUND");
        assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).isEqualTo("CTX Alpha");
    }

    @Test
    void updateRejectsInvalidDataAndLeavesTheProductUntouched() throws Exception {
        Map<String, Object> badPrice = productRequest("CTX Alpha Changed", alpha.getIsbn());
        badPrice.put("price", 0);
        Map<String, Object> badStock = productRequest("CTX Alpha Changed", alpha.getIsbn());
        badStock.put("stockQty", -1);
        Map<String, Object> badVersion = productRequest("CTX Alpha Changed", alpha.getIsbn());
        badVersion.put("version", -1);

        assertValidationError(send(put("/admin/products/" + alpha.getId()), adminToken, badPrice), "price");
        assertValidationError(send(put("/admin/products/" + alpha.getId()), adminToken, badStock), "stockQty");
        assertValidationError(send(put("/admin/products/" + alpha.getId()), adminToken, badVersion), "version");
        assertValidationError(send(put("/admin/products/0"), adminToken, productRequest("CTX Alpha", alpha.getIsbn())), "productId");

        Product stored = products.findById(alpha.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("CTX Alpha");
        assertThat(stored.getPrice()).isEqualByComparingTo("100.00");
        assertThat(stored.getStockQty()).isEqualTo(5);
    }

    // ------------------------------------------------------------ authorization

    @Test
    void adminOperationsRequireAnAdmin() throws Exception {
        Object category = Map.of("name", "CTX Forbidden");
        Object brand = Map.of("name", "CTX Forbidden");
        Object product = productRequest("CTX Forbidden", "9780000002070");

        // anonymous -> 401
        assertError(call(post("/admin/categories").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(category))), 401, "AUTHENTICATION_FAILED");
        assertError(call(post("/admin/brands").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(brand))), 401, "AUTHENTICATION_FAILED");
        assertError(call(post("/admin/products").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(product))), 401, "AUTHENTICATION_FAILED");
        assertError(call(put("/admin/products/" + alpha.getId()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(product))), 401, "AUTHENTICATION_FAILED");

        // CUSTOMER -> 403
        assertError(send(post("/admin/categories"), customerToken, category), 403, "ACCESS_DENIED");
        assertError(send(post("/admin/brands"), customerToken, brand), 403, "ACCESS_DENIED");
        assertError(send(post("/admin/products"), customerToken, product), 403, "ACCESS_DENIED");
        assertError(send(put("/admin/products/" + alpha.getId()), customerToken, product), 403, "ACCESS_DENIED");

        // nothing was created or changed
        assertThat(categories.existsByNameIgnoreCase("CTX Forbidden")).isFalse();
        assertThat(brands.existsByNameIgnoreCase("CTX Forbidden")).isFalse();
        assertThat(products.existsByIsbn("9780000002070")).isFalse();
        assertThat(products.findById(alpha.getId()).orElseThrow().getTitle()).isEqualTo("CTX Alpha");
    }

    @Test
    void customerCannotInjectAnAdminOperationByPassingARoleInTheBody() throws Exception {
        Map<String, Object> request = productRequest("CTX Role Injection", "9780000002071");
        request.put("role", "ADMIN");

        assertError(send(post("/admin/products"), customerToken, request), 403, "ACCESS_DENIED");
    }

    @Test
    void publicCatalogueEndpointsNeedNoTokenAndIgnoreAnInvalidOne() throws Exception {
        assertThat(getJson("/categories").getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/brands").getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/products/" + alpha.getId()).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(bearer(get("/products/" + alpha.getId()), "garbage")).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(bearer(get("/products/" + alpha.getId()), customerToken)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void catalogueWriteMethodsDoNotExistOnPublicPaths() throws Exception {
        assertThat(send(post("/products"), adminToken, productRequest("CTX Wrong Path", "9780000002072"))
                .getResponse().getStatus()).isIn(401, 403, 405);
        assertThat(products.existsByIsbn("9780000002072")).isFalse();
    }
}
