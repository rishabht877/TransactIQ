package com.transactiq.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-gateway service entry point.
 *
 * <p>Phase 0: bootable skeleton only. The REST payment API, Redis idempotency-key dedup, and
 * transactional outbox are added in Phases 1–2.
 */
@SpringBootApplication
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }
}
