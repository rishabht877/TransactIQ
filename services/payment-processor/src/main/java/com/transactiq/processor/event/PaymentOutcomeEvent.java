package com.transactiq.processor.event;

import com.transactiq.processor.fraud.Decision;
import java.util.List;

/**
 * Terminal outcome event published (via the outbox) to {@code payments.processed} or
 * {@code payments.blocked}. Carries the fraud triage detail so downstream consumers (the Phase 5
 * dashboard) can show the decision and human-readable reasoning.
 */
public record PaymentOutcomeEvent(
        String eventId,
        String paymentId,
        String status,        // PROCESSED | BLOCKED
        Decision decision,    // APPROVE | ESCALATE | BLOCK
        double riskScore,
        List<String> reasons) {
}
