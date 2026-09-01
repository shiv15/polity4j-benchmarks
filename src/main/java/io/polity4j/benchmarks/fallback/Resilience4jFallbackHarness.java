package io.polity4j.benchmarks.fallback;

import io.github.resilience4j.core.SupplierUtils;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Benchmark harness for Resilience4j Retry combined with Fallback (SupplierUtils.recover / Fallback.decorateSupplier).
 */
public final class Resilience4jFallbackHarness {

    private final Retry retry;
    private final String fallbackValue;

    public Resilience4jFallbackHarness(String fallbackValue, int maxAttempts) {
        this.fallbackValue = Objects.requireNonNull(fallbackValue, "fallbackValue must not be null");
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(e -> true)
                .build();
        this.retry = Retry.of("resilience4jFallback", config);
    }

    /**
     * Fallback helper method matching Fallback.decorateSupplier pattern using Resilience4j's SupplierUtils.recover.
     */
    public static <T> Supplier<T> decorateSupplier(Supplier<T> supplier, Function<Throwable, T> fallbackFunction) {
        return SupplierUtils.recover(supplier, fallbackFunction);
    }

    public <T> T execute(Supplier<T> primarySupplier, T fallbackReturn) {
        Supplier<T> decoratedRetry = Retry.decorateSupplier(retry, primarySupplier);
        Supplier<T> decoratedFallback = decorateSupplier(decoratedRetry, e -> fallbackReturn);
        return decoratedFallback.get();
    }

    public String execute(Supplier<String> primarySupplier) {
        return execute(primarySupplier, fallbackValue);
    }
}
