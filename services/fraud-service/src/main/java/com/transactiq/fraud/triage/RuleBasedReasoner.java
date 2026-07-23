package com.transactiq.fraud.triage;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic reasoner: echoes the rule engine's recommendation and signals as the final
 * decision. Used when the LLM is disabled (tests/CI) and as the {@link LlmFraudReasoner}
 * fallback when the model is unreachable or returns something unusable.
 */
public class RuleBasedReasoner implements FraudReasoner {

    @Override
    public FraudDecision reason(FraudEvaluationRequest request, RuleFindings findings) {
        List<String> reasons = new ArrayList<>(findings.signals());
        reasons.add("decision by deterministic rules (LLM triage not applied)");
        return new FraudDecision(findings.recommended(), findings.ruleRiskScore(), reasons);
    }
}
