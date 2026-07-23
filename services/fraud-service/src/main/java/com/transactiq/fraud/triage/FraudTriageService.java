package com.transactiq.fraud.triage;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.CustomerActivityStore;
import com.transactiq.fraud.rules.CustomerActivityStore.ActivitySnapshot;
import com.transactiq.fraud.rules.FraudRulesEngine;
import com.transactiq.fraud.rules.RuleFindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates fraud triage: record the transaction in the activity window, run the deterministic
 * rules, then let the reasoner (LLM or rule-based) produce the final structured decision.
 */
@Service
public class FraudTriageService {

    private static final Logger log = LoggerFactory.getLogger(FraudTriageService.class);

    private final CustomerActivityStore activityStore;
    private final FraudRulesEngine rulesEngine;
    private final FraudReasoner reasoner;

    public FraudTriageService(CustomerActivityStore activityStore,
                              FraudRulesEngine rulesEngine,
                              FraudReasoner reasoner) {
        this.activityStore = activityStore;
        this.rulesEngine = rulesEngine;
        this.reasoner = reasoner;
    }

    public FraudDecision evaluate(FraudEvaluationRequest request) {
        ActivitySnapshot snapshot = activityStore.recordAndSnapshot(
                request.customerId(), request.amount(), request.country());
        RuleFindings findings = rulesEngine.evaluate(request, snapshot);
        FraudDecision decision = reasoner.reason(request, findings);
        log.info("Fraud triage payment={} -> {} (risk {})",
                request.paymentId(), decision.decision(), decision.riskScore());
        return decision;
    }
}
