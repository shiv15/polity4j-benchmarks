# M0 Resilience & Optimization Benchmark Findings: Polity4j vs Resilience4j

## Executive Summary

We initiated the `polity4j-benchmarks` project to compare **Polity4j**'s native reliability and cost optimization modules (Circuit Breaker, Fallback, Exact Cache) against **Resilience4j**. We extended the fault-injection harness (`FaultProfile`, `AttemptRecorder`) to support arbitrary-length fault sequences and sustained failure windows ($N$ consecutive failures, then $M$ successes).

Via contract tests (`CircuitBreakerContractTest`, `FallbackContractTest`, `CacheContractTest`), we verified parity, measured backend attempt counts, and analyzed integration friction across Circuit Breaker, Fallback, and Caching mechanisms.

---

## 1. Circuit Breaker API & Configuration Comparison

| Aspect | Polity4j | Resilience4j |
| :--- | :--- | :--- |
| **Config Class** | `io.polity4j.reliability.circuitbreaker.CircuitBreakerConfig` | `io.github.resilience4j.circuitbreaker.CircuitBreakerConfig` |
| **Failure Metric** | Consecutive failure count (`failureThreshold(int)`) | Sliding window failure rate (`failureRateThreshold(float)`) |
| **Default Threshold** | `failureThreshold = 5` consecutive failures | `failureRateThreshold = 50.0%` over 100 calls |
| **Cooldown Duration** | `cooldownDuration(Duration)` (default 30s) | `waitDurationInOpenState(Duration)` (default 60s) |
| **Half-Open Successes**| `successesRequiredToClose(int)` (default 1) | `permittedNumberOfCallsInHalfOpenState(int)` (default 10) |
| **Short-Circuit Exception** | `io.polity4j.core.exception.ModelUnavailableException` | `io.github.resilience4j.circuitbreaker.CallNotPermittedException` |
| **Error Classification** | Built-in provider vs. application error separation | Custom predicates (`recordException`, `ignoreException`) |

---

## 2. Circuit Breaker Metrics: Sustained Failure Window

### Scenario Setup
- **Configured Threshold ($N$)**: 5 consecutive failures before tripping OPEN.
- **Total Caller Invocations**: 10 calls.
- **Fault Sequence**: 10 consecutive `RATE_LIMITED` (429) errors from backend.

### Experimental Results

| Library | Total Caller Invocations | Wasted Backend Calls Before Trip | Fast Short-Circuited Calls | Breaker State | Parity? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Polity4j** | 10 | **5** | **5** | `OPEN` | **Yes** |
| **Resilience4j** | 10 | **5** | **5** | `OPEN` | **Yes** |

---

## 3. Fallback Mechanism & Behavior Analysis

### API & Architectural Comparison

| Aspect | Polity4j | Resilience4j |
| :--- | :--- | :--- |
| **Primary Component** | `FallbackChainModule` | `SupplierUtils.recover(...)` (decorated over `Retry`) |
| **Input Structure** | `List<LlmClient>` (ordered failover clients) | Fallback recovery function `Function<Throwable, T>` |
| **Exception Eligibility**| Provider errors (`ModelUnavailable`, `Overloaded`, `RateLimit`) | Any matching exception in `recover(...)` |
| **Default Overloaded Behavior** | **Fails fast after 1 attempt** (does NOT retry `OverloadedException` on same provider; immediately triggers Fallback) | Retries all exceptions if `retryOnException` is generic |

### Fallback Contract Test Results (Exhausted Retries)

- **Scenario**: Primary backend fails all attempts (3 max retries configured).
- **Goal**: Confirm fallback value returned cleanly without throwing exceptions, and log exact primary backend attempts before fallback.

