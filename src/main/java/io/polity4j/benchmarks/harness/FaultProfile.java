package io.polity4j.benchmarks.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Defines a deterministic sequence of FaultType per attempt index.
 * Supports arbitrary-length sequences, builder construction, and sustained failure windows.
 */
public final class FaultProfile {

    private final List<FaultType> sequence;
    private final FaultType fallbackFault;

    private FaultProfile(List<FaultType> sequence, FaultType fallbackFault) {
        this.sequence = List.copyOf(sequence);
        this.fallbackFault = Objects.requireNonNullElse(fallbackFault, FaultType.SUCCESS);
    }

    public static FaultProfile of(FaultType... faults) {
        return new FaultProfile(Arrays.asList(faults), FaultType.SUCCESS);
    }

    /**
     * Helper creating a "sustained failure window":
     * N consecutive failures of type `failureType`, followed by M successes.
     */
    public static FaultProfile sustainedFailures(FaultType failureType, int nFailures, int mSuccesses) {
        return builder()
                .addFailures(failureType, nFailures)
                .addSuccesses(mSuccesses)
                .build();
    }

    /**
     * Helper creating repeating failure/success windows over multiple cycles.
     */
    public static FaultProfile repeatingWindow(FaultType failureType, int nFailures, int mSuccesses, int cycles) {
        Builder b = builder();
        for (int i = 0; i < cycles; i++) {
            b.addFailures(failureType, nFailures);
            b.addSuccesses(mSuccesses);
        }
        return b.build();
    }

    /**
     * Seeded pseudo-random fault sequence generator.
     */
    public static FaultProfile seeded(long seed, int totalAttempts, FaultType... possibleFaults) {
        Random random = new Random(seed);
        List<FaultType> seq = new ArrayList<>();
        FaultType[] pool = (possibleFaults == null || possibleFaults.length == 0)
                ? new FaultType[]{FaultType.RATE_LIMITED, FaultType.OVERLOADED, FaultType.TRANSIENT_5XX}
                : possibleFaults;

        for (int i = 0; i < totalAttempts - 1; i++) {
            seq.add(pool[random.nextInt(pool.length)]);
        }
        seq.add(FaultType.SUCCESS);
        return new FaultProfile(seq, FaultType.SUCCESS);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Retrieves the FaultType for the given 1-indexed attempt number.
     * If attemptNumber exceeds the configured sequence length, returns the fallback fault (default SUCCESS).
     */
    public FaultType getFaultForAttempt(int attemptNumber) {
        int index = attemptNumber - 1;
        if (index < 0) {
            throw new IllegalArgumentException("Attempt number must be >= 1");
        }
        if (index >= sequence.size()) {
            return fallbackFault;
        }
        return sequence.get(index);
    }

    public List<FaultType> getSequence() {
        return sequence;
    }

    public int length() {
        return sequence.size();
    }

    public static final class Builder {
        private final List<FaultType> items = new ArrayList<>();
        private FaultType fallbackFault = FaultType.SUCCESS;

        public Builder add(FaultType fault) {
            items.add(Objects.requireNonNull(fault, "fault must not be null"));
            return this;
        }

        public Builder add(FaultType fault, int count) {
            if (count < 0) throw new IllegalArgumentException("count must be >= 0");
            Objects.requireNonNull(fault, "fault must not be null");
            for (int i = 0; i < count; i++) {
                items.add(fault);
            }
            return this;
        }

        public Builder addFailures(FaultType failureType, int count) {
            return add(failureType, count);
        }

        public Builder addSuccesses(int count) {
            return add(FaultType.SUCCESS, count);
        }

        public Builder fallback(FaultType fallback) {
            this.fallbackFault = Objects.requireNonNull(fallback, "fallback must not be null");
            return this;
        }

        public FaultProfile build() {
            return new FaultProfile(items, fallbackFault);
        }
    }
}
