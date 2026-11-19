package com.capstone.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.capstone.bookstore.TestSupport;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;

import io.jsonwebtoken.Jwts;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnop";
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private JwtService service;
    private User customer;

    @BeforeEach
    void setUp() {
        service = serviceAt(SECRET, 3600, NOW);
        customer = userWithId(42L, "deepanshu.arora@example.com", Role.CUSTOMER);
    }

    private static JwtService serviceAt(String secret, long expirySeconds, Instant now) {
        return new JwtService(TestSupport.properties(secret, expirySeconds), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User userWithId(Long id, String email, Role role) {
        User user = new User("Deepanshu Arora", email, "$2a$10$irrelevant", null, role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void roundTripPreservesIdEmailAndRole() {
        String token = service.generateToken(customer);

        var parsed = service.parse(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().id()).isEqualTo(42L);
        assertThat(parsed.get().email()).isEqualTo("deepanshu.arora@example.com");
        assertThat(parsed.get().role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void adminRoleIsCarriedInTheToken() {
        String token = service.generateToken(userWithId(1L, "admin@example.com", Role.ADMIN));

        assertThat(service.parse(token)).get().extracting(AuthenticatedUser::role).isEqualTo(Role.ADMIN);
    }

    @Test
    void tokenHasTheClaimsDocumentedInOpenApi() {
        String token = service.generateToken(customer);

        var claims = Jwts.parser().verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8))).clock(() -> java.util.Date.from(NOW)).build()
                .parseSignedClaims(token);

        assertThat(claims.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.getPayload().getSubject()).isEqualTo("42");
        assertThat(claims.getPayload().get("email")).isEqualTo("deepanshu.arora@example.com");
        assertThat(claims.getPayload().get("role")).isEqualTo("CUSTOMER");
        assertThat(claims.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void expiryComesFromConfiguration() {
        assertThat(service.getExpirySeconds()).isEqualTo(3600);
        assertThat(serviceAt(SECRET, 120, NOW).getExpirySeconds()).isEqualTo(120);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service.generateToken(customer);

        JwtService later = serviceAt(SECRET, 3600, NOW.plus(Duration.ofHours(1)).plusSeconds(1));

        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void tokenIsStillValidJustBeforeExpiry() {
        String token = service.generateToken(customer);

        JwtService almost = serviceAt(SECRET, 3600, NOW.plus(Duration.ofHours(1)).minusSeconds(1));

        assertThat(almost.parse(token)).isPresent();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = serviceAt("another-secret-0123456789-abcdefghijklmnopqrstuv", 3600, NOW)
                .generateToken(customer);

        assertThat(service.parse(foreign)).isEmpty();
    }

    @Test
    void tamperedPayloadCannotEscalateToAdmin() {
        String token = service.generateToken(customer);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"role\":\"CUSTOMER\"");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace("CUSTOMER", "ADMIN").getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(service.parse(forged)).isEmpty();
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"1\",\"email\":\"x@example.com\",\"role\":\"ADMIN\","
                + "\"exp\":" + NOW.plusSeconds(600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));

        assertThat(service.parse(header + "." + payload + ".")).isEmpty();
    }

    @Test
    void garbageAndEmptyInputAreRejected() {
        assertThat(service.parse("")).isEmpty();
        assertThat(service.parse("not-a-jwt")).isEmpty();
        assertThat(service.parse("a.b.c")).isEmpty();
    }

    @Test
    void tokenWithUnknownRoleIsRejected() {
        String token = Jwts.builder().subject("1").claim("email", "x@example.com").claim("role", "SUPERUSER")
                .issuedAt(java.util.Date.from(NOW)).expiration(java.util.Date.from(NOW.plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(service.parse(token)).isEmpty();
    }
}
