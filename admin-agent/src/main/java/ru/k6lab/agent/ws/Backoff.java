package ru.k6lab.agent.ws;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Exponential backoff с jitter (+-20%): min, 2*min, 4*min ... max. */
public class Backoff {

    private final Duration min;
    private final Duration max;
    private final DoubleSupplier random;
    private int attempt;

    public Backoff(Duration min, Duration max) {
        this(min, max, Math::random);
    }

    public Backoff(Duration min, Duration max, DoubleSupplier random) {
        this.min = min;
        this.max = max;
        this.random = random;
    }

    public synchronized Duration next() {
        long base = min.toMillis() << Math.min(attempt, 20);
        attempt++;
        base = Math.min(base, max.toMillis());
        double jitter = 0.8 + 0.4 * random.getAsDouble();
        return Duration.ofMillis(Math.min(max.toMillis(), Math.max(1, (long) (base * jitter))));
    }

    public synchronized void reset() {
        attempt = 0;
    }
}
