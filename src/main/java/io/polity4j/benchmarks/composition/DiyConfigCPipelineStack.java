package io.polity4j.benchmarks.composition;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.core.SupplierUtils;
import io.polity4j.benchmarks.cache.Resilience4jCacheHarness;
import io.polity4j.benchmarks.circuitbreaker.Resilience4jCircuitBreakerHarness;
import io.polity4j.benchmarks.diy.DiyBudgetTracker;
import io.polity4j.benchmarks.diy.DiyRouter;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * DIY Stack Config C:
 * DiyRouter selects cheapest backend first, but an explicit per-call Fallback chain is wired
 * across alternate backends (cheap-backend -> unreliable-cheap-backend -> expensive-backend).
 * Demonstrates isolation of structural routing gaps vs implementation fallback gaps.
 */
public final class DiyConfigCPipelineStack {

    public record ConfiguredBackend(
            String name,
            double costPerCall,
            Supplier<String> rawBackendSupplier,
            Resilience4jCircuitBreakerHarness circuitBreaker
    ) {
        public ConfiguredBackend {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(rawBackendSupplier, "rawBackendSupplier must not be null");
            Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        }
    }

    private final ConfiguredBackend primary;
    private final ConfiguredBackend fallback1;
    private final ConfiguredBackend fallback2;

    private final DiyRouter<String> router;
    private final Resilience4jCacheHarness cacheHarness;
    private final DiyBudgetTracker budgetTracker;

    private final AtomicInteger succeededViaFallbackCount = new AtomicInteger(0);
    private final AtomicInteger wastedOpenBreakerCallCount = new AtomicInteger(0);
    private final AtomicInteger fallback1UsageCount = new AtomicInteger(0);
    private final AtomicInteger fallback2UsageCount = new AtomicInteger(0);

    public DiyConfigCPipelineStack(
            ConfiguredBackend primary,
            ConfiguredBackend fallback1,
            ConfiguredBackend fallback2,
            DiyBudgetTracker budgetTracker,
            Resilience4jCacheHarness cacheHarness
    ) {
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        this.fallback1 = Objects.requireNonNull(fallback1, "fallback1 must not be null");
        this.fallback2 = Objects.requireNonNull(fallback2, "fallback2 must not be null");
        this.budgetTracker = Objects.requireNonNull(budgetTracker, "budgetTracker must not be null");
        this.cacheHarness = Objects.requireNonNull(cacheHarness, "cacheHarness must not be null");

        List<DiyRouter.Backend<String>> routerBackends = List.of(
                new DiyRouter.Backend<>(primary.name(), primary.costPerCall(), primary.rawBackendSupplier()),
                new DiyRouter.Backend<>(fallback1.name(), fallback1.costPerCall(), fallback1.rawBackendSupplier()),
                new DiyRouter.Backend<>(fallback2.name(), fallback2.costPerCall(), fallback2.rawBackendSupplier())
        );

        this.router = new DiyRouter<>(routerBackends);
    }

    public String execute(String prompt) {
        // Step 1: Router picks cheapest backend based strictly on cost (always 'primary' when cost is lowest)
        DiyRouter.Backend<String> selected = router.selectCheapestBackend();
        
        String cacheKey = "diy-config-c:" + selected.name() + ":" + prompt;

        return cacheHarness.execute(cacheKey, () -> {
            // Supplier 1: Primary backend (cheap-backend)
            Supplier<String> primaryCall = () -> {
                if (primary.circuitBreaker().state() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
                    wastedOpenBreakerCallCount.incrementAndGet();
                }
                Supplier<String> trackerCall = () -> budgetTracker.execute(primary.costPerCall(), primary.rawBackendSupplier());
                return primary.circuitBreaker().execute(trackerCall);
            };

            // Supplier 2: Fallback 1 (unreliable-cheap-backend)
            Supplier<String> fallback1Call = () -> {
                fallback1UsageCount.incrementAndGet();
                Supplier<String> trackerCall = () -> budgetTracker.execute(fallback1.costPerCall(), fallback1.rawBackendSupplier());
                return fallback1.circuitBreaker().execute(trackerCall);
            };

            // Supplier 3: Fallback 2 (expensive-backend)
            Supplier<String> fallback2Call = () -> {
                fallback2UsageCount.incrementAndGet();
                Supplier<String> trackerCall = () -> budgetTracker.execute(fallback2.costPerCall(), fallback2.rawBackendSupplier());
                return fallback2.circuitBreaker().execute(trackerCall);
            };

            // Chain: Primary -> Recover with Fallback 1 -> Recover with Fallback 2
            Supplier<String> recoverWithFallback2 = SupplierUtils.recover(fallback1Call, e -> {
                succeededViaFallbackCount.incrementAndGet();
                return fallback2Call.get();
            });

            Supplier<String> fullChain = SupplierUtils.recover(primaryCall, e -> {
                succeededViaFallbackCount.incrementAndGet();
                return recoverWithFallback2.get();
            });

            return fullChain.get();
        });
    }

    public int getSucceededViaFallbackCount() { return succeededViaFallbackCount.get(); }
    public int getWastedOpenBreakerCallCount() { return wastedOpenBreakerCallCount.get(); }
    public int getFallback1UsageCount() { return fallback1UsageCount.get(); }
    public int getFallback2UsageCount() { return fallback2UsageCount.get(); }
    public DiyRouter<String> getRouter() { return router; }
}
