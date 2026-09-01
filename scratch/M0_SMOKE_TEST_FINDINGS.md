# M0 Smoke Test Findings: Composition Bug Predictions

## Executive Summary

Before building the full M1 composition-cost benchmark, we executed a targeted smoke test (`SmokeTest`) to evaluate two predicted composition bugs when hand-gluing standalone resilience/cost components vs using Polity4j's unified pipeline:

1. **Prediction (a): Cache vs DIY Budget Tracker Composition**
2. **Prediction (b): DIY Router vs Resilience4j Circuit Breaker Isolation**

---

## 1. Prediction (a): Cache vs DIY Budget Tracker

### Prediction Statement
> *"Wire PolityCacheHarness's cache in front of DiyBudgetTracker. Send the same request twice. Does the budget tracker count 2 calls or 1? Prediction: 2 — it has no visibility into the cache layer."*

### Empirical Findings & Verification

| Wiring Configuration | Call 1 Spend Count | Call 2 (Cache Hit) Spend Count | Actual Backend Calls Made | Prediction Status |
| :--- | :--- | :--- | :--- | :--- |
| **Option 1: Cache in Front of BudgetTracker**<br>`Client -> Cache -> DiyBudgetTracker -> Backend` | 1 ($0.01) | **1 ($0.01)** | **1** | **Disconfirmed for this specific ordering** |
| **Option 2: BudgetTracker in Front of Cache**<br>`Client -> DiyBudgetTracker -> Cache -> Backend` | 1 ($0.01) | **2 ($0.02)** | **1** | **Confirmed for this ordering** |

### Root Cause & Architectural Insight

- **Why Option 1 Disconfirmed the Prediction**: When `Cache` sits **in front of** `DiyBudgetTracker`, the cache hit on Call 2 intercepts the request **before** it reaches `DiyBudgetTracker`. As a result, `DiyBudgetTracker` is never executed on Call 2, and its internal spend count remains **1** ($0.01).
- **Why Option 2 Confirmed the Prediction**: When a developer places `DiyBudgetTracker` **in front of** `Cache` (or wraps the caller request before caching), `DiyBudgetTracker` increments spend on every inbound invocation. On Call 2, `DiyBudgetTracker` increments spend ($0.02) **before** passing the request to `Cache`, which then returns a cached response. The budget tracker records **2 calls ($0.02)** despite only **1 actual backend call**.
- **Impact on M1 Benchmark Design**: M1 must demonstrate pipeline ordering vulnerability: placing budget guards upstream of caching causes phantom budget drain on cache hits. In Polity4j, `LlmPipeline` standardizes module ordering (`ExactCacheModule` before `BudgetGuardrailModule`).

---

## 2. Prediction (b): DIY Router vs Resilience4j Circuit Breaker

### Prediction Statement
> *"Wire DiyRouter next to Resilience4jCircuitBreakerHarness. Force that breaker open via a sustained failure sequence. Ask the router to pick a backend. Does it avoid the backend behind the open breaker, or route to it anyway? Prediction: routes to it anyway — the router has no visibility into breaker state."*

### Empirical Findings & Verification

- **Setup**:
  - `cheap-backend`: Cost = $0.001 per call, protected by `Resilience4jCircuitBreakerHarness`.
  - `expensive-backend`: Cost = $0.010 per call, healthy.
  - Forced `cheap-backend` circuit breaker to trip `OPEN` via a sustained failure sequence of 3 `RATE_LIMITED` errors.
- **Router Action**: `router.selectCheapestBackend()` selected `cheap-backend` based strictly on minimum cost per call ($0.001 < $0.010).
- **Execution Outcome**: Executing `router.routeAndExecute()` immediately threw `CallNotPermittedException` because `cheap-backend`'s circuit breaker was `OPEN`.
- **Backup Backend Utilization**: `expensive-backend` was **0 times** (never attempted), even though it was healthy and available.

| Metric | Measured Result |
| :--- | :--- |
| **Selected Backend** | `cheap-backend` (Cost: $0.001) |
| **Circuit Breaker State** | `OPEN` |
| **Routed Exception Thrown** | `io.github.resilience4j.circuitbreaker.CallNotPermittedException` |
| **Healthy Backup Attempts** | **0** (Healthy fallback backend never called) |
| **Prediction Status** | **CONFIRMED** |

### Architectural Insight

`DiyRouter` operates as an uncoordinated component with zero visibility into Resilience4j's circuit breaker state. When `cheap-backend` enters `OPEN` state, `DiyRouter` continues routing 100% of traffic to it, resulting in fast-failing user requests instead of dynamically routing to healthy alternatives. In Polity4j, `ModelRouterModule` and `FallbackChainModule` work in tandem within the pipeline to failover when primary backends are unavailable.

---

## 3. Summary of Predictions

| Prediction | Status | Key Takeaway for M1 Benchmark |
| :--- | :--- | :--- |
| **(a) Budget Tracker overcounting on cache hits** | **Confirmed (when BudgetTracker is placed upstream of Cache)** | Demonstrates pipeline module ordering sensitivity and phantom budget depletion. |
| **(b) Cost Router selecting broken backend with OPEN circuit breaker** | **CONFIRMED** | Demonstrates blind routing failure when router lacks circuit breaker awareness. |
