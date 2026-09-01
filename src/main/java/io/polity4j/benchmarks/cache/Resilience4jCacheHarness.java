package io.polity4j.benchmarks.cache;

import io.github.resilience4j.cache.Cache;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Benchmark harness for Resilience4j Cache (resilience4j-cache) over JCache (JSR-107).
 */
public final class Resilience4jCacheHarness {

    private final javax.cache.Cache<String, String> jCache;
    private final Cache<String, String> r4jCache;

    public Resilience4jCacheHarness(String cacheName) {
        Objects.requireNonNull(cacheName, "cacheName must not be null");
        CachingProvider cachingProvider = Caching.getCachingProvider();
        CacheManager cacheManager = cachingProvider.getCacheManager();
        
        javax.cache.Cache<String, String> existing = cacheManager.getCache(cacheName, String.class, String.class);
        if (existing != null) {
            this.jCache = existing;
        } else {
            MutableConfiguration<String, String> config = new MutableConfiguration<String, String>()
                    .setTypes(String.class, String.class)
                    .setStoreByValue(false);
            this.jCache = cacheManager.createCache(cacheName, config);
        }

        this.r4jCache = Cache.of(jCache);
    }

    public String execute(String key, Supplier<String> supplier) {
        Function<String, String> cachedFunction = Cache.decorateSupplier(r4jCache, supplier);
        return cachedFunction.apply(key);
    }

    public Cache<String, String> getR4jCache() {
        return r4jCache;
    }

    public javax.cache.Cache<String, String> getJCache() {
        return jCache;
    }
}
