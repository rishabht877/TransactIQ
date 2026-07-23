package com.transactiq.fraud.rules;

import com.transactiq.fraud.domain.Decision;
import java.util.List;

/**
 * Output of the deterministic rules engine: a recommended severity floor, a rule-based risk
 * score, and the human-readable signals that fired. The LLM triage layer receives this as
 * context and may escalate further, but never below {@link #recommended()}.
 */
public record RuleFindings(
        Decision recommended,
        double ruleRiskScore,
        List<String> signals) {
}
