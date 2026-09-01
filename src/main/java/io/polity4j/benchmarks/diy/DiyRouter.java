package io.polity4j.benchmarks.diy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A deliberately naive DIY cost-based router component.
 * Given 2+ named backends each with a configured cost-per-call, picks the cheapest backend.
 * Deliberately does NOT check circuit breaker state or backend health.
 */
public final class DiyRouter<T> {

    public record Backend<T>(String name, double costPerCall, Supplier<T> supplier) {
        public Backend {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(supplier, "supplier must not be null");
            if (costPerCall < 0.0) {
                throw new IllegalArgumentException("costPerCall must be >= 0");
            }
        }
    }

    private final List<Backend<T>> backends;

    public DiyRouter(List<Backend<T>> backends) {
        Objects.requireNonNull(backends, "backends must not be null");
        if (backends.size() < 2) {
            throw new IllegalArgumentException("DiyRouter requires at least 2 backends to perform routing");
        }
        this.backends = List.copyOf(backends);
    }

    /**
     * Selects the backend with the minimum cost per call.
     * Does NOT inspect whether the backend is healthy or if its circuit breaker is OPEN.
     */
    public Backend<T> selectCheapestBackend() {
        return backends.stream()
                .min(Comparator.comparingDouble(Backend::costPerCall))
                .orElseThrow(() -> new IllegalStateException("No backends available"));
    }

    public T routeAndExecute() {
        Backend<T> cheapest = selectCheapestBackend();
        return cheapest.supplier().get();
    }

    public String getCheapestBackendName() {
        return selectCheapestBackend().name();
    }
}
