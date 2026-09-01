# M0 Circuit Breaker Benchmark Findings: Polity4j vs Resilience4j

## Executive Summary

We initiated the `polity4j-benchmarks` project to compare **Polity4j**'s native circuit breaker implementation against **Resilience4j**. We extended the fault-injection harness (`FaultProfile`, `AttemptRecorder`) to support arbitrary-length fault sequences and sustained failure windows ($N$ consecutive failures, then $M$ successes).

Via contract tests (`CircuitBreakerContractTest`), we verified that under sustained provider failures, both libraries trip their circuit breakers after exactly $N$ failures and immediately short-circuit subsequent calls, avoiding wasted backend network executions.

---

## 1. API & Configuration Comparison

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

## 2. Configuration Alignment Strategy

To achieve exact behavioral parity for benchmark contract testing:

1. **Polity4j**:
   ```java
   CircuitBreakerConfig config = CircuitBreakerConfig.builder()
           .failureThreshold(5)
           .cooldownDuration(Duration.ofSeconds(30))
           .successesRequiredToClose(1)
           .build();
   ```

2. **Resilience4j**:
   ```java
   CircuitBreakerConfig config = CircuitBreakerConfig.custom()
           .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
           .slidingWindowSize(5)
           .minimumNumberOfCalls(5)
           .failureRateThreshold(100.0f)
           .waitDurationInOpenState(Duration.ofSeconds(30))
           .permittedNumberOfCallsInHalfOpenState(1)
           .build();
   ```

---

## 3. Contract Test Metrics: Sustained Failure Window

### Scenario Setup
- **Configured Threshold ($N$)**: 5 consecutive failures before tripping OPEN.
- **Total Caller Invocations**: 10 calls.
- **Fault Sequence**: 10 consecutive `RATE_LIMITED` (429) errors from backend.

### Experimental Results

| Library | Total Caller Invocations | Wasted Backend Calls Before Trip | Fast Short-Circuited Calls | Breaker State | Parity? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Polity4j** | 10 | **5** | **5** | `OPEN` | **Yes** |
| **Resilience4j** | 10 | **5** | **5** | `OPEN` | **Yes** |

### Key Metric Analysis
- **Wasted Calls Before Trip**: Both libraries allowed exactly **5 backend attempts** through before tripping the circuit.
- **Short-Circuit Start Point**: On invocation #6, both libraries detected the `OPEN` circuit state and failed fast without invoking the backend (`AttemptRecorder` counter remained at 5).
- **Fast Failure Mechanics**:
  - Polity4j threw `ModelUnavailableException`.
  - Resilience4j threw `CallNotPermittedException`.

---

## 4. API Surprises & Design Insights

1. **Consecutive Failures vs. Failure Rate Percentage**:
   - Polity4j tracks simple consecutive failure counts. In LLM integration contexts (e.g. OpenAI / Anthropic API outages or 429 rate limit spikes), consecutive failure counting is simple and predictable.
   - Resilience4j defaults to a sliding window percentage calculation (`failureRateThreshold = 50.0%`). To mimic consecutive failure behavior, `slidingWindowSize`, `minimumNumberOfCalls`, and `failureRateThreshold(100.0f)` must be explicitly set.

2. **Built-in Domain Exception Filtering**:
   - Polity4j's `CircuitBreakerModule` automatically distinguishes between **provider failures** (`RateLimitException`, `OverloadedException`, `ModelUnavailableException`, `PartialResponseException`) and **application errors** (`BudgetExceededException`, `ContextOverflowException`, `ResourceNotFoundException`).
   - Application errors do not increment Polity4j's circuit failure count because they indicate caller input issues rather than provider unhealthiness.

3. **Extensibility for Additional Libraries**:
   - The modular structure in `polity4j-benchmarks` allows adding other resilience libraries (e.g. Failsafe, Spring Retry, LangChain4j) by implementing a harness wrapper exposing `execute(Supplier<T>)` and inspecting backend calls via `AttemptRecorder`.

---

## 5. Conclusion

Both Polity4j and Resilience4j successfully protect downstream backends during sustained failures by tripping after the configured threshold and short-circuiting fast. Polity4j's consecutive failure model and domain exception awareness provide an out-of-the-box experience tailored to LLM backend reliability.
