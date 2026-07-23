package com.transactiq.fraud.rules;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.rules.CustomerActivityStore.ActivitySnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic fraud pre-checks — the authoritative backbone of triage. Fully unit-testable
 * (no LLM, no randomness). Produces a recommended severity floor + signals that are then handed
 * to the LLM for the final call and reasoning.
 *
 * <p>Thresholds are intentionally simple and legible for a demo; a real system would tune them
 * from data / use an ML model. The point of the project is the architecture around the decision.
 */
@Component
public class FraudRulesEngine {

    // Velocity / card-testing: many charges for one customer in the 60s window.
    private static final int VELOCITY_ESCALATE = 4;
    private static final int VELOCITY_BLOCK = 6;

    // Amount anomaly.
    private static final BigDecimal LARGE_AMOUNT_ESCALATE = new BigDecimal("5000");
    private static final BigDecimal LARGE_AMOUNT_BLOCK = new BigDecimal("20000");
    private static final BigDecimal ANOMALY_MIN_AMOUNT = new BigDecimal("500");
    private static final BigDecimal ANOMALY_FACTOR = new BigDecimal("10");

    public RuleFindings evaluate(FraudEvaluationRequest request, ActivitySnapshot snapshot) {
        List<String> signals = new ArrayList<>();
        Decision recommended = Decision.APPROVE;
        double risk = 0.0;

        // --- Velocity / card-testing ---
        if (snapshot.countInWindow() >= VELOCITY_BLOCK) {
            signals.add("velocity: " + snapshot.countInWindow()
                    + " charges in 60s for this customer (card-testing pattern)");
            recommended = maxSeverity(recommended, Decision.BLOCK);
            risk = Math.max(risk, 0.95);
        } else if (snapshot.countInWindow() >= VELOCITY_ESCALATE) {
            signals.add("velocity: " + snapshot.countInWindow() + " charges in 60s (elevated)");
            recommended = maxSeverity(recommended, Decision.ESCALATE);
            risk = Math.max(risk, 0.6);
        }

        // --- Geo mismatch / impossible travel ---
        if (snapshot.distinctCountriesInWindow() >= 2) {
            signals.add("geo-velocity: charges from " + snapshot.distinctCountriesInWindow()
                    + " countries within 60s (impossible travel)");
            recommended = maxSeverity(recommended, Decision.BLOCK);
            risk = Math.max(risk, 0.9);
        } else if (snapshot.previousCountry() != null && request.country() != null
                && !snapshot.previousCountry().equalsIgnoreCase(request.country())) {
            signals.add("geo-mismatch: country changed from " + snapshot.previousCountry()
                    + " to " + request.country());
            recommended = maxSeverity(recommended, Decision.ESCALATE);
            risk = Math.max(risk, 0.55);
        }

        // --- Amount anomaly ---
        BigDecimal amount = request.amount();
        if (amount.compareTo(LARGE_AMOUNT_BLOCK) >= 0) {
            signals.add("amount: " + amount + " " + request.currency() + " exceeds hard limit");
            recommended = maxSeverity(recommended, Decision.BLOCK);
            risk = Math.max(risk, 0.9);
        } else if (amount.compareTo(LARGE_AMOUNT_ESCALATE) >= 0) {
            signals.add("amount: " + amount + " " + request.currency() + " is unusually large");
            recommended = maxSeverity(recommended, Decision.ESCALATE);
            risk = Math.max(risk, 0.5);
        } else if (snapshot.averagePriorAmount() != null
                && amount.compareTo(ANOMALY_MIN_AMOUNT) >= 0
                && amount.compareTo(snapshot.averagePriorAmount().multiply(ANOMALY_FACTOR)) > 0) {
            signals.add("amount: " + amount + " is >" + ANOMALY_FACTOR + "x the customer's average ("
                    + snapshot.averagePriorAmount() + ")");
            recommended = maxSeverity(recommended, Decision.ESCALATE);
            risk = Math.max(risk, 0.5);
        }

        if (signals.isEmpty()) {
            signals.add("no rule signals: within normal velocity, geo, and amount bounds");
        }
        return new RuleFindings(recommended, risk, signals);
    }

    private static Decision maxSeverity(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
