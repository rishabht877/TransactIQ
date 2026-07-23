package com.transactiq.processor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.processor.domain.Payment;
import com.transactiq.processor.domain.PaymentRepository;
import com.transactiq.processor.domain.PaymentStatus;
import com.transactiq.processor.domain.ProcessedEventRepository;
import com.transactiq.processor.event.PaymentOutcomeEvent;
import com.transactiq.processor.event.PaymentRequestedEvent;
import com.transactiq.processor.fraud.Decision;
import com.transactiq.processor.fraud.FraudDecider;
import com.transactiq.processor.fraud.FraudDecision;
import com.transactiq.processor.outbox.OutboxEvent;
import com.transactiq.processor.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer. In ONE transaction it: (1) claims the event in {@code processed_events},
 * (2) writes the terminal payment status, and (3) writes the outcome event to the outbox. Either
 * all three commit or none do.
 *
 * <p><b>Idempotency:</b> the claim is the first step. If the event_id was already processed the
 * claim returns 0 and we skip — a re-delivered event (Kafka is at-least-once) produces no second
 * effect. If any later step throws, the whole transaction (including the claim) rolls back, so a
 * retry reprocesses cleanly. This is <i>effectively-once</i> processing over at-least-once
 * delivery — NOT exactly-once (which is unachievable across a DB and a broker).
 */
@Service
public class PaymentProcessingService {

    public static final String TOPIC_PROCESSED = "payments.processed";
    public static final String TOPIC_BLOCKED = "payments.blocked";
    private static final String AGGREGATE_TYPE = "payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final FraudDecider fraudDecider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentProcessingService(ProcessedEventRepository processedEventRepository,
                                    PaymentRepository paymentRepository,
                                    OutboxRepository outboxRepository,
                                    FraudDecider fraudDecider,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.fraudDecider = fraudDecider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /** Outcome of a processing attempt (returned so callers/tests can distinguish a skip). */
    public enum Outcome { PROCESSED, DUPLICATE_SKIPPED }

    @Transactional
    public Outcome process(PaymentRequestedEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doProcess(event);
        } finally {
            sample.stop(meterRegistry.timer("transactiq.processing"));
        }
    }

    private Outcome doProcess(PaymentRequestedEvent event) {
        // 1. Idempotent claim — first writer proceeds, re-deliveries short-circuit.
        if (processedEventRepository.claim(event.eventId()) == 0) {
            log.info("Duplicate event {} (payment {}) skipped — already processed",
                    event.eventId(), event.paymentId());
            meterRegistry.counter("transactiq.events.duplicate").increment();
            return Outcome.DUPLICATE_SKIPPED;
        }

        // 2. Business effect: fraud triage -> terminal status.
        Payment payment = paymentRepository.findById(event.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for id=" + event.paymentId()));

        FraudDecision decision = fraudDecider.decide(event);
        boolean approved = decision.decision() == Decision.APPROVE;
        PaymentStatus terminal = approved ? PaymentStatus.PROCESSED : PaymentStatus.BLOCKED;
        payment.setStatus(terminal);
        // Persist the triage result on the row for the dashboard.
        payment.setFraudDecision(decision.decision().name());
        payment.setRiskScore(java.math.BigDecimal.valueOf(decision.riskScore()));
        payment.setFraudReasons(serializeReasons(decision));
        meterRegistry.counter("transactiq.payments.processed", "status", terminal.name()).increment();

        // 3. Output event via the outbox (published by this service's relay).
        String topic = approved ? TOPIC_PROCESSED : TOPIC_BLOCKED;
        String eventType = approved ? "PaymentProcessed" : "PaymentBlocked";
        String outEventId = UUID.randomUUID().toString();
        PaymentOutcomeEvent outcome = new PaymentOutcomeEvent(
                outEventId,
                payment.getId(),
                terminal.name(),
                decision.decision(),
                decision.riskScore(),
                decision.reasons());
        outboxRepository.save(new OutboxEvent(
                AGGREGATE_TYPE, payment.getId(), outEventId, eventType, topic, payment.getId(),
                serialize(outcome)));

        log.info("Processed payment {} -> {} (event {})", payment.getId(), terminal, event.eventId());
        return Outcome.PROCESSED;
    }

    private String serialize(PaymentOutcomeEvent outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PaymentOutcomeEvent", e);
        }
    }

    private String serializeReasons(FraudDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision.reasons());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
