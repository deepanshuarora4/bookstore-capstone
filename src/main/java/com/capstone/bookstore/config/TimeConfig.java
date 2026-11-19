package com.capstone.bookstore.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Single time source (UTC) so token expiry and, later, the 48-hour cancellation rule are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
