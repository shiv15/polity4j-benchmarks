package io.polity4j.benchmarks.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe recorder capturing attempt history at the backend boundary.
 * Allows benchmarking exact backend attempt counts and detecting short-circuit behavior.
 */
public final class AttemptRecorder {

    private final List<AttemptRecord> records = Collections.synchronizedList(new ArrayList<>());

    public void recordAttempt(int attemptNumber, FaultType faultType, boolean isTerminal) {
        records.add(new AttemptRecord(attemptNumber, System.currentTimeMillis(), faultType, isTerminal));
    }

    public List<AttemptRecord> getRecords() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    public int totalAttempts() {
        return records.size();
    }

    public boolean endedWithSuccess() {
        synchronized (records) {
            if (records.isEmpty()) return false;
            AttemptRecord last = records.get(records.size() - 1);
            return last.faultType() == FaultType.SUCCESS;
        }
    }

    public void reset() {
        records.clear();
    }
}
