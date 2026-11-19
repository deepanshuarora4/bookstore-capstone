package com.capstone.bookstore.security;

import static com.capstone.bookstore.TestSupport.delete;
import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static com.capstone.bookstore.TestSupport.put;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.capstone.bookstore.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the access rules from openapi.yaml against the real filter chain, using tokens created by the real
 * JwtService. Endpoints that do not exist yet (catalogue, cart, orders, ...) are still protected by the
 * chain: security runs before routing, so a request that is allowed through ends as 404 (no handler yet),
 * never 200, and a request that is refused ends as 401 / 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAccessIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwtService;
    @Autowired BookstoreProperties properties;
    @Autowired UserRepository userRepository;

    private User customer;
    private User admin;

    /** Real rows, so that tokens carry real user ids (GET /users/me looks the user up). */
    @BeforeEach
    void createUsers() {
        removeUsers();
        customer = userRepository.save(
                new User("Test Customer", "customer@tokens.example", "$2a$10$irrelevant", null, Role.CUSTOMER));
        admin = userRepository.save(
                new User("Test Admin", "admin@tokens.example", "$2a$10$irrelevant", null, Role.ADMIN));
    }

    @AfterEach
    void removeUsers() {
        userRepository.findByEmail("customer@tokens.example").ifPresent(userRepository::delete);
        userRepository.findByEmail("admin@tokens.example").ifPresent(userRepository::delete);
    }

    // ------------------------------------------------------------------ helpers

    private String tokenFor(Role role) {
        return jwtService.generateToken(role == Role.ADMIN ? admin : customer);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MvcResult run(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.fieldNames()).toIterable().contains("timestamp", "error", "message", "path");
    }

    private static Stream<Arguments> protectedCustomerRoutes() {
        return Stream.of(
                Arguments.of("GET", "/users/me"),
                Arguments.of("GET", "/cart"),
                Arguments.of("DELETE", "/cart"),
                Arguments.of("POST", "/cart/items"),
                Arguments.of("PUT", "/cart/items/1"),
                Arguments.of("DELETE", "/cart/items/1"),
                Arguments.of("GET", "/addresses"),
                Arguments.of("POST", "/addresses"),
                Arguments.of("GET", "/addresses/1"),
                Arguments.of("PUT", "/addresses/1"),
                Arguments.of("DELETE", "/addresses/1"),
                Arguments.of("GET", "/orders"),
                Arguments.of("POST", "/orders"),
                Arguments.of("GET", "/orders/buy-again"),
                Arguments.of("GET", "/orders/1"),
                Arguments.of("POST", "/orders/1/cancel"),
                Arguments.of("POST", "/orders/1/payments"),
                Arguments.of("GET", "/orders/1/payment"),
                Arguments.of("GET", "/gift-points"),
                Arguments.of("GET", "/recommendations"));
    }

    private static Stream<Arguments> adminRoutes() {
        return Stream.of(
                Arguments.of("POST", "/admin/categories"),
                Arguments.of("POST", "/admin/brands"),
                Arguments.of("POST", "/admin/products"),
                Arguments.of("PUT", "/admin/products/1"));
    }

    private static Stream<Arguments> publicRoutes() {
        return Stream.of(
                Arguments.of("GET", "/categories"),
                Arguments.of("GET", "/products"),
                Arguments.of("GET", "/products/1"),
                Arguments.of("GET", "/products/1/related"),
                Arguments.of("GET", "/brands"),
                Arguments.of("GET", "/brands/1/products"));
    }

    private static MockHttpServletRequestBuilder request(String method, String path) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        return builder.contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    // ---------------------------------------------------------------- anonymous

    @ParameterizedTest(name = "anonymous {0} {1} -> 401")
    @MethodSource("protectedCustomerRoutes")
    void anonymousCannotReachProtectedRoutes(String method, String path) throws Exception {
        MvcResult result = run(request(method, path));

        assertError(result, 401, "AUTHENTICATION_FAILED");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @ParameterizedTest(name = "anonymous {0} {1} -> 401")
    @MethodSource("adminRoutes")
    void anonymousCannotReachAdminRoutes(String method, String path) throws Exception {
        assertError(run(request(method, path)), 401, "AUTHENTICATION_FAILED");
    }

    @ParameterizedTest(name = "anonymous {0} {1} is not blocked by security")
    @MethodSource("publicRoutes")
    void publicRoutesDoNotRequireAToken(String method, String path) throws Exception {
        int status = run(request(method, path)).getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    // ----------------------------------------------------------------- CUSTOMER

    @ParameterizedTest(name = "CUSTOMER {0} {1} -> 403")
    @MethodSource("adminRoutes")
    void customerCannotReachAdminRoutes(String method, String path) throws Exception {
        MvcResult result = run(bearer(request(method, path), tokenFor(Role.CUSTOMER)));

        assertError(result, 403, "ACCESS_DENIED");
    }

    @ParameterizedTest(name = "CUSTOMER {0} {1} passes security")
    @MethodSource("protectedCustomerRoutes")
    void customerPassesSecurityOnCustomerRoutes(String method, String path) throws Exception {
        MvcResult result = run(bearer(request(method, path), tokenFor(Role.CUSTOMER)));

        assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", method, path, result.getResponse().getContentAsString())
                .isNotIn(401, 403);
    }

    @Test
    void customerReadsOwnProfileFromUsersMe() throws Exception {
        MvcResult result = run(bearer(get("/users/me"), tokenFor(Role.CUSTOMER)));
        JsonNode body = json.readTree(result.getResponse().getContentAsString());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body.get("id").asLong()).isEqualTo(customer.getId());
        assertThat(body.get("email").asText()).isEqualTo("customer@tokens.example");
        assertThat(body.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("password")).isFalse();
    }

    @Test
    void validTokenForADeletedUserGets401OnUsersMe() throws Exception {
        String token = tokenFor(Role.CUSTOMER);
        userRepository.delete(customer);

        assertError(run(bearer(get("/users/me"), token)), 401, "AUTHENTICATION_FAILED");
    }

    // -------------------------------------------------------------------- ADMIN

    @ParameterizedTest(name = "ADMIN {0} {1} passes security")
    @MethodSource("adminRoutes")
    void adminPassesSecurityOnAdminRoutes(String method, String path) throws Exception {
        int status = run(bearer(request(method, path), tokenFor(Role.ADMIN))).getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    // -------------------------------------------------------------- bad tokens

    @Test
    void expiredTokenGets401() throws Exception {
        User user = new User("Old Timer", "old@tokens.example", "$2a$10$irrelevant", null, Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", 7L);
        var past = new JwtService(properties, Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC));
        String expired = past.generateToken(user); // issued 2h ago, valid for 1h

        assertError(run(bearer(get("/users/me"), expired)), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void tokenSignedWithAnotherSecretGets401() throws Exception {
        var foreign = new JwtService(
                com.capstone.bookstore.TestSupport.properties("a-completely-different-secret-0123456789abcdef", 3600),
                Clock.systemUTC());
        User user = new User("Mallory", "mallory@tokens.example", "$2a$10$irrelevant", null, Role.ADMIN);
        ReflectionTestUtils.setField(user, "id", 1L);

        assertError(run(bearer(get("/users/me"), foreign.generateToken(user))), 401, "AUTHENTICATION_FAILED");
        assertError(run(bearer(post("/admin/products"), foreign.generateToken(user))), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void garbageTokenGets401() throws Exception {
        assertError(run(bearer(get("/users/me"), "not.a.jwt")), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void wrongAuthorizationSchemeGets401() throws Exception {
        String token = tokenFor(Role.CUSTOMER);

        assertError(run(get("/users/me").header("Authorization", "Basic " + token)), 401, "AUTHENTICATION_FAILED");
        assertError(run(get("/users/me").header("Authorization", token)), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void invalidTokenOnAPublicRouteDoesNotBreakIt() throws Exception {
        int status = run(bearer(get("/products"), "garbage")).getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    // ---------------------------------------------------------------- stateless

    @Test
    void responsesNeverCreateASessionOrSetCookies() throws Exception {
        String token = tokenFor(Role.CUSTOMER);

        MvcResult authenticated = mvc.perform(bearer(get("/orders"), token)).andReturn();
        MvcResult anonymous = mvc.perform(get("/orders")).andReturn();

        for (MvcResult r : new MvcResult[] {authenticated, anonymous}) {
            assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
            assertThat(r.getRequest().getSession(false)).isNull();
        }
    }

    @Test
    void authenticationDoesNotCarryOverBetweenRequests() throws Exception {
        String token = tokenFor(Role.CUSTOMER);

        int withToken = run(bearer(get("/orders"), token)).getResponse().getStatus();
        MvcResult withoutToken = run(get("/orders"));

        assertThat(withToken).isNotIn(401, 403);
        assertError(withoutToken, 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void customerTokenDoesNotBecomeAdminAfterAdminRequest() throws Exception {
        run(bearer(post("/admin/products"), tokenFor(Role.ADMIN)));

        assertError(run(bearer(post("/admin/products"), tokenFor(Role.CUSTOMER))), 403, "ACCESS_DENIED");
    }

    @Test
    void csrfIsNotRequiredForTokenAuthenticatedWrites() throws Exception {
        int status = run(bearer(post("/orders"), tokenFor(Role.CUSTOMER))).getResponse().getStatus();

        assertThat(status).isNotEqualTo(403);
    }

    @Test
    void noDefaultSpringUserExists() throws Exception {
        // UserDetailsServiceAutoConfiguration is excluded, so HTTP Basic with a generated password can't work.
        String basic = java.util.Base64.getEncoder().encodeToString("user:anything".getBytes());

        assertError(run(get("/users/me").header("Authorization", "Basic " + basic)), 401, "AUTHENTICATION_FAILED");
    }
}
