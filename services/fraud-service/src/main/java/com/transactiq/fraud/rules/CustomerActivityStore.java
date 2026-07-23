package com.transactiq.fraud.rules;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window of recent activity per customer, powering the velocity / geo /
 * amount-anomaly rules. Stateful by design so those rules are real (not passed-in), and small
 * enough to keep in memory for the project. A {@link Clock} is injected so tests can drive time
 * deterministically.
 *
 * <p>Trade-off: this state is per-instance and non-durable — fine for a single fraud-service, but
 * in a scaled deployment the window would move to Redis/a feature store. Called out for interviews.
 */
@Component
public class CustomerActivityStore {

    /** Sliding window for velocity/geo evaluation. */
    public static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int MAX_RETAINED_PER_CUSTOMER = 50;

    private record Activity(Instant at, BigDecimal amount, String country) {
    }

    /** Snapshot of the stats a rule needs, computed at record time (includes the current txn). */
    public record ActivitySnapshot(
            int countInWindow,
            int distinctCountriesInWindow,
            String previousCountry,        // most recent prior txn's country (null if first)
            BigDecimal averagePriorAmount) { // mean of prior amounts (null if first)
    }

    private final Clock clock;
    private final Map<String, Deque<Activity>> byCustomer = new ConcurrentHashMap<>();

    public CustomerActivityStore(Clock clock) {
        this.clock = clock;
    }

    /** Record the current transaction and return the stats snapshot used by the rules. */
    public ActivitySnapshot recordAndSnapshot(String customerId, BigDecimal amount, String country) {
        Instant now = clock.instant();
        Deque<Activity> history = byCustomer.computeIfAbsent(customerId, k -> new ArrayDeque<>());
        synchronized (history) {
            String previousCountry = history.isEmpty() ? null : history.peekLast().country();
            BigDecimal averagePrior = averageAmount(history);

            history.addLast(new Activity(now, amount, country));
            trim(history);

            Instant windowStart = now.minus(WINDOW);
            int count = 0;
            long distinctCountries = history.stream()
                    .filter(a -> !a.at().isBefore(windowStart))
                    .map(Activity::country)
                    .filter(c -> c != null)
                    .distinct()
                    .count();
            for (Activity a : history) {
                if (!a.at().isBefore(windowStart)) {
                    count++;
                }
            }
            return new ActivitySnapshot(count, (int) distinctCountries, previousCountry, averagePrior);
        }
    }

    private BigDecimal averageAmount(Deque<Activity> history) {
        if (history.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Activity a : history) {
            sum = sum.add(a.amount());
        }
        return sum.divide(BigDecimal.valueOf(history.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    private void trim(Deque<Activity> history) {
        while (history.size() > MAX_RETAINED_PER_CUSTOMER) {
            history.removeFirst();
        }
        Instant cutoff = clock.instant().minus(WINDOW.multipliedBy(10));
        Iterator<Activity> it = history.iterator();
        while (it.hasNext()) {
            if (it.next().at().isBefore(cutoff)) {
                it.remove();
            } else {
                break; // ordered oldest-first
            }
        }
    }
}
