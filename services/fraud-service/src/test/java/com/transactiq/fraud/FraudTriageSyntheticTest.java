package com.transactiq.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.CustomerActivityStore;
import com.transactiq.fraud.rules.FraudRulesEngine;
import com.transactiq.fraud.triage.FraudTriageService;
import com.transactiq.fraud.triage.RuleBasedReasoner;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Synthetic fraud suite: injected transaction patterns with asserted expected decisions. Runs
 * against the DETERMINISTIC path (rules engine + RuleBasedReasoner, no LLM) so outcomes are
 * exact and repeatable — this is what makes fraud behavior testable despite the LLM layer.
 *
 * <p>A steppable Clock drives the velocity/geo windows.
 */
class FraudTriageSyntheticTest {

    /** Test clock we can advance manually to simulate time between charges. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override public Instant instant() {
            return now.get();
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private MutableClock clock;
    private FraudTriageService triage;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        triage = new FraudTriageService(
                new CustomerActivityStore(clock), new FraudRulesEngine(), new RuleBasedReasoner());
    }

    private static FraudEvaluationRequest tx(String customer, String amount, String country) {
        return new FraudEvaluationRequest(
                "pay-" + customer + "-" + amount, new BigDecimal(amount), "USD", customer, "4242",
                country, "Acme");
    }

    @Test
    void cleanTransactionIsApproved() {
        FraudDecision d = triage.evaluate(tx("clean-cust", "49.99", "US"));
        assertThat(d.decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void cardTestingVelocityIsBlocked() {
        FraudDecision last = null;
        for (int i = 0; i < 6; i++) {
            last = triage.evaluate(tx("velocity-cust", "3.00", "US"));
            clock.advance(Duration.ofSeconds(5)); // 6 charges within ~30s
        }
        assertThat(last.decision()).isEqualTo(Decision.BLOCK);
        assertThat(last.reasons()).anyMatch(r -> r.toLowerCase().contains("velocity"));
    }

    @Test
    void impossibleTravelIsBlocked() {
        triage.evaluate(tx("geo-cust", "40.00", "US"));
        clock.advance(Duration.ofSeconds(10));
        FraudDecision d = triage.evaluate(tx("geo-cust", "40.00", "GB")); // 2 countries in 60s
        assertThat(d.decision()).isEqualTo(Decision.BLOCK);
        assertThat(d.reasons()).anyMatch(r -> r.toLowerCase().contains("geo"));
    }

    @Test
    void slowerCountryChangeIsEscalated() {
        triage.evaluate(tx("geo2-cust", "40.00", "US"));
        clock.advance(Duration.ofSeconds(120)); // outside the 60s velocity window
        FraudDecision d = triage.evaluate(tx("geo2-cust", "40.00", "GB"));
        assertThat(d.decision()).isEqualTo(Decision.ESCALATE);
    }

    @Test
    void largeAmountIsEscalated() {
        FraudDecision d = triage.evaluate(tx("big-cust", "6000.00", "US"));
        assertThat(d.decision()).isEqualTo(Decision.ESCALATE);
        assertThat(d.reasons()).anyMatch(r -> r.toLowerCase().contains("amount"));
    }

    @Test
    void extremeAmountIsBlocked() {
        FraudDecision d = triage.evaluate(tx("huge-cust", "25000.00", "US"));
        assertThat(d.decision()).isEqualTo(Decision.BLOCK);
    }
}
