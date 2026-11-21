package com.capstone.bookstore.auth;

import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.bookstore.config.BookstoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Passwords, password hashes, JWTs and the JWT secret must never reach the application log. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingTest {

    private static final String EMAIL = "logcheck@logtest.example";
    private static final String PASSWORD = "L0gCheckSecret";
    private static final String WRONG_PASSWORD = "Wr0ngLogSecret";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookstoreProperties properties;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM gift_point_accounts WHERE user_id IN (SELECT id FROM users WHERE email = ?)", EMAIL);
        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);
    }

    @Test
    void registerLoginFailureAndTokenUseNeverLogSecrets(CapturedOutput output) throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("fullName", "Log Check", "email", EMAIL, "password", PASSWORD)))).andReturn();
        var login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("email", EMAIL, "password", PASSWORD)))).andReturn();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("email", EMAIL, "password", WRONG_PASSWORD)))).andReturn();
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"" + PASSWORD + "\", broken")).andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)).andReturn();
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token + "tampered")).andReturn();

        String storedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);
        String log = output.getAll();

        assertThat(log).contains("Registered customer userId=").contains("Login succeeded userId=").contains("Login failed");
        assertThat(log).doesNotContain(PASSWORD).doesNotContain(WRONG_PASSWORD);
        assertThat(log).doesNotContain(storedHash);
        assertThat(log).doesNotContain(token);
        assertThat(log).doesNotContain(properties.security().jwt().secret());
        assertThat(log).doesNotContain("Using generated security password");
    }

    @Test
    void requestObjectsMaskPasswordsInToString() {
        assertThat(new RegisterRequest("A B", EMAIL, PASSWORD, null).toString()).doesNotContain(PASSWORD).contains("***");
        assertThat(new LoginRequest(EMAIL, PASSWORD).toString()).doesNotContain(PASSWORD).contains("***");
        assertThat(AuthResponse.bearer("header.payload.signature", 3600, null).toString())
                .doesNotContain("header.payload.signature").contains("***");
    }
}
