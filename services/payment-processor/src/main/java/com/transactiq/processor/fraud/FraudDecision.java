package com.transactiq.processor.fraud;

import java.util.List;

/**
 * Structured fraud triage result. This is the exact shape the Phase 3 LangChain4j AiService
 * will produce; in Phases 1–2 a stub returns a fixed APPROVE so the processing path is complete
 * before the LLM is wired in.
 */
public record FraudDecision(
        Decision decision,
        double riskScore,
        List<String> reasons) {
}
