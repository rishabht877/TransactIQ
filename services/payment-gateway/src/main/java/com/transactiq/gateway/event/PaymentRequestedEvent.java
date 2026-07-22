package com.transactiq.gateway.event;

import java.math.BigDecimal;

/**
 * Event published to {@code payments.requested}. Serialized to JSON as the outbox payload.
 *
 * <p>{@code eventId} is the unique identity used by the processor's idempotent consumer
 * (processed_events PRIMARY KEY) — it is what makes re-delivery safe.
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
