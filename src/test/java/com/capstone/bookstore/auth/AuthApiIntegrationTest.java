package com.capstone.bookstore.auth;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.capstone.bookstore.giftpoints.GiftPointAccountRepository;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.capstone.bookstore.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests (H2, real Flyway schema, real BCrypt, real filter chain) for
 * POST /auth/register, POST /auth/login and GET /users/me against the contract in openapi.yaml.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final String DOMAIN = "@authtest.example";
    private static final String PASSWORD = "Str0ngPass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired GiftPointAccountRepository giftAccounts;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM gift_point_accounts WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> registration(String localPart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Deepanshu Arora");
        body.put("email", localPart + DOMAIN);
        body.put("password", PASSWORD);
        body.put("phone", "9876543210");
        return body;
    }

    private MvcResult register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertErrorBody(MvcResult result, int status, String code, String path) throws Exception {
        JsonNode b = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        // every required property of ErrorResponse in openapi.yaml
        assertThat(b.fieldNames()).toIterable().contains("timestamp", "status", "error", "code", "message", "path");
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("path").asText()).isEqualTo(path);
        assertThat(b.get("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    // ----------------------------------------------------------------- register

    @Test
    void registerCreatesCustomerAndReturnsUserResponse() throws Exception {
        MvcResult result = register(registration("deepanshu"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode b = body(result);
        assertThat(b.get("id").asLong()).isPositive();
        assertThat(b.get("fullName").asText()).isEqualTo("Deepanshu Arora");
        assertThat(b.get("email").asText()).isEqualTo("deepanshu" + DOMAIN);
        assertThat(b.get("phone").asText()).isEqualTo("9876543210");
        assertThat(b.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(b.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void registerNeverExposesPasswordOrHash() throws Exception {
        MvcResult result = register(registration("nohash"));

        String raw = result.getResponse().getContentAsString();
        assertThat(raw).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD).doesNotContain("$2a$").doesNotContain("$2b$");
        assertThat(body(result).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "fullName", "email", "phone", "role", "createdAt");
    }

    @Test
    void registerStoresOnlyABcryptHash() throws Exception {
        register(registration("bcrypt"));

        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, "bcrypt" + DOMAIN);
        assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2").hasSize(60);
        assertThat(encoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void registerAlwaysCreatesCustomerEvenWhenClientSendsAdminRole() throws Exception {
        Map<String, Object> attempt = registration("sneaky");
        attempt.put("role", "ADMIN");
        attempt.put("roles", java.util.List.of("ADMIN"));
        attempt.put("admin", true);

        MvcResult result = register(attempt);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, "sneaky" + DOMAIN))
                .isEqualTo("CUSTOMER");
        // and the issued token carries CUSTOMER too
        String token = body(login("sneaky" + DOMAIN, PASSWORD)).get("accessToken").asText();
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void registerCreatesEmptyGiftPointAccount() throws Exception {
        long userId = body(register(registration("points"))).get("id").asLong();

        var account = giftAccounts.findByUserId(userId);

        assertThat(account).isPresent();
        assertThat(account.get().getBalance()).isZero();
    }

    @Test
    void registerNormalizesEmailToLowerCase() throws Exception {
        Map<String, Object> mixed = registration("MixedCase");
        mixed.put("email", "MixedCase" + DOMAIN.toUpperCase());

        MvcResult result = register(mixed);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("email").asText()).isEqualTo("mixedcase" + DOMAIN);
    }

    @Test
    void registerWithoutPhoneIsAllowed() throws Exception {
        Map<String, Object> noPhone = registration("nophone");
        noPhone.remove("phone");

        MvcResult result = register(noPhone);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).has("phone")).isFalse();
    }

    @Test
    void duplicateEmailReturns409WithEmailAlreadyRegistered() throws Exception {
        register(registration("dup"));

        MvcResult second = register(registration("dup"));

        assertErrorBody(second, 409, "EMAIL_ALREADY_REGISTERED", "/api/v1/auth/register");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "dup" + DOMAIN)).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsDetectedCaseInsensitively() throws Exception {
        register(registration("casedup"));
        Map<String, Object> upper = registration("casedup");
        upper.put("email", ("casedup" + DOMAIN).toUpperCase());

        assertErrorBody(register(upper), 409, "EMAIL_ALREADY_REGISTERED", "/api/v1/auth/register");
    }

    @Test
    void registerValidationReportsEveryInvalidFieldWithoutEchoingValues() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("fullName", "A");
        bad.put("email", "not-an-email");
        bad.put("password", "short");
        bad.put("phone", "12345");

        MvcResult result = register(bad);

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        JsonNode errors = body(result).get("fieldErrors");
        assertThat(errors).isNotNull();
        assertThat(errors).extracting(e -> e.get("field").asText()).contains("fullName", "email", "password", "phone");
        String raw = result.getResponse().getContentAsString();
        assertThat(raw).doesNotContain("not-an-email").doesNotContain("\"short\"").doesNotContain("12345");
    }

    @Test
    void registerRequiresAllMandatoryFields() throws Exception {
        MvcResult result = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertThat(body(result).get("fieldErrors")).extracting(e -> e.get("field").asText())
                .contains("fullName", "email", "password");
    }

    @Test
    void passwordMustContainLetterAndDigit() throws Exception {
        Map<String, Object> noDigit = registration("nodigit");
        noDigit.put("password", "OnlyLettersHere");
        Map<String, Object> noLetter = registration("noletter");
        noLetter.put("password", "123456789");

        assertErrorBody(register(noDigit), 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertErrorBody(register(noLetter), 400, "VALIDATION_FAILED", "/api/v1/auth/register");
    }

    /** 2 ASCII bytes + 35 two-byte characters = 72 bytes in 37 characters. */
    private static final String MULTIBYTE_72_BYTES = "a1" + "\u00e9".repeat(35);
    /** 72 ASCII characters = 72 bytes. */
    private static final String ASCII_72_BYTES = "a1" + "x".repeat(70);

    @Test
    void registerAcceptsExactly72AsciiBytesAndTheFullPasswordIsUsed() throws Exception {
        Map<String, Object> request = registration("ascii72");
        request.put("password", ASCII_72_BYTES);

        assertThat(register(request).getResponse().getStatus()).isEqualTo(201);
        assertThat(login("ascii72" + DOMAIN, ASCII_72_BYTES).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void registerRejects73AsciiBytes() throws Exception {
        Map<String, Object> request = registration("ascii73");
        request.put("password", ASCII_72_BYTES + "y");

        MvcResult result = register(request);

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertThat(body(result).get("fieldErrors").get(0).get("field").asText()).isEqualTo("password");
        assertThat(users.existsByEmail("ascii73" + DOMAIN)).isFalse();
    }

    @Test
    void registerAcceptsExactly72BytesOfMultibyteUtf8() throws Exception {
        Map<String, Object> request = registration("utf8at72");
        request.put("password", MULTIBYTE_72_BYTES);

        assertThat(MULTIBYTE_72_BYTES.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(MULTIBYTE_72_BYTES.length()).isLessThan(72);
        assertThat(register(request).getResponse().getStatus()).isEqualTo(201);
        assertThat(login("utf8at72" + DOMAIN, MULTIBYTE_72_BYTES).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void registerRejects73BytesOfMultibyteUtf8EvenThoughItIsOnly38Characters() throws Exception {
        Map<String, Object> request = registration("utf8at73");
        request.put("password", MULTIBYTE_72_BYTES + "b");

        MvcResult result = register(request);

        assertThat((MULTIBYTE_72_BYTES + "b").getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(73);
        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertThat(body(result).get("fieldErrors").get(0).get("field").asText()).isEqualTo("password");
        assertThat(users.existsByEmail("utf8at73" + DOMAIN)).isFalse();
    }

    @Test
    void registerRejectsFourByteCharactersThatExceed72Bytes() throws Exception {
        Map<String, Object> request = registration("emoji");
        request.put("password", "a1" + "\uD83D\uDE00".repeat(20)); // 42 chars but 82 UTF-8 bytes

        MvcResult result = register(request);

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertThat(body(result).get("fieldErrors").get(0).get("field").asText()).isEqualTo("password");
    }

    @Test
    void malformedJsonReturns400ValidationFailedWithoutEchoingBody() throws Exception {
        MvcResult result = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"Secr3tValue\", broken")).andReturn();

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/register");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Secr3tValue");
    }

    @Test
    void nonJsonContentTypeReturns415InErrorFormat() throws Exception {
        MvcResult result = mvc.perform(post("/auth/register").contentType(MediaType.TEXT_PLAIN).content("hello")).andReturn();

        assertErrorBody(result, 415, "VALIDATION_FAILED", "/api/v1/auth/register");
    }

    // -------------------------------------------------------------------- login

    @Test
    void loginReturnsBearerTokenAndUser() throws Exception {
        register(registration("login"));

        MvcResult result = login("login" + DOMAIN, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode b = body(result);
        assertThat(b.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(b.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(b.get("accessToken").asText().split("\\.")).hasSize(3);
        assertThat(b.get("user").get("email").asText()).isEqualTo("login" + DOMAIN);
        assertThat(b.get("user").get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        register(registration("loginci"));

        assertThat(login(("loginci" + DOMAIN).toUpperCase(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void issuedTokenOpensGetUsersMe() throws Exception {
        long id = body(register(registration("me"))).get("id").asLong();
        String token = body(login("me" + DOMAIN, PASSWORD)).get("accessToken").asText();

        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("me" + DOMAIN))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void wrongPasswordReturnsGeneric401() throws Exception {
        register(registration("wrongpw"));

        MvcResult result = login("wrongpw" + DOMAIN, "Wr0ngPassword");

        assertErrorBody(result, 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Wr0ngPassword");
    }

    @Test
    void unknownEmailReturnsTheSame401AsWrongPassword() throws Exception {
        register(registration("known"));

        MvcResult wrongPassword = login("known" + DOMAIN, "Wr0ngPassword");
        MvcResult unknownEmail = login("ghost" + DOMAIN, "Wr0ngPassword");

        assertErrorBody(unknownEmail, 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        // identical apart from the timestamp: no hint whether the e-mail exists
        JsonNode a = body(wrongPassword);
        JsonNode b = body(unknownEmail);
        assertThat(b.get("message")).isEqualTo(a.get("message"));
        assertThat(b.get("code")).isEqualTo(a.get("code"));
        assertThat(b.get("status")).isEqualTo(a.get("status"));
        assertThat(b.get("error")).isEqualTo(a.get("error"));
    }

    @Test
    void loginWithAsciiPasswordOver72CharactersReturns401NotValidationError() throws Exception {
        register(registration("longlogin"));

        assertErrorBody(login("longlogin" + DOMAIN, PASSWORD + "x".repeat(80)), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        assertErrorBody(login("longlogin" + DOMAIN, ASCII_72_BYTES + "y"), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        assertErrorBody(login("longlogin" + DOMAIN, "a1" + "x".repeat(10_000)), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
    }

    @Test
    void loginWithMultibytePasswordOver72BytesReturns401() throws Exception {
        register(registration("bytelogin"));

        // 42 characters but 82 UTF-8 bytes
        assertErrorBody(login("bytelogin" + DOMAIN, "a1" + "\uD83D\uDE00".repeat(20)), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        // 38 characters but 73 UTF-8 bytes
        assertErrorBody(login("bytelogin" + DOMAIN, MULTIBYTE_72_BYTES + "b"), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        // unknown e-mail takes the same path
        assertErrorBody(login("ghost" + DOMAIN, MULTIBYTE_72_BYTES + "b"), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
    }

    @Test
    void passwordIsNeverTruncatedSoPrefixPlusExtraCannotLogIn() throws Exception {
        Map<String, Object> ascii = registration("prefixascii");
        ascii.put("password", ASCII_72_BYTES);
        Map<String, Object> multibyte = registration("prefixutf8");
        multibyte.put("password", MULTIBYTE_72_BYTES);
        register(ascii);
        register(multibyte);

        // BCrypt alone would accept these (it only reads the first 72 bytes); the service must not
        assertErrorBody(login("prefixascii" + DOMAIN, ASCII_72_BYTES + "extra"), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        assertErrorBody(login("prefixutf8" + DOMAIN, MULTIBYTE_72_BYTES + "extra"), 401, "AUTHENTICATION_FAILED", "/api/v1/auth/login");
        // while the exact passwords do work
        assertThat(login("prefixascii" + DOMAIN, ASCII_72_BYTES).getResponse().getStatus()).isEqualTo(200);
        assertThat(login("prefixutf8" + DOMAIN, MULTIBYTE_72_BYTES).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void oversizedPasswordLoginIsIndistinguishableFromWrongPassword() throws Exception {
        register(registration("samebody"));

        JsonNode wrong = body(login("samebody" + DOMAIN, "Wr0ngPassword"));
        JsonNode oversized = body(login("samebody" + DOMAIN, PASSWORD + "x".repeat(200)));

        for (String field : new String[] {"status", "error", "code", "message", "path"}) {
            assertThat(oversized.get(field)).as(field).isEqualTo(wrong.get(field));
        }
        assertThat(oversized.has("fieldErrors")).isFalse();
        assertThat(oversized.size()).isEqualTo(wrong.size());
    }

    @Test
    void loginValidationReturns400() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bad\",\"password\":\"\"}")).andReturn();

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/login");
        assertThat(body(result).get("fieldErrors")).extracting(e -> e.get("field").asText()).contains("email", "password");
    }

    @Test
    void loginWithMissingBodyFieldsReturns400() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertErrorBody(result, 400, "VALIDATION_FAILED", "/api/v1/auth/login");
    }

    @Test
    void adminAccountsCreatedOutsideRegistrationLogInWithAdminRole() throws Exception {
        users.save(new User("Site Admin", "admin" + DOMAIN, encoder.encode("Adm1nPassw0rd"), null, Role.ADMIN));

        JsonNode b = body(login("admin" + DOMAIN, "Adm1nPassw0rd"));

        assertThat(b.get("user").get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void usersMeForDeletedUserIsTreatedAsInvalidToken() throws Exception {
        register(registration("gone"));
        String token = body(login("gone" + DOMAIN, PASSWORD)).get("accessToken").asText();
        cleanUp();

        MvcResult result = mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)).andReturn();

        assertErrorBody(result, 401, "AUTHENTICATION_FAILED", "/api/v1/users/me");
    }
}
