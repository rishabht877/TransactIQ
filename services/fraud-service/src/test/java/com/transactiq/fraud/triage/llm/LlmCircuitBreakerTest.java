package com.transactiq.fraud.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.transactiq.fraud.triage.llm.LlmCircuitBreaker.Permit;
import com.transactiq.fraud.triage.llm.LlmCircuitBreaker.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The breaker's state machine on its own: no model, no sleeping, time moved by hand. */
class LlmCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private MutableClock clock;
    private SimpleMeterRegistry registry;
    private LlmCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        registry = new SimpleMeterRegistry();
        breaker = new LlmCircuitBreaker(THRESHOLD, COOLDOWN, clock, registry);
    }

    private Permit permit() {
        return breaker.tryAcquire().orElseThrow(() -> new AssertionError("expected a call to be allowed"));
    }

    private void fail() {
        permit().recordFailure();
    }

    private void succeed() {
        permit().recordSuccess();
    }

    private void tripIt() {
        for (int i = 0; i < THRESHOLD; i++) {
            fail();
        }
    }

    private double gauge() {
        return registry.get("transactiq.llm.circuit.state").gauge().value();
    }

    private double skipped() {
        return registry.get("transactiq.llm.circuit.skipped").counter().count();
    }

    // ------------------------------------------------------------ closed -> open

    @Test
    void staysClosedBelowTheThreshold() {
        fail();
        fail();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void opensAfterNConsecutiveFailures() {
        tripIt();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aSuccessResetsTheCountSoOnlyConsecutiveFailuresTrip() {
        fail();
        fail();
        succeed();
        fail();
        fail();
        assertThat(breaker.state()).as("2 + 2 failures with a success between is not 3 in a row").isEqualTo(State.CLOSED);

        fail();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    // ------------------------------------------------------------ open: short-circuiting

    @Test
    void refusesEveryCallWhileOpenAndCountsThem() {
        tripIt();

        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquire()).isEmpty();
        }
        assertThat(skipped()).isEqualTo(5.0);
    }

    @Test
    void staysOpenUntilTheCooldownHasPassed() {
        tripIt();

        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(breaker.tryAcquire()).as("1s before the cooldown ends").isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).as("cooldown has elapsed").isPresent();
    }

    // ------------------------------------------------------------ half open

    @Test
    void afterTheCooldownExactlyOneTrialCallIsAllowed() {
        tripIt();
        clock.advance(COOLDOWN);

        Permit trial = permit();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("a second caller while the trial runs").isEmpty();
        assertThat(skipped()).isEqualTo(1.0);

        trial.recordSuccess();
    }

    @Test
    void aSuccessfulTrialClosesTheBreaker() {
        tripIt();
        clock.advance(COOLDOWN);

        permit().recordSuccess();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isPresent();
        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void aClosedBreakerStartsCountingFromZeroAgain() {
        tripIt();
        clock.advance(COOLDOWN);
        permit().recordSuccess();

        fail();
        fail();

        assertThat(breaker.state()).as("2 fresh failures, not the old 3").isEqualTo(State.CLOSED);
    }

    @Test
    void aFailedTrialReopensForAFullNewCooldown() {
        tripIt();
        clock.advance(COOLDOWN);
        Permit trial = permit();
        clock.advance(Duration.ofSeconds(10));

        trial.recordFailure();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(breaker.tryAcquire())
                .as("29s after the REOPEN, though 69s after the first open")
                .isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isPresent();
    }

    // ------------------------------------------------------------ stale and repeated reports

    @Test
    void aSlowCallFromBeforeTheBreakerOpenedCannotCloseIt() {
        Permit slow = permit();
        tripIt();
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        slow.recordSuccess();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isEmpty();
    }

    @Test
    void aSlowCallFromBeforeTheBreakerOpenedCannotSpoilTheTrial() {
        Permit slow = permit();
        tripIt();
        clock.advance(COOLDOWN);
        Permit trial = permit();

        slow.recordFailure();
        assertThat(breaker.state()).as("a stale failure is not the trial's result").isEqualTo(State.HALF_OPEN);

        trial.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aSlowSuccessFromBeforeTheBreakerOpenedCannotCloseItWhileTheTrialIsRunning() {
        Permit slow = permit();
        tripIt();
        clock.advance(COOLDOWN);
        Permit trial = permit();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);

        slow.recordSuccess();

        assertThat(breaker.state()).as("only the trial decides, not a call from before it opened")
                .isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("the trial is still in flight").isEmpty();

        trial.recordFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aPermitOnlyReportsOnce() {
        Permit p = permit();

        p.recordFailure();
        p.recordFailure();
        p.recordFailure();
        assertThat(breaker.state()).as("one call, one failure").isEqualTo(State.CLOSED);

        Permit q = permit();
        q.recordSuccess();   // resets the count to 0
        q.recordFailure();   // already reported, so this must NOT count
        fail();
        fail();
        assertThat(breaker.state())
                .as("2 real failures; had q's late failure counted this would be 3 and OPEN")
                .isEqualTo(State.CLOSED);
    }

    // ------------------------------------------------------------ off switch and metrics

    @Test
    void aThresholdOfZeroDisablesTheBreaker() {
        LlmCircuitBreaker off = new LlmCircuitBreaker(0, COOLDOWN, clock, new SimpleMeterRegistry());

        for (int i = 0; i < 20; i++) {
            off.tryAcquire().orElseThrow().recordFailure();
        }

        assertThat(off.state()).isEqualTo(State.CLOSED);
        assertThat(off.tryAcquire()).isPresent();
    }

    @Test
    void theGaugeFollowsTheState() {
        assertThat(gauge()).as("closed").isEqualTo(0.0);

        tripIt();
        assertThat(gauge()).as("open").isEqualTo(1.0);

        clock.advance(COOLDOWN);
        Permit trial = permit();
        assertThat(gauge()).as("half open").isEqualTo(2.0);

        trial.recordSuccess();
        assertThat(gauge()).as("closed again").isEqualTo(0.0);
    }
}
