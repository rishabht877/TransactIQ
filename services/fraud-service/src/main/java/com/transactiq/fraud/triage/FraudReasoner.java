package com.transactiq.fraud.triage;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;

/**
 * Produces the FINAL {@link FraudDecision} from the transaction + deterministic rule findings.
 * Two implementations: a rule-based one (deterministic; used when the LLM is disabled and as the
 * LLM's fallback) and the LLM-backed one.
 */
public interface FraudReasoner {

    FraudDecision reason(FraudEvaluationRequest request, RuleFindings findings);
}
