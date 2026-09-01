package io.polity4j.benchmarks.diy;

import java.util.Objects;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Supplier;

/**
 * A deliberately naive, thread-safe DIY budget tracker component.
 * Increments accumulated spend on every call into the backend layer and rejects once maxBudget is reached.
 * Deliberately does NOT check whether a call was served from cache.
 */
public final class DiyBudgetTracker {

    private final double maxBudget;
    private final DoubleAccumulator currentSpend = new DoubleAccumulator(Double::sum, 0.0);

    public DiyBudgetTracker(double maxBudget) {
        if (maxBudget <= 0.0) {
            throw new IllegalArgumentException("maxBudget must be positive");
        }
        this.maxBudget = maxBudget;
    }

    public synchronized <T> T execute(double callCost, Supplier<T> backendCall) {
        Objects.requireNonNull(backendCall, "backendCall must not be null");
        if (callCost < 0.0) {
            throw new IllegalArgumentException("callCost must be >= 0");
        }

        if (currentSpend.get() + callCost > maxBudget) {
            throw new IllegalStateException(String.format(
                    "Budget limit exceeded! Current spend $%.4f + call cost $%.4f exceeds max budget $%.4f",
                    currentSpend.get(), callCost, maxBudget));
        }

        currentSpend.accumulate(callCost);
        return backendCall.get();
    }

    public double getCurrentSpend() {
        return currentSpend.get();
    }

    public double getMaxBudget() {
        return maxBudget;
    }

    public synchronized void reset() {
        currentSpend.reset();
    }
}
