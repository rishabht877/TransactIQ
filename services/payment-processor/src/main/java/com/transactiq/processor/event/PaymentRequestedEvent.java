package com.transactiq.processor.event;

import java.math.BigDecimal;

/**
 * Processor's own view of the PaymentRequested event consumed from {@code payments.requested}.
 *
 * <p>Deliberately a separate copy from the gateway's record (not a shared class): the JSON
 * payload is the contract, so each service owns its view and they stay decoupled. In a polyrepo
 * this contract would live in a schema registry (Avro/Protobuf) instead.
 */
public record PaymentRequestedEvent(
        String eventId,
        String paymentId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        String customerId,
        String cardLast4,
        String country,
        String merchant) {
}
