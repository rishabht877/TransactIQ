package com.transactiq.fraud.triage.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Circuit breaker in front of the LLM call.
 *
 * <p>CLOSED: calls go through; consecutive failures are counted and any success resets the count.
 * After {@code failureThreshold} in a row the breaker OPENs. OPEN: calls are refused immediately so
 * the caller can use the rules' decision without waiting on a model that is down. After the
 * cooldown the next caller becomes the single trial (HALF_OPEN): success closes the breaker, a
 * failure opens it again for another full cooldown. While the trial is in flight everyone else is
 * still refused.
 *
 * <p>It only protects calls made AFTER it opens. The calls that tripped it each still waited out
 * the full model timeout, and so does the trial call.
 *
 * <p>A {@code failureThreshold} of 0 or less disables it (every call is allowed). State changes
 * are guarded by one lock, and each permit is tied to the state it was issued in, so a slow call
 * that started before the breaker opened cannot close, reopen or extend it when it finally
 * finishes.
 */
public class LlmCircuitBreaker {

    public enum State {
        CLOSED(0), OPEN(1), HALF_OPEN(2);

        private final int gaugeValue;

        State(int gaugeValue) {
            this.gaugeValue = gaugeValue;
        }
    }

    /** Handed out for one allowed call. Report the outcome once; later reports are ignored. */
    public final class Permit {
        private final long generation;
        private final AtomicBoolean reported = new AtomicBoolean();

        private Permit(long generation) {
            this.generation = generation;
        }

        public void recordSuccess() {
            if (reported.compareAndSet(false, true)) {
                onSuccess(generation);
            }
        }

        public void recordFailure() {
            if (reported.compareAndSet(false, true)) {
                onFailure(generation);
            }
        }
    }

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private final Counter skipped;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;
    /** Bumped on every state change, so a permit from an earlier state can be recognised as stale. */
    private long generation;

    public LlmCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock, MeterRegistry registry) {
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
        Gauge.builder("transactiq.llm.circuit.state", this, b -> b.state().gaugeValue)
                .description("LLM circuit breaker state: 0 closed, 1 open, 2 half-open (trial call in flight)")
                .register(registry);
        this.skipped = Counter.builder("transactiq.llm.circuit.skipped")
                .description("LLM calls skipped because the circuit breaker was open or a trial was in flight")
                .register(registry);
    }

    /** A breaker that never opens, for callers that do not want one. */
    public static LlmCircuitBreaker disabled(MeterRegistry registry) {
        return new LlmCircuitBreaker(0, Duration.ZERO, Clock.systemUTC(), registry);
    }

    /** Empty means the call is refused and the caller should not touch the model. */
    public synchronized Optional<Permit> tryAcquire() {
        if (failureThreshold <= 0) {
            return Optional.of(new Permit(generation));
        }
        switch (state) {
            case CLOSED:
                return Optional.of(new Permit(generation));
            case OPEN:
                if (clock.instant().isBefore(openedAt.plus(cooldown))) {
                    skipped.increment();
                    return Optional.empty();
                }
                transitionTo(State.HALF_OPEN);
                trialInFlight = true;
                return Optional.of(new Permit(generation));
            default: // HALF_OPEN
                if (trialInFlight) {
                    skipped.increment();
                    return Optional.empty();
                }
                trialInFlight = true;
                return Optional.of(new Permit(generation));
        }
    }

    /** Stored state. A breaker whose cooldown has passed still reads OPEN until the next call arrives. */
    public synchronized State state() {
        return state;
    }

    private synchronized void onSuccess(long permitGeneration) {
        if (permitGeneration != generation || failureThreshold <= 0) {
            return;
        }
        if (state == State.HALF_OPEN) {
            transitionTo(State.CLOSED);
        } else if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    private synchronized void onFailure(long permitGeneration) {
        if (permitGeneration != generation || failureThreshold <= 0) {
            return;
        }
        if (state == State.HALF_OPEN) {
            transitionTo(State.OPEN);
        } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
            transitionTo(State.OPEN);
        }
    }

    private void transitionTo(State next) {
        state = next;
        generation++;
        trialInFlight = false;
        consecutiveFailures = 0;
        if (next == State.OPEN) {
            openedAt = clock.instant();
        }
    }
}
