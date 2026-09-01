# M1 Composition-Cost Benchmark Findings

## Executive Summary

The **M1 Composition-Cost Benchmark** evaluates the structural composition risks and integration costs of hand-rolling a resilience and cost orchestration stack using standalone **Resilience4j** modules and DIY stubs vs using **Polity4j**'s native, unified `LlmPipeline`.

Unlike isolated component benchmarks, M1 measures the hidden bugs that arise when independently configured resilience, routing, caching, and budget components are glued together by hand.

---

## 1. Headline Scenario: Router Blind to Circuit Breaker State ($N=20$)

### Experimental Setup
- **Workload**: $N=20$ sequential requests.
- **Backend Infrastructure**:
  - `cheap-backend`: $0.001 per call. Healthy for requests 1–5, then encounters sustained provider failures (`RATE_LIMITED` / 429) for requests 6–20. Circuit breaker failure threshold = 3.
  - `expensive-backend`: $0.010 per call. Healthy throughout all 20 requests.
  - `unreliable-cheap-backend`: $0.002 per call.

### Results & Empirical Metrics

| Metric | DIY Hand-Rolled Stack | Polity4j Unified Pipeline | Parity / Winner |
| :--- | :--- | :--- | :--- |
| **Total Inbound Requests** | 20 | 20 | - |
| **User Requests Succeeded** | **5** (25.0% success rate) | **20** (100.0% success rate) | **Polity4j (+75% availability)** |
| **User Requests Failed** | **15** | **0** | **Polity4j (0 user-facing failures)** |
| **Wasted Calls into OPEN Breaker**| **14** calls | **0** calls | **Polity4j (0 wasted calls)** |
| **Dollars Spent on Failed Calls** | **$0.0010** | **$0.0000** | **Polity4j ($0 wasted spend)** |
| **Traffic Shifted to Healthy Backup**| **False** (0 attempts on `expensive-backend`) | **True** (Shifted on request 6) | **Polity4j (Automatic failover)** |
| **Failures Before Failover Shift** | **15** (Never shifted) | **0** | **Polity4j (0 user failures)** |

### Smoke Test Prediction Divergence Note
- **Prediction vs Empirical Reality**: In the single-call smoke test, we estimated 12 wasted calls into the OPEN breaker based on an assumed 3 failing backend calls before trip. In the full $N=20$ execution, Resilience4j's count-based sliding window failure rate calculation crossed threshold on request 7, resulting in **14 calls** (requests 7–20) throwing `CallNotPermittedException`.
- **Root Cause**: The hand-rolled `DiyRouter` operates strictly on static cost-per-call ($0.001 < $0.002 < $0.010) with zero visibility into Resilience4j's circuit breaker state. When `cheap-backend`'s circuit breaker tripped OPEN, `DiyRouter` continued blindly routing 100% of traffic into the OPEN breaker, failing 15 user requests while healthy `expensive-backend` received 0 traffic.
- **Polity4j Solution**: Polity4j's `FallbackChainModule` and `CircuitBreakerModule` share context within `LlmPipeline`. When `cheap-backend` fails or trips OPEN, Polity4j automatically failovers to `expensive-backend`, ensuring 100% request completion without user-facing errors.

---

## 2. Secondary Scenario: Budget / Cache Ordering Sensitivity

### Experimental Setup
- **Workload**: Send an identical prompt twice (`cost = $0.01` per call).
- **Tested Configurations**:
  - **Config A (Cache-First)**: `Client -> Cache -> DiyBudgetTracker -> Backend`
  - **Config B (Budget-First)**: `Client -> DiyBudgetTracker -> Cache -> Backend`
  - **Polity4j Pipeline**: `LlmPipeline` enforcing `ExactCacheModule` before `BudgetGuardrailModule`.

### Results & Phantom Spend Table

| Pipeline Configuration | Expected Spend | Recorded Spend | Phantom Spend | Ordering Status |
| :--- | :--- | :--- | :--- | :--- |
| **DIY Config A (Cache-First)** | $0.01 | $0.01 | **$0.00** | Correct ordering (Manual) |
| **DIY Config B (Budget-First)** | $0.01 | $0.02 | **+$0.01 (+100%)** | **Flawed ordering (Phantom Drain)** |
| **Polity4j Unified Pipeline** | $0.01 | $0.01 | **$0.00** | **Guaranteed ordering by design** |

### Architectural Insight
- **Honest Ordering Analysis**: If a developer happens to order Config A correctly (`Cache` before `BudgetTracker`), the cache hit on Call 2 intercepts the request before reaching the budget tracker, resulting in zero phantom spend. However, if a developer orders Config B (`BudgetTracker` before `Cache`), every inbound request increments the spend counter before hitting the cache, causing **$0.01 phantom budget depletion (+100% overcounting)** on cache hits.
- **Polity4j Solution**: Polity4j eliminates manual ordering risk altogether. `LlmPipeline` standardizes module execution order (`ExactCacheModule` sits before `BudgetGuardrailModule`), guaranteeing zero phantom spend without relying on developer luck.

---

## 3. Composition-Cost Accounting

| Metric | DIY Hand-Rolled Stack | Polity4j Unified Pipeline | Savings / Reduction |
| :--- | :--- | :--- | :--- |
| **Lines of Glue Code (LOC)** | **330 LOC** | **40 LOC** | **-87.9% LOC reduction** |
| **Distinct Maven Dependencies** | **7 dependencies** (`resilience4j-retry`, `resilience4j-circuitbreaker`, `resilience4j-cache`, `resilience4j-all`, `javax.cache:cache-api`, `cache-ri-impl`, DIY stubs) | **1 dependency** (`polity4j-bom` / `polity4j-*`) | **7x dependency reduction** |
| **Separate Config Objects** | **5 config objects** (`CircuitBreakerConfig`, `RetryConfig`, JCache `MutableConfiguration`, `DiyBudgetTracker`, `DiyRouter`) | **1 pipeline config** | **5x configuration reduction** |

---

## 4. Conclusion

Hand-rolling a resilience and cost pipeline by gluing standalone libraries together creates critical architectural vulnerabilities:
1. **Blind Routing Outages**: Uncoordinated cost routers route traffic into OPEN circuit breakers, causing 15 out of 20 requests to fail while healthy backup backends sit idle.
2. **Phantom Budget Depletion**: Incorrect module ordering drains user budget counters on cached responses.
3. **High Integration Cost**: 7 distinct dependencies, 330 lines of unpolished glue code, and 5 separate configuration objects must be manually synchronized.

Polity4j solves these composition costs out-of-the-box: achieving **100% request availability**, **$0 wasted spend**, **0 phantom budget drain**, and an **88% reduction in glue code**.
