package io.polity4j.benchmarks.cache;

import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.PipelineChain;
import io.polity4j.cost.cache.ExactCacheModule;
import io.polity4j.cost.cache.InMemoryCacheStore;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Benchmark harness for Polity4j's native ExactCacheModule.
 */
public final class PolityCacheHarness {

    private final ExactCacheModule cacheModule;

    public PolityCacheHarness() {
        this.cacheModule = new ExactCacheModule(new InMemoryCacheStore());
    }

    public LlmResponse execute(LlmRequest request, PipelineChain next) {
        return cacheModule.process(request, next);
    }

    public String execute(String cacheKeyPrompt, Supplier<String> supplier) {
        Objects.requireNonNull(cacheKeyPrompt, "cacheKeyPrompt must not be null");
        LlmRequest request = LlmRequest.builder(cacheKeyPrompt, "gpt-4o").build();
        PipelineChain adapterChain = req -> LlmResponse.builder(supplier.get(), "gpt-4o", "openai").build();
        LlmResponse response = cacheModule.process(request, adapterChain);
        return response.content();
    }

    public long hits() {
        return cacheModule.hits();
    }

    public long misses() {
        return cacheModule.misses();
    }

    public double hitRate() {
        return cacheModule.hitRate();
    }

    public ExactCacheModule getCacheModule() {
        return cacheModule;
    }
}
