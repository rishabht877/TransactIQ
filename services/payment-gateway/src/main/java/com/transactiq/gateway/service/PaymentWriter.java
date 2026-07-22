package com.transactiq.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.gateway.api.PaymentRequest;
import com.transactiq.gateway.api.PaymentResponse;
import com.transactiq.gateway.domain.Payment;
import com.transactiq.gateway.domain.PaymentRepository;
import com.transactiq.gateway.domain.PaymentStatus;
import com.transactiq.gateway.event.PaymentRequestedEvent;
import com.transactiq.gateway.outbox.OutboxEvent;
import com.transactiq.gateway.outbox.OutboxRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the business row and the outbox row in ONE transaction — the transactional outbox.
 *
 * <p>This is the atomic unit that fixes the dual-write problem: {@code payments} and
 * {@code outbox} commit together or not at all. There is no Kafka call here; publishing is the
 * relay's job, decoupled from the request path.
 *
 * <p>On a concurrent duplicate Idempotency-Key, the UNIQUE constraint makes {@code saveAndFlush}
 * throw {@code DataIntegrityViolationException}. We let it propagate so THIS transaction rolls
 * back cleanly; the caller ({@link PaymentService}) re-reads the winning row in a fresh
 * transaction. (Catching it here would not work — the transaction is already rollback-only.)
 */
@Service
public class PaymentWriter {

    public static final String TOPIC_PAYMENTS_REQUESTED = "payments.requested";
    public static final String EVENT_TYPE = "PaymentRequested";
    public static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentWriter(PaymentRepository paymentRepository,
                         OutboxRepository outboxRepository,
                         ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse insertNewPayment(String idempotencyKey, PaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(
                paymentId,
                idempotencyKey,
                request.amount(),
                request.currency(),
                request.customerId(),
                request.cardLast4(),
                request.country(),
                request.merchant(),
                PaymentStatus.RECEIVED);
        // Flush now so a duplicate idempotency_key surfaces as a constraint violation here,
        // before we write the outbox row.
        paymentRepository.saveAndFlush(payment);

        String eventId = UUID.randomUUID().toString();
        PaymentRequestedEvent event = new PaymentRequestedEvent(
                eventId,
                paymentId,
                idempotencyKey,
                request.amount(),
                request.currency(),
                request.customerId(),
                request.cardLast4(),
                request.country(),
                request.merchant());
        outboxRepository.save(new OutboxEvent(
                AGGREGATE_TYPE,
                paymentId,
                eventId,
                EVENT_TYPE,
                TOPIC_PAYMENTS_REQUESTED,
                paymentId,
                serialize(event)));

        return new PaymentResponse(paymentId, PaymentStatus.RECEIVED);
    }

    private String serialize(PaymentRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PaymentRequestedEvent", e);
        }
    }
}
