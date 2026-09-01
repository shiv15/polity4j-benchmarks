package io.polity4j.benchmarks.harness;

import java.util.Objects;

/**
 * Immutable record representing a single attempt at the backend boundary.
 */
public record AttemptRecord(
        int attemptNumber,
        long timestampMs,
        FaultType faultType,
        boolean isTerminal
) {
    public AttemptRecord {
        Objects.requireNonNull(faultType, "faultType must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }
}
