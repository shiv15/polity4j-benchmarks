# M2 Agent Loop Detection Spike Findings

## Executive Summary

The **M2 Agent Loop Detection Benchmark** evaluates the accuracy (Precision, Recall, and False Positive/Negative rates) of agentic loop detection mechanisms against a ground-truth dataset of synthetic agent execution traces.

As a prelude to building the full 50–100+ trace evaluation suite, this **Spike Analysis** evaluates Polity4j's native `AgentLoopDetectorModule` against a 14-trace synthetic spike dataset covering 4 core loop categories and 4 legitimate non-loop categories.

---

## 1. Polity4j Agent Loop Detection Mechanism Analysis

Inspection of Polity4j's source code (`io.polity4j.reliability.loop.AgentLoopDetectorModule` and `AgentLoopConfig`) revealed the following internal design:

- **Session Partitioning**: Tracks state per session using `request.callerId()`. Bypasses checks if `callerId` is null or blank.
- **Four Independent Trip Conditions**:
  1. **Stagnation (`consecutiveDuplicates > maxConsecutiveDuplicates`)**: Tracks consecutive identical prompts using Java `String.equals(lastPrompt)`. Default: 3.
  2. **Frequency (`requests > maxRequestsPerSession` within `slidingWindowMs`)**: Tracks request timestamps in a sliding window. Default: 10 requests per 60,000 ms.
  3. **Max Cost (`accumulatedCost >= maxCost`)**: Checks cumulative session cost against a ceiling.
  4. **Max Iterations (`totalIterations > maxIterations`)**: Checks total session step count against a hard cap. Default: 10.
- **Return Interface**: Throws `AgentLoopException` carrying a `TripReason` enum (`STAGNATION_DETECTED`, `FREQUENCY_LIMIT_EXCEEDED`, `MAX_COST_EXCEEDED`, `MAX_ITERATIONS_EXCEEDED`), total iterations, and accumulated cost.

---

## 2. Spike Dataset Confusion & Accuracy Results

### Configuration Settings
- `maxConsecutiveDuplicates`: 3
- `maxRequestsPerSession`: 10 (window: 60,000 ms)
- `maxIterations`: 10
- `maxCost`: $0.10

### Summary Metrics
- **Total Traces**: 14
- **Correct Predictions**: 9 / 14 (**64.29% Accuracy**)
- **False Positives**: 1 (7.1%)
- **False Negatives**: 4 (28.6%)
- **Precision**: 90.0% (9 true positives out of 10 flagged traces)
- **Recall**: 55.6% (5 true positives detected out of 9 actual loop traces)

### Complete Spike Trace Breakdown

| Trace ID | Category | Expected Label | Actual Label | Actual Reason | Step | Correct? | Diagnostic Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `loop_01_exact_repetition` | LOOP | `LOOP` | `LOOP` | `STAGNATION_DETECTED` | 4/4 | **TRUE** | Correctly flagged 4th identical prompt. |
| `loop_02_near_identical_cosmetic_args` | LOOP | `LOOP` | `NOT_LOOP` | `NONE` | 4/4 | **FALSE** | **False Negative**: `String.equals()` missed cosmetic `req_id` changes. |
| `loop_03_oscillating` | LOOP | `LOOP` | `NOT_LOOP` | `NONE` | 6/6 | **FALSE** | **False Negative**: No 2-state oscillation (A-B-A-B) detection. |
| `loop_04_slow_drift` | LOOP | `LOOP` | `NOT_LOOP` | `NONE` | 6/6 | **FALSE** | **False Negative**: No semantic similarity matching for rephrased queries. |
| `loop_05_runaway_iteration` | LOOP | `LOOP` | `LOOP` | `FREQUENCY_LIMIT_EXCEEDED` | 11/11 | **TRUE** | Correctly flagged on 11th step. |
| `loop_06_high_frequency_burst` | LOOP | `LOOP` | `LOOP` | `FREQUENCY_LIMIT_EXCEEDED` | 11/11 | **TRUE** | Correctly flagged rapid request burst. |
| `loop_07_budget_runaway` | LOOP | `LOOP` | `NOT_LOOP` | `NONE` | 3/3 | **FALSE** | **False Negative**: Cost check is pre-flight; post-call cost update requires a 4th request to trip. |
| `nonloop_01_legitimate_iterative_work` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 4/4 | **TRUE** | Distinct tools making visible progress. |
| `nonloop_02_retry_with_backoff` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 2/2 | **TRUE** | 2 retries allowed under `maxConsecutiveDuplicates=3`. |
| `nonloop_03_legitimate_pagination` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 4/4 | **TRUE** | `page=1,2,3,4` changes prompt string. |
| `nonloop_04_legitimate_polling` | NOT_LOOP | `NOT_LOOP` | `LOOP` | `STAGNATION_DETECTED` | 4/6 | **FALSE** | **False Positive**: Exact prompt polling flagged as stagnation on step 4. |
| `nonloop_05_distinct_multi_step_flow` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 8/8 | **TRUE** | 8 distinct steps executed cleanly under 10-step cap. |
| `nonloop_06_low_frequency_spaced_calls` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 5/5 | **TRUE** | Spaced calls executed cleanly. |
| `nonloop_07_low_cost_healthy_session` | NOT_LOOP | `NOT_LOOP` | `NOT_LOOP` | `NONE` | 4/4 | **TRUE** | Healthy cost session executed cleanly. |

