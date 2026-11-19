package com.capstone.bookstore.auth;

import com.capstone.bookstore.user.UserResponse;

/** AuthResponse in openapi.yaml. */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresIn, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, user);
    }

    /** Never print the token, even by accident (logging, debugging). */
    @Override
    public String toString() {
        return "AuthResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", accessToken=***]";
    }
}
