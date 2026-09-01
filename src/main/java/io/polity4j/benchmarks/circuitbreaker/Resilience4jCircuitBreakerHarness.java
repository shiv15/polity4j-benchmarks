package io.polity4j.benchmarks.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Benchmark harness wrapping Resilience4j's native CircuitBreaker and CircuitBreakerConfig.
 */
public final class Resilience4jCircuitBreakerHarness {

    private final CircuitBreaker circuitBreaker;

    public Resilience4jCircuitBreakerHarness(String name, CircuitBreakerConfig config) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.circuitBreaker = CircuitBreaker.of(name, config);
    }

    public Resilience4jCircuitBreakerHarness(String name, int failureThreshold, Duration cooldownDuration) {
        this(name, failureThreshold, cooldownDuration, 1);
    }

    public Resilience4jCircuitBreakerHarness(String name, int failureThreshold, Duration cooldownDuration, int successesRequiredToClose) {
        this(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failureThreshold)
                .minimumNumberOfCalls(failureThreshold)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(cooldownDuration)
                .permittedNumberOfCallsInHalfOpenState(successesRequiredToClose)
                .build());
    }

    public <T> T execute(Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        return decorated.get();
    }

    public CircuitBreaker.State state() {
        return circuitBreaker.getState();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
