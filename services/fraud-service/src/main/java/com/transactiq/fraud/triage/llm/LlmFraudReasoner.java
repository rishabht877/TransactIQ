package com.transactiq.fraud.triage.llm;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;
import com.transactiq.fraud.triage.FraudReasoner;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-backed reasoner. Feeds the transaction + rule findings to the model, then enforces safety
 * invariants on the model's answer:
 *
 * <ul>
 *   <li><b>Severity floor:</b> the final decision is never below the rules' recommendation — the
 *       LLM can escalate but can't override a rule-driven BLOCK/ESCALATE (defense against a model
 *       that hallucinates "looks fine").</li>
 *   <li><b>Fallback:</b> if the model is unreachable or returns something unusable, fall back to
 *       the deterministic rule decision — the pipeline never blocks on the LLM.</li>
 * </ul>
 */
public class LlmFraudReasoner implements FraudReasoner {

    private static final Logger log = LoggerFactory.getLogger(LlmFraudReasoner.class);

    private final FraudAssistant assistant;
    private final FraudReasoner fallback;

    public LlmFraudReasoner(FraudAssistant assistant, FraudReasoner fallback) {
        this.assistant = assistant;
        this.fallback = fallback;
    }

    @Override
    public FraudDecision reason(FraudEvaluationRequest request, RuleFindings findings) {
        try {
            LlmVerdict verdict = assistant.triage(buildPrompt(request, findings));
            if (verdict == null || verdict.decision == null) {
                log.warn("LLM returned no usable verdict; falling back to rules");
                return fallback.reason(request, findings);
            }
            double risk = clamp(Math.max(findings.ruleRiskScore(), verdict.riskScore));
            Decision decision = reconcile(findings.recommended(), verdict.decision, risk);

            List<String> reasons = new ArrayList<>();
            if (verdict.reasons != null) {
                reasons.addAll(verdict.reasons);
            }
            reasons.add("rule signals: " + String.join("; ", findings.signals()));
            return new FraudDecision(decision, risk, reasons);
        } catch (Exception e) {
            log.warn("LLM triage failed ({}); falling back to deterministic rules", e.toString());
            return fallback.reason(request, findings);
        }
    }

    private static String buildPrompt(FraudEvaluationRequest r, RuleFindings f) {
        return """
                Transaction:
                - amount: %s %s
                - customer: %s
                - country: %s
                - merchant: %s
                - card (last4): %s

                Deterministic rules engine:
                - recommended action (severity floor): %s
                - rule risk score: %.2f
                - signals: %s

                Decide the final action, riskScore, and reasons."""
                .formatted(
                        r.amount(), r.currency(), r.customerId(),
                        r.country() == null ? "unknown" : r.country(),
                        r.merchant() == null ? "unknown" : r.merchant(),
                        r.cardLast4() == null ? "unknown" : r.cardLast4(),
                        f.recommended(), f.ruleRiskScore(), String.join("; ", f.signals()));
    }

    /**
     * Reconcile the LLM's decision into something safe AND internally consistent:
     *
     * <ul>
     *   <li>never below the deterministic rule floor (LLM can't approve away a rule block);</li>
     *   <li>never MORE severe than the risk score justifies — this stops a weak local model from
     *       erratically BLOCKing a clean transaction with a near-zero risk score (observed with
     *       llama3.2). The LLM can still escalate, but only by also raising the risk score.</li>
     * </ul>
     */
    private static Decision reconcile(Decision ruleFloor, Decision llmDecision, double risk) {
        Decision riskImplied = risk >= 0.8 ? Decision.BLOCK
                : risk >= 0.4 ? Decision.ESCALATE
                : Decision.APPROVE;
        Decision ceiling = maxSeverity(ruleFloor, riskImplied);   // as severe as risk/rules allow
        Decision capped = minSeverity(llmDecision, ceiling);      // don't exceed that ceiling
        return maxSeverity(ruleFloor, capped);                    // but never below the rule floor
    }

    private static Decision maxSeverity(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static Decision minSeverity(Decision a, Decision b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
