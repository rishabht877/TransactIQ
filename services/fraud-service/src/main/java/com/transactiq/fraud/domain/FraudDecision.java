package com.transactiq.fraud.domain;

import java.util.List;

/**
 * The structured fraud decision — the exact shape the build spec pins down. Returned by the
 * REST API and produced by the LLM triage layer.
 */
public record FraudDecision(
        Decision decision,        // APPROVE, ESCALATE, BLOCK
        double riskScore,         // 0.0–1.0
        List<String> reasons) {   // human-readable
}
