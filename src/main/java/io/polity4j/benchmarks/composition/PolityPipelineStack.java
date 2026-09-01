package io.polity4j.benchmarks.composition;

import io.polity4j.core.LlmClient;
import io.polity4j.core.LlmPipeline;
import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.cost.BudgetConfig;
import io.polity4j.cost.BudgetGuardrailModule;
import io.polity4j.cost.cache.ExactCacheModule;
import io.polity4j.cost.cache.InMemoryCacheStore;
import io.polity4j.reliability.RetryConfig;
import io.polity4j.reliability.RetryModule;
import io.polity4j.reliability.circuitbreaker.CircuitBreaker;
import io.polity4j.reliability.circuitbreaker.CircuitBreakerConfig;
import io.polity4j.reliability.circuitbreaker.CircuitBreakerModule;
import io.polity4j.reliability.fallback.FallbackChainModule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Unified pipeline stack using Polity4j's native LlmPipeline.
 * Combines ExactCacheModule, RetryModule, CircuitBreakerModule, FallbackChainModule, and BudgetGuardrailModule.
 */
public final class PolityPipelineStack {

    private final LlmPipeline pipeline;
    private final ExactCacheModule cacheModule;
    private final CircuitBreaker circuitBreaker;

    public PolityPipelineStack(
            LlmClient primaryClient,
            List<LlmClient> fallbackClients,
            int failureThreshold,
            BigDecimal maxOrgBudget
    ) {
        Objects.requireNonNull(primaryClient, "primaryClient must not be null");
        Objects.requireNonNull(fallbackClients, "fallbackClients must not be null");

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.builder()
                .failureThreshold(failureThreshold)
                .cooldownDuration(Duration.ofSeconds(30))
                .successesRequiredToClose(1)
                .build();
        this.circuitBreaker = new CircuitBreaker(primaryClient.provider(), cbConfig);

        this.cacheModule = new ExactCacheModule(new InMemoryCacheStore());
        RetryModule retryModule = new RetryModule(RetryConfig.builder().maxAttempts(3).initialDelay(Duration.ofMillis(10)).build());
        CircuitBreakerModule cbModule = new CircuitBreakerModule(circuitBreaker);

        BudgetConfig budgetConfig = new BudgetConfig(null, null, maxOrgBudget);
        BudgetGuardrailModule budgetModule = new BudgetGuardrailModule(budgetConfig);

        LlmPipeline.Builder builder = LlmPipeline.builder(primaryClient)
                .with(cacheModule)
                .with(retryModule)
                .with(cbModule);

        if (fallbackClients != null && !fallbackClients.isEmpty()) {
            builder.with(new FallbackChainModule(fallbackClients));
        }

        builder.with(budgetModule);
        this.pipeline = builder.build();
    }

    public LlmResponse execute(LlmRequest request) {
        return pipeline.execute(request);
    }

    public String execute(String prompt, String model) {
        LlmRequest request = LlmRequest.builder(prompt, model).build();
        return execute(request).content();
    }

    public ExactCacheModule getCacheModule() { return cacheModule; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
}
