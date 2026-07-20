package com.transactiq.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-processor service entry point.
 *
 * <p>Phase 0: bootable skeleton only. The idempotent Kafka consumer (processed_events dedup),
 * fraud-service call, and retry/DLQ wiring are added in Phases 1–3.
 */
@SpringBootApplication
public class PaymentProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentProcessorApplication.class, args);
    }
}
