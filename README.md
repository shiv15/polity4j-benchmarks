# Polity4j Benchmarks

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/shiv15/polity4j-benchmarks)
[![Java Version](https://img.shields.io/badge/java-21%2B-blue.svg)](https://www.oracle.com/java/technologies/downloads/#java21)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

`polity4j-benchmarks` is an empirical evaluation suite comparing [**Polity4j**](https://github.com/shiv15/polity4j) against general-purpose fault-tolerance libraries (such as **Resilience4j**) and hand-rolled DIY glue stacks.

Rather than focusing solely on throughput (JMH microbenchmarks), this suite measures the **design cost, composition safety, and agentic reliability** of building LLM production pipelines.

---

## Benchmark Structure

The repository is structured into three primary benchmark modules:

```
polity4j-benchmarks/
├── src/main/java/io/polity4j/benchmarks/
│   ├── composition/      # DIY vs Polity4j pipeline composition stacks (M1)
│   ├── diy/              # DIY component stubs (DiyRouter, DiyBudgetTracker)
│   └── harness/          # Fault-injection backend harnesses (AttemptRecorder, FaultProfile)
├── src/test/java/io/polity4j/benchmarks/
│   ├── circuitbreaker/   # M0: Circuit Breaker contract tests
│   ├── cache/            # M0: Exact Cache contract tests
│   ├── fallback/         # M0: Fallback chain contract tests
│   ├── composition/      # M1: Composition-cost & headline benchmark runner
│   └── loop/             # M2: Agent Loop Detection evaluation spike
└── results/              # Machine-readable JSON outcome artifacts
```

---

## Key Benchmark Modules & Findings

### Module 0: Single-Decorator Contract Parity (M0)
Direct head-to-head contract tests verifying isolated parity between Polity4j modules and Resilience4j wrappers:
- **Circuit Breaker**: Confirms failure threshold tripping (3 consecutive failures) and short-circuiting (`CallNotPermittedException`).
- **Fallback**: Confirms failover decorator return values and attempt accounting.
- **Cache**: Confirms exact hit/miss behavior (0 backend calls on subsequent cache hit).

### Module 1: Composition-Cost Benchmark (M1)
Evaluates the architectural risks and integration overhead when hand-rolling a multi-module LLM pipeline (Router + Cache + Circuit Breaker + Fallback + Budget Guardrail) vs using Polity4j's unified `LlmPipeline`.

#### Headline Scenario Results ($N=20$ requests)

| Metric | DIY Config A/B (Unwired Fallback) | DIY Config C (Hand-Wired Fallback) | Polity4j Unified Pipeline | Parity / Winner |
| :--- | :--- | :--- | :--- | :--- |
| **Total Inbound Requests** | 20 | 20 | 20 | - |
| **User Requests Succeeded** | **5** (25.0%) | **20** (100.0%) | **20** (100.0%) | **Config C & Polity4j (100% Availability)** |
| **User Requests Failed** | **15** (75.0%) | **0** (0.0%) | **0** (0.0%) | **Config C & Polity4j (0 User Failures)** |
| **Calls Succeeded via Fallback** | **0** | **15** (requests 6–20) | **15** (requests 6–20) | **Config C & Polity4j** |
| **User-Facing Open-Breaker Exceptions**| **13** calls (requests 8–20) | **12** calls (requests 9–20) | **0** calls | **Polity4j (0 Wasted Calls)** |
| **Dollars Spent on Failed Calls** | **$0.0030** (3 failed calls) | **$0.0030** (3 failed calls) | **$0.0000** | **Polity4j ($0 Wasted Spend)** |
| **`unreliable-cheap-backend` Used** | **0** | **15** (Fallback 1) | **0** | **Config C (Cost-conscious Fallback)** |
| **`expensive-backend` Used** | **0** | **0** | **15** (Fallback 2) | **Polity4j** |

#### Key Insights from M1:
1. **Implementation Gap vs Structural Routing Flaw**: Explicitly wiring fallback across backends (**Config C**) eliminates user-facing failures (100% availability). However, **the DIY router remains completely blind to breaker state**, wasting 12 redundant execution attempts into an OPEN circuit breaker across requests 9–20. Polity4j short-circuits *before* backend invocation, recording **0 wasted calls**.
2. **Hidden Decorator Interactions (`Cache` + `CircuitBreaker`)**: In Config A/B, uncaught exceptions propagating out of `CircuitBreaker` cause `resilience4j-cache`'s `Cache.decorateSupplier` to re-execute the underlying supplier, silently doubling backend attempts on failure.
3. **Budget / Cache Ordering Sensitivity**: Placing Budget Guardrails in front of Cache (Budget-First) causes **phantom budget drain** (+100% false spend recorded for cached responses). Polity4j enforces correct execution ordering by design.
4. **Composition Accounting**:

| Metric | DIY Hand-Rolled Stack | Polity4j Unified Pipeline | Savings / Reduction |
| :--- | :--- | :--- | :--- |
| **Lines of Glue Code (LOC)** | **330 LOC** | **40 LOC** | **-87.9% LOC reduction** |
| **Distinct Dependencies** | **7 dependencies** | **1 dependency** | **7x dependency reduction** |
| **Separate Config Objects** | **5 config objects** | **1 pipeline config** | **5x configuration reduction** |

---

### Module 2: Agentic Loop Detection Evaluation (M2 Spike)
Accuracy study evaluating Polity4j's native `AgentLoopDetectorModule` against a 14-trace synthetic agent execution dataset ([m2_spike_dataset.json](file:///Users/shivenduamale/Documents/Personal%20Projects/polity4j-benchmarks/src/test/resources/m2_spike_dataset.json)):

- **Accuracy**: **64.29%** (9 / 14 traces correct)
- **Precision**: **90.0%**
- **Recall**: **55.6%**
- **False Positives (7.1%)**: Identical polling prompts ("Check job status") trip `STAGNATION_DETECTED` on step 4.
- **False Negatives (28.6%)**: Exact string matching (`prompt.equals(lastPrompt)`) misses cosmetic parameter changes (`req_id`), 2-state oscillations (A-B-A-B), and slow query drift.

---

## Running the Benchmarks

### Prerequisites
- **JDK 21** or later
- **Apache Maven 3.9+**

### Execute All Benchmark Tests

```bash
mvn test
```

### Execute Specific Benchmark Modules

```bash
# Run M0 Contract Tests
mvn test -Dtest=CircuitBreakerContractTest,CacheContractTest,FallbackContractTest

# Run M1 Composition-Cost Benchmark
mvn test -Dtest=M1CompositionCostBenchmarkTest

# Run M2 Agent Loop Detector Evaluation Spike
mvn test -Dtest=M2AgentLoopDetectorSpikeTest
```

---

## Generated Results & Artifacts

- **Machine-Readable Results**: [results/m1_composition_cost.json](file:///Users/shivenduamale/Documents/Personal%20Projects/polity4j-benchmarks/results/m1_composition_cost.json)
- **M1 Composition-Cost Detailed Report**: [scratch/M1_FINDINGS.md](file:///Users/shivenduamale/Documents/Personal%20Projects/polity4j-benchmarks/scratch/M1_FINDINGS.md)
- **M2 Agent Loop Detection Spike Report**: [scratch/M2_SPIKE_FINDINGS.md](file:///Users/shivenduamale/Documents/Personal%20Projects/polity4j-benchmarks/scratch/M2_SPIKE_FINDINGS.md)

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