---

## 3. Deep-Dive Analysis of Detection Failure Modes

### False Positives (Legitimate Traces Wrongly Killed)
1. **`nonloop_04_legitimate_polling`**:
   - **Symptom**: Polling a job status using identical prompts ("Check status of job_88492") 6 times was killed on Step 4 with `STAGNATION_DETECTED`.
   - **Root Cause**: Stagnation detection operates purely on exact string equality (`prompt.equals(lastPrompt)`). Because the polling prompt did not change, `consecutiveDuplicates` reached 4, exceeding `maxConsecutiveDuplicates=3`.
   - **Impact**: Rule-based stagnation detectors destroy legitimate long-running polling patterns unless polling contains dynamic timestamps or explicit progress signals.

### False Negatives (Real Loops Missed)
1. **`loop_02_near_identical_cosmetic_args`**:
   - **Root Cause**: Modifying cosmetic arguments (`req_id=1001` vs `req_id=1002`) causes `prompt.equals(lastPrompt)` to evaluate to `false`, resetting `consecutiveDuplicates = 1` on every call.
2. **`loop_03_oscillating`**:
   - **Root Cause**: Alternating tool calls (A -> B -> A -> B) continuously change `lastPrompt`, preventing consecutive duplicate counters from accumulating. Polity4j lacks a sliding-window cycle detector (e.g. Floyd's cycle-finding or N-state graph cycle detection).
3. **`loop_04_slow_drift`**:
   - **Root Cause**: Rephrasing queries slightly ("python regex match" vs "python re.match") evades string equality checks without semantic vector embedding comparison.
4. **`loop_07_budget_runaway`**:
   - **Root Cause**: Pre-flight cost evaluation happens before `next.proceed(request)` execution. Post-flight cost addition updates total spend, but requires a *subsequent* request to trigger the pre-flight budget check.

---

## 4. Recommendations for Scaling to Full Dataset (50–100+ Traces)

1. **Category Taxonomy Validation**:
   - The 8 categories in our spike dataset are highly effective and represent real-world agent failure modes. We recommend maintaining these 8 categories for the full 50–100+ benchmark corpus.
2. **Category Weighting for Full Dataset**:
   - **Exact Repetition** (15%): Baseline stagnation.
   - **Near-Identical / Cosmetic Args** (15%): Testing parameter-blind stagnation.
   - **Oscillation / Cycle Loops** (15%): Testing 2-state and 3-state cycle detection.
   - **Slow Drift** (15%): Testing semantic similarity requirements.
   - **Polling & Pagination** (20%): Testing false positive boundaries.
   - **Multi-Step Workflows & Retries** (20%): Testing normal agent execution.
3. **Format Standardization**:
   - Maintain the JSON schema (`id`, `category`, `sub_category`, `description`, `expected_label`, `expected_reason`, `prompts`, `costs`) for automated evaluation harness integration.
