package io.polity4j.benchmarks.circuitbreaker;

import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.PipelineChain;
import io.polity4j.reliability.circuitbreaker.CircuitBreaker;
import io.polity4j.reliability.circuitbreaker.CircuitBreakerConfig;
import io.polity4j.reliability.circuitbreaker.CircuitBreakerModule;
import io.polity4j.reliability.circuitbreaker.CircuitState;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Benchmark harness wrapping Polity4j's native CircuitBreaker and CircuitBreakerModule.
 */
public final class PolityCircuitBreakerHarness {

    private final CircuitBreaker circuitBreaker;
    private final CircuitBreakerModule module;

    public PolityCircuitBreakerHarness(String provider, CircuitBreakerConfig config) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.circuitBreaker = new CircuitBreaker(provider, config);
        this.module = new CircuitBreakerModule(circuitBreaker);
    }

    public PolityCircuitBreakerHarness(String provider, int failureThreshold, Duration cooldownDuration) {
        this(provider, CircuitBreakerConfig.builder()
                .failureThreshold(failureThreshold)
                .cooldownDuration(cooldownDuration)
                .successesRequiredToClose(1)
                .build());
    }

    public PolityCircuitBreakerHarness(String provider, int failureThreshold, Duration cooldownDuration, int successesRequiredToClose) {
        this(provider, CircuitBreakerConfig.builder()
                .failureThreshold(failureThreshold)
                .cooldownDuration(cooldownDuration)
                .successesRequiredToClose(successesRequiredToClose)
                .build());
    }

    public LlmResponse execute(LlmRequest request, PipelineChain next) {
        return module.process(request, next);
    }

    public String execute(Supplier<String> supplier) {
        LlmRequest dummyRequest = LlmRequest.builder("benchmark prompt", "gpt-4o").build();
        PipelineChain adapterChain = req -> LlmResponse.builder(supplier.get(), "gpt-4o", "openai").build();
        LlmResponse response = module.process(dummyRequest, adapterChain);
        return response.content();
    }

    public CircuitState state() {
        return circuitBreaker.state();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