| Library | Max Retry Attempts Configured | Primary Backend Attempts Made | Final Return Value | Succeeded with Fallback? |
| :--- | :--- | :--- | :--- | :--- |
| **Polity4j** | 3 | **3** | `"Polity4j Fallback Success"` | **Yes** |
| **Resilience4j** | 3 | **3** | `"Resilience4j Fallback Success"` | **Yes** |

### Fallback API Surprises & Key Insights

1. **No Standalone `resilience4j-fallback` Dependency**:
   - Unlike CircuitBreaker and Retry, Resilience4j does NOT have a standalone `resilience4j-fallback` artifact. Fallback decoration is implemented via `io.github.resilience4j.core.SupplierUtils.recover(supplier, fallbackFunction)`.
2. **Polity4j Provider-Aware Failover**:
   - Polity4j's `FallbackChainModule` operates on domain-aware `LlmClient` instances. If a primary provider encounters `OverloadedException` or `ModelUnavailableException`, Polity4j skips unnecessary retries on the busy provider and immediately invokes the next fallback client in the chain.

---

## 4. Exact Caching & JCache Integration Friction Analysis

### API & Setup Comparison

| Aspect | Polity4j | Resilience4j |
| :--- | :--- | :--- |
| **Cache Module** | `ExactCacheModule` | `resilience4j-cache` |
| **Storage Backend** | Native `InMemoryCacheStore` or `CaffeineCacheStore` | Requires external JCache (JSR-107 SPI provider) |
| **External Dependencies** | **0 required** (built-in in-memory store) | **`javax.cache:cache-api` + JCache Provider** (e.g. `cache-ri-impl` or `ehcache`) |
| **Key Generation** | Automatic SHA-256 prompt & model hashing (`CacheKey.from(request)`) | Manual key passing to `cachedFunction.apply(key)` |
| **Integration Friction** | **Zero Friction**: `.with(new ExactCacheModule())` | **High Friction**: Must configure `CachingProvider`, `CacheManager`, `javax.cache.Cache`, and wire into `Cache.of(jcache)` |

### Caching Contract Test Results

- **Scenario**: Send 2 identical requests with the same prompt/key.
- **Goal**: Verify 1st request reaches backend (miss), 2nd request is served from cache with **zero additional backend calls**.

| Library | Request 1 Outcome | Request 1 Backend Attempts | Request 2 Outcome | Request 2 Backend Attempts | Total Backend Attempts | Cache Hit? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Polity4j** | Miss (called backend) | 1 | Hit (served from cache) | **0** | **1** | **Yes** |
| **Resilience4j** | Miss (called backend) | 1 | Hit (served from JCache) | **0** | **1** | **Yes** |

### Integration Friction Data Point (Critical for Benchmarks)

- **Resilience4j JCache Setup**: Running `resilience4j-cache` requires pulling in `javax.cache:cache-api:1.1.1` and an explicit JSR-107 SPI implementation (such as `org.jsr107.ri:cache-ri-impl:1.1.1` or `org.ehcache:ehcache`). Without an explicit JCache provider on the classpath, `Caching.getCachingProvider()` throws `CacheException: No CachingProvider found`.
- **Polity4j Out-of-the-Box Experience**: Polity4j includes `InMemoryCacheStore` and `CaffeineCacheStore` natively, automatically hashing LLM request metadata without requiring JSR-107 boilerplate or external provider configuration.

---

## 5. Summary Matrix across All Harnesses

| Feature | Polity4j | Resilience4j | Parity Verified? |
| :--- | :--- | :--- | :--- |
| **Circuit Breaker** | Trips OPEN on 5 consecutive failures; short-circuits fast | Trips OPEN on 5 consecutive failures (100% rate threshold); short-circuits fast | **Yes** |
| **Fallback** | Retries 3 times then invokes fallback `LlmClient` | Retries 3 times then invokes fallback via `SupplierUtils.recover` | **Yes** |
| **Exact Cache** | Cache hit on 2nd request; 0 backend calls | Cache hit on 2nd request via JCache; 0 backend calls | **Yes** |
