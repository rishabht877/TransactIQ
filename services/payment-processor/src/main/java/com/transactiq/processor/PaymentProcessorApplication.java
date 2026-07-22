package com.transactiq.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-processor service entry point.
 *
 * <p>@EnableScheduling drives the output-event outbox relay ({@code OutboxRelay}).
 */
@SpringBootApplication
@EnableScheduling
public class PaymentProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentProcessorApplication.class, args);
    }
}
