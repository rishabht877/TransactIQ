package com.transactiq.fraud.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FraudServiceConfig {

    /** System clock in prod; tests can supply a fixed/steppable Clock to drive velocity windows. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
