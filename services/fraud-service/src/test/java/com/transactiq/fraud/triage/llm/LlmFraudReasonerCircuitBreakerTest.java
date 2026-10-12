package com.transactiq.fraud.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;
import com.transactiq.fraud.triage.RuleBasedReasoner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The breaker as the reasoner uses it: what a caller sees (the rules' decision and the markers on
 * it) and whether the model is called at all. The model is a fake that counts its calls.
 */
class LlmFraudReasonerCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private static final FraudEvaluationRequest REQUEST = new FraudEvaluationRequest(
            "p-1", new BigDecimal("30000"), "USD", "cust-1", "4242", "US", "Acme");
    private static final RuleFindings RULE_BLOCK =
            new RuleFindings(Decision.BLOCK, 0.9, List.of("amount exceeds hard limit"));

    /** A model that counts calls and behaves however the test says. */
    private static final class FakeModel implements FraudAssistant {
        final AtomicInteger calls = new AtomicInteger();
        Supplier<LlmVerdict> behaviour;

        @Override
        public LlmVerdict triage(String prompt) {
            calls.incrementAndGet();
            return behaviour.get();
        }

        void isDown() {
            behaviour = () -> {
                throw new RuntimeException("Failed to connect to localhost:11434");
            };
        }

        void returnsGarbage() {
            behaviour = () -> null;
        }

        void works() {
            behaviour = () -> {
                LlmVerdict v = new LlmVerdict();
                v.decision = Decision.APPROVE;
                v.riskScore = 0.0;
                v.reasons = List.of("model reason");
                return v;
            };
        }
    }

    private MutableClock clock;
    private LlmCircuitBreaker breaker;
    private FakeModel model;
    private LlmFraudReasoner reasoner;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        breaker = new LlmCircuitBreaker(THRESHOLD, COOLDOWN, clock, new SimpleMeterRegistry());
        model = new FakeModel();
        reasoner = new LlmFraudReasoner(model, new RuleBasedReasoner(), breaker);
    }

    private FraudDecision call() {
        return reasoner.reason(REQUEST, RULE_BLOCK);
    }

    private void tripIt() {
        model.isDown();
        for (int i = 0; i < THRESHOLD; i++) {
            call();
        }
    }

    private static boolean usedRulesFallback(FraudDecision d) {
        return d.reasons().stream().anyMatch(r -> r.contains("LLM triage not applied"));
    }

    private static boolean saysCircuitWasOpen(FraudDecision d) {
        return d.reasons().stream().anyMatch(r -> r.contains("circuit breaker open"));
    }

    // ------------------------------------------------------------ closed -> open

    @Test
    void afterNFailuresTheModelIsNotCalledAndTheRulesDecide() {
        model.isDown();

        for (int i = 0; i < THRESHOLD; i++) {
            FraudDecision d = call();
            assertThat(usedRulesFallback(d)).isTrue();
            assertThat(saysCircuitWasOpen(d)).as("the calls that tripped it did reach the model").isFalse();
        }
        assertThat(model.calls).hasValue(THRESHOLD);

        FraudDecision skipped = call();

        assertThat(model.calls).as("the model is not touched while open").hasValue(THRESHOLD);
        assertThat(skipped.decision()).isEqualTo(Decision.BLOCK);
        assertThat(skipped.riskScore()).isEqualTo(0.9);
        assertThat(usedRulesFallback(skipped)).as("marked like the existing fallback").isTrue();
        assertThat(saysCircuitWasOpen(skipped)).as("and says why").isTrue();
    }

    @Test
    void unusableAnswersCountAsFailuresToo() {
        model.returnsGarbage();

        for (int i = 0; i < THRESHOLD; i++) {
            call();
        }
        call();

        assertThat(model.calls).hasValue(THRESHOLD);
    }

    @Test
    void aWorkingCallInBetweenResetsTheCount() {
        model.isDown();
        call();
        call();
        model.works();
        call();
        model.isDown();
        call();
        call();

        call();   // would be skipped if the earlier failures had added up to 3 in total

        assertThat(model.calls).as("all six went to the model").hasValue(6);
    }

    // ------------------------------------------------------------ recovery

    @Test
    void aSuccessfulTrialAfterTheCooldownClosesItAndTheModelIsUsedAgain() {
        tripIt();
        clock.advance(COOLDOWN);
        model.works();

        FraudDecision trial = call();
        assertThat(model.calls).as("3 failures + the trial").hasValue(THRESHOLD + 1);
        assertThat(usedRulesFallback(trial)).as("the trial used the real model answer").isFalse();

        call();
        call();
        assertThat(model.calls).as("closed again, so these reach the model").hasValue(THRESHOLD + 3);
    }

    @Test
    void aFailedTrialOpensItAgainAndCallsAreSkippedUntilTheNextCooldown() {
        tripIt();
        clock.advance(COOLDOWN);

        call();   // trial, the model is still down
        assertThat(model.calls).hasValue(THRESHOLD + 1);

        FraudDecision skipped = call();
        assertThat(model.calls).as("skipped again").hasValue(THRESHOLD + 1);
        assertThat(saysCircuitWasOpen(skipped)).isTrue();

        clock.advance(COOLDOWN);
        call();
        assertThat(model.calls).as("next trial after a fresh cooldown").hasValue(THRESHOLD + 2);
    }

    @Test
    void theGuardsStillApplyToTheTrialCall() {
        tripIt();
        clock.advance(COOLDOWN);
        model.works();   // says APPROVE at risk 0.0

        FraudDecision trial = call();

        assertThat(trial.decision()).as("the severity floor still holds the rule BLOCK").isEqualTo(Decision.BLOCK);
    }

    // ------------------------------------------------------------ a call that blows up outright

    @Test
    void anErrorThatEscapesStillCountsAsAFailure() {
        model.behaviour = () -> {
            throw new AssertionError("not an Exception, so the reasoner's catch does not see it");
        };

        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(this::call).isInstanceOf(AssertionError.class);
        }

        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);
    }

    @Test
    void anErrorDuringTheTrialDoesNotLeaveTheBreakerStuckHalfOpen() {
        tripIt();
        clock.advance(COOLDOWN);
        model.behaviour = () -> {
            throw new AssertionError("trial blows up");
        };

        assertThatThrownBy(this::call).isInstanceOf(AssertionError.class);

        assertThat(breaker.state()).as("reopened, not stuck waiting for a trial that is gone")
                .isEqualTo(LlmCircuitBreaker.State.OPEN);
        clock.advance(COOLDOWN);
        model.works();
        call();
        assertThat(breaker.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
    }
}
