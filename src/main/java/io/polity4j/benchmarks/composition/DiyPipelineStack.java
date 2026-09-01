package io.polity4j.benchmarks.composition;

import io.polity4j.benchmarks.cache.Resilience4jCacheHarness;
import io.polity4j.benchmarks.circuitbreaker.Resilience4jCircuitBreakerHarness;
import io.polity4j.benchmarks.diy.DiyBudgetTracker;
import io.polity4j.benchmarks.diy.DiyRouter;
import io.polity4j.benchmarks.fallback.Resilience4jFallbackHarness;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Hand-rolled DIY pipeline stack gluing together standalone Resilience4j harnesses and DIY stubs.
 * Unpolished glue code representing manual assembly:
 * Router picks backend -> Cache check -> Retry/CircuitBreaker/Fallback -> DiyBudgetTracker.
 */
public final class DiyPipelineStack {

    public record ConfiguredBackend(
            String name,
            double costPerCall,
            Supplier<String> rawBackendSupplier,
            Resilience4jCircuitBreakerHarness circuitBreaker,
            Resilience4jFallbackHarness fallback
    ) {
        public ConfiguredBackend {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(rawBackendSupplier, "rawBackendSupplier must not be null");
            Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        }
    }

    private final List<ConfiguredBackend> backends;
    private final DiyRouter<String> router;
    private final Resilience4jCacheHarness cacheHarness;
    private final DiyBudgetTracker budgetTracker;

    public DiyPipelineStack(List<ConfiguredBackend> backends, DiyBudgetTracker budgetTracker, Resilience4jCacheHarness cacheHarness) {
        this.backends = List.copyOf(backends);
        this.budgetTracker = Objects.requireNonNull(budgetTracker, "budgetTracker must not be null");
        this.cacheHarness = Objects.requireNonNull(cacheHarness, "cacheHarness must not be null");

        List<DiyRouter.Backend<String>> routerBackends = this.backends.stream()
                .map(b -> new DiyRouter.Backend<>(b.name(), b.costPerCall(), b.rawBackendSupplier()))
                .toList();

        this.router = new DiyRouter<>(routerBackends);
    }

    public String execute(String prompt) {
        // Step 1: Router picks cheapest backend based strictly on cost
        DiyRouter.Backend<String> selected = router.selectCheapestBackend();
        ConfiguredBackend matched = backends.stream()
                .filter(b -> b.name().equals(selected.name()))
                .findFirst()
                .orElseThrow();

        // Step 2: Cache check using prompt + backend name
        String cacheKey = "diy:" + selected.name() + ":" + prompt;

        return cacheHarness.execute(cacheKey, () -> {
            // Step 3 & 4: Fallback / CircuitBreaker wrapper executing DiyBudgetTracker -> Backend
            Supplier<String> coreCall = () -> budgetTracker.execute(matched.costPerCall(), matched.rawBackendSupplier());

            if (matched.fallback() != null) {
                return matched.fallback().execute(() -> matched.circuitBreaker().execute(coreCall));
            } else {
                return matched.circuitBreaker().execute(coreCall);
            }
        });
    }

    public DiyRouter<String> getRouter() { return router; }
    public DiyBudgetTracker getBudgetTracker() { return budgetTracker; }
    public Resilience4jCacheHarness getCacheHarness() { return cacheHarness; }
}
