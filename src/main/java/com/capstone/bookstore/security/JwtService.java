package com.capstone.bookstore.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.capstone.bookstore.config.BookstoreProperties;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Creates and validates the HS256 JWTs described in openapi.yaml (claims: sub = user id, email, role, iat, exp).
 * Neither the secret nor any token is ever logged.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration expiry;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(BookstoreProperties properties, Clock clock) {
        var jwt = properties.security().jwt();
        this.key = Keys.hmacShaKeyFor(jwt.secret().getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofSeconds(jwt.expirySeconds());
        this.clock = clock;
        // Signed (JWS) tokens only: unsigned or differently-signed tokens are rejected by verifyWith(key).
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String generateToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Token lifetime in seconds, returned to clients as {@code expiresIn}. */
    public long getExpirySeconds() {
        return expiry.toSeconds();
    }

    /** Returns the user described by a valid, unexpired token, or empty for anything else. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            Long id = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            return Optional.of(new AuthenticatedUser(id, email, role));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // Log only the failure type: never the token or its content.
            log.debug("JWT rejected: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
