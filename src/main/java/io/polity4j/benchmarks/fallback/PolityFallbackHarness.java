package io.polity4j.benchmarks.fallback;

import io.polity4j.core.LlmClient;
import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.PipelineChain;
import io.polity4j.reliability.RetryConfig;
import io.polity4j.reliability.RetryModule;
import io.polity4j.reliability.fallback.FallbackChainModule;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Benchmark harness for Polity4j's FallbackChainModule combined with RetryModule.
 */
public final class PolityFallbackHarness {

    private final RetryModule retryModule;
    private final FallbackChainModule fallbackModule;

    public PolityFallbackHarness(String fallbackValue, int maxAttempts) {
        Objects.requireNonNull(fallbackValue, "fallbackValue must not be null");
        RetryConfig retryConfig = RetryConfig.builder()
                .maxAttempts(maxAttempts)
                .initialDelay(Duration.ofMillis(10))
                .multiplier(1.0)
                .maxDelay(Duration.ofMillis(100))
                .build();
        this.retryModule = new RetryModule(retryConfig);

        LlmClient fallbackClient = new LlmClient() {
            @Override
            public LlmResponse call(LlmRequest request) {
                return LlmResponse.builder(fallbackValue, request.model(), "fallback-provider").build();
            }

            @Override
            public String provider() {
                return "fallback-provider";
            }
        };

        this.fallbackModule = new FallbackChainModule(List.of(fallbackClient));
    }

    public LlmResponse execute(LlmRequest request, PipelineChain primaryChain) {
        // Chain: FallbackChainModule -> RetryModule -> primaryChain
        PipelineChain retryChain = req -> retryModule.process(req, primaryChain);
        return fallbackModule.process(request, retryChain);
    }

    public String execute(Supplier<String> primarySupplier) {
        LlmRequest request = LlmRequest.builder("fallback benchmark", "gpt-4o").build();
        PipelineChain primaryChain = req -> LlmResponse.builder(primarySupplier.get(), "gpt-4o", "openai").build();
        LlmResponse response = execute(request, primaryChain);
        return response.content();
    }
}
