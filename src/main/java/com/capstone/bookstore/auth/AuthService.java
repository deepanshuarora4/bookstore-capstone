package com.capstone.bookstore.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.giftpoints.GiftPointAccount;
import com.capstone.bookstore.giftpoints.GiftPointAccountRepository;
import com.capstone.bookstore.security.JwtService;
import com.capstone.bookstore.user.Role;
import com.capstone.bookstore.user.User;
import com.capstone.bookstore.user.UserRepository;
import com.capstone.bookstore.user.UserResponse;

/**
 * Registration and login. Passwords are only ever handled as BCrypt hashes after hashing and are never logged.
 * Log lines contain the numeric user id only.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** BCrypt ignores/rejects input beyond 72 bytes; multi-byte characters can exceed it within 72 characters. */
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String TIMING_DUMMY_PASSWORD = "timing-dummy-not-a-real-password";

    private final UserRepository userRepository;
    private final GiftPointAccountRepository giftPointAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Compared against when the e-mail is unknown, so unknown and wrong-password logins cost the same time. */
    private final String timingDummyHash;

    public AuthService(UserRepository userRepository, GiftPointAccountRepository giftPointAccountRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.giftPointAccountRepository = giftPointAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.timingDummyHash = passwordEncoder.encode(TIMING_DUMMY_PASSWORD);
    }

    /** Creates a CUSTOMER account and its empty gift point account. The role can not be chosen by the caller. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.validation("password", "must be at most 72 bytes when encoded as UTF-8");
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw ApiException.emailAlreadyRegistered();
        }
        User user = new User(request.fullName().trim(), email, passwordEncoder.encode(request.password()),
                request.phone(), Role.CUSTOMER);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent registrations for the same e-mail: the unique constraint is the final guard.
            throw ApiException.emailAlreadyRegistered();
        }
        giftPointAccountRepository.save(new GiftPointAccount(user));
        log.info("Registered customer userId={}", user.getId());
        return UserResponse.from(user);
    }

    /** Verifies the credentials and issues a JWT. Every failure is the same generic AUTHENTICATION_FAILED. */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
        // A password over 72 UTF-8 bytes can never have been registered. It must not reach BCrypt as-is (BCrypt
        // would only look at the first 72 bytes, so "<valid password>extra" could authenticate). It is answered
        // like any wrong password; a dummy comparison keeps the response time the same as for a wrong password.
        boolean tooLong = request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
        String hash = user != null ? user.getPasswordHash() : timingDummyHash;
        boolean matches = passwordEncoder.matches(tooLong ? TIMING_DUMMY_PASSWORD : request.password(), hash) && !tooLong;
        if (user == null || !matches) {
            log.info("Login failed");
            throw ApiException.authenticationFailed();
        }
        log.info("Login succeeded userId={}", user.getId());
        return AuthResponse.bearer(jwtService.generateToken(user), jwtService.getExpirySeconds(), UserResponse.from(user));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
