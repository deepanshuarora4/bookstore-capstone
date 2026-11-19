package com.capstone.bookstore.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.capstone.bookstore.user.Role;

/**
 * The principal placed in the SecurityContext for a valid JWT. It is rebuilt from the token claims on
 * every request (no session, no database lookup) and never contains credentials.
 */
public record AuthenticatedUser(Long id, String email, Role role) {

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
