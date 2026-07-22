package com.transactiq.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-gateway service entry point.
 *
 * <p>@EnableScheduling drives the transactional-outbox relay ({@code OutboxRelay}).
 */
@SpringBootApplication
@EnableScheduling
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }
}
