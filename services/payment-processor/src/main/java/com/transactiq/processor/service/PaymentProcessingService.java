package com.transactiq.processor.service;

import com.transactiq.processor.domain.Payment;
import com.transactiq.processor.domain.PaymentRepository;
import com.transactiq.processor.domain.PaymentStatus;
import com.transactiq.processor.event.PaymentRequestedEvent;
import com.transactiq.processor.fraud.Decision;
import com.transactiq.processor.fraud.FraudDecision;
import com.transactiq.processor.fraud.FraudStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1 processing: look up the payment, run fraud triage (stubbed APPROVE), write the
 * terminal status.
 *
 * <p><b>No idempotency yet.</b> If this event is re-delivered (Kafka is at-least-once), Phase 1
 * would process it again. Phase 2 makes this an idempotent consumer: it claims the event in a
 * {@code processed_events(event_id PK)} row in the SAME transaction as the status write, so a
 * re-delivered event is detected and skipped — exactly one business effect.
 */
@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final PaymentRepository paymentRepository;
    private final FraudStub fraudStub;

    public PaymentProcessingService(PaymentRepository paymentRepository, FraudStub fraudStub) {
        this.paymentRepository = paymentRepository;
        this.fraudStub = fraudStub;
    }

    @Transactional
    public void process(PaymentRequestedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for id=" + event.paymentId()));

        FraudDecision decision = fraudStub.decide(event);
        PaymentStatus terminal =
                decision.decision() == Decision.APPROVE ? PaymentStatus.PROCESSED : PaymentStatus.BLOCKED;
        payment.setStatus(terminal);

        log.info("Processed payment {} -> {} (event {}, risk {})",
                event.paymentId(), terminal, event.eventId(), decision.riskScore());
    }
}
