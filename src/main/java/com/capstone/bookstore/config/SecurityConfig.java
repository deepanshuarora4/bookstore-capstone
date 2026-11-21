package com.capstone.bookstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.capstone.bookstore.security.JwtAuthenticationFilter;
import com.capstone.bookstore.security.JwtService;
import com.capstone.bookstore.security.RestAccessDeniedHandler;
import com.capstone.bookstore.security.RestAuthenticationEntryPoint;

/**
 * Stateless JWT security. Rules mirror the {@code security} declarations in openapi.yaml:
 * public catalogue and auth endpoints, ADMIN-only {@code /admin/**}, everything else needs a valid token.
 * Anonymous access to a protected route is a 401; a CUSTOMER on an ADMIN route is a 403.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)            // stateless token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .cors(Customizer.withDefaults())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // public operations (security: [] in openapi.yaml)
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/categories", "/products", "/products/*", "/products/*/related",
                                "/brands", "/brands/*/products").permitAll()
                        // role-restricted operations
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // everything else needs a valid JWT
                        .anyRequest().authenticated());
        return http.build();
    }
}
