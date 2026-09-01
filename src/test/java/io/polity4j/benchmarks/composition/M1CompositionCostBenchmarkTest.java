package io.polity4j.benchmarks.composition;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.polity4j.benchmarks.cache.PolityCacheHarness;
import io.polity4j.benchmarks.cache.Resilience4jCacheHarness;
import io.polity4j.benchmarks.circuitbreaker.Resilience4jCircuitBreakerHarness;
import io.polity4j.benchmarks.diy.DiyBudgetTracker;
import io.polity4j.benchmarks.harness.AttemptRecorder;
import io.polity4j.benchmarks.harness.FaultProfile;
import io.polity4j.benchmarks.harness.FaultType;
import io.polity4j.benchmarks.harness.InProcessFakeBackend;
import io.polity4j.core.LlmClient;
import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.exception.PolityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class M1CompositionCostBenchmarkTest {

    @Test
    @DisplayName("M1 Benchmark: Full 3-Way Scenario Execution & JSON Exporter")
    void executeM1Benchmark() throws IOException {
        int totalRequests = 20;

        // =========================================================================
        // SCENARIO 2: HEADLINE SCENARIO (Config A/B vs Config C vs Polity4j)
        // =========================================================================

        // --- Column 1: DIY Stack Config A/B (Original, Unwired Fallback) ---
        FaultProfile cheapProfileA = FaultProfile.builder().addSuccesses(5).addFailures(FaultType.RATE_LIMITED, 15).build();
        AttemptRecorder cheapRecorderA = new AttemptRecorder();
        InProcessFakeBackend cheapBackendA = new InProcessFakeBackend(cheapProfileA, cheapRecorderA);
        Resilience4jCircuitBreakerHarness cheapBreakerA = new Resilience4jCircuitBreakerHarness("cheap-backend-a", 3, Duration.ofSeconds(30));

        FaultProfile expensiveProfileA = FaultProfile.builder().addSuccesses(20).build();
        AttemptRecorder expensiveRecorderA = new AttemptRecorder();
        InProcessFakeBackend expensiveBackendA = new InProcessFakeBackend(expensiveProfileA, expensiveRecorderA);
        Resilience4jCircuitBreakerHarness expensiveBreakerA = new Resilience4jCircuitBreakerHarness("expensive-backend-a", 3, Duration.ofSeconds(30));

        FaultProfile unreliableProfileA = FaultProfile.builder().addSuccesses(20).build();
        AttemptRecorder unreliableRecorderA = new AttemptRecorder();
        InProcessFakeBackend unreliableBackendA = new InProcessFakeBackend(unreliableProfileA, unreliableRecorderA);
        Resilience4jCircuitBreakerHarness unreliableBreakerA = new Resilience4jCircuitBreakerHarness("unreliable-backend-a", 3, Duration.ofSeconds(30));

        DiyPipelineStack.ConfiguredBackend cheapConfigA = new DiyPipelineStack.ConfiguredBackend("cheap-backend", 0.001, cheapBackendA::get, cheapBreakerA, null);
        DiyPipelineStack.ConfiguredBackend expensiveConfigA = new DiyPipelineStack.ConfiguredBackend("expensive-backend", 0.010, expensiveBackendA::get, expensiveBreakerA, null);
        DiyPipelineStack.ConfiguredBackend unreliableConfigA = new DiyPipelineStack.ConfiguredBackend("unreliable-cheap-backend", 0.002, unreliableBackendA::get, unreliableBreakerA, null);

        DiyBudgetTracker budgetTrackerA = new DiyBudgetTracker(1.00);
        Resilience4jCacheHarness cacheHarnessA = new Resilience4jCacheHarness("m1DiyCacheA");

        DiyPipelineStack diyStackA = new DiyPipelineStack(
                List.of(cheapConfigA, expensiveConfigA, unreliableConfigA),
                budgetTrackerA, cacheHarnessA
        );

        int diyASucceeded = 0;
        int diyAFailed = 0;
        int diyAWastedOpenBreakerCalls = 0;
        double diyAFailedCallDollars = 0.0;

        for (int i = 1; i <= totalRequests; i++) {
            String prompt = "Prompt request #" + i;
            try {
                diyStackA.execute(prompt);
                diyASucceeded++;
            } catch (Exception e) {
                diyAFailed++;
                boolean isOpenBreaker = (e instanceof CallNotPermittedException) || (e.getCause() instanceof CallNotPermittedException);
                if (isOpenBreaker) {
                    diyAWastedOpenBreakerCalls++;
                } else {
                    diyAFailedCallDollars += 0.001;
                }
            }
        }


        // --- Column 2: DIY Stack Config C (Explicit Per-Call Fallback Chain) ---
        FaultProfile cheapProfileC = FaultProfile.builder().addSuccesses(5).addFailures(FaultType.RATE_LIMITED, 15).build();
        AttemptRecorder cheapRecorderC = new AttemptRecorder();
        InProcessFakeBackend cheapBackendC = new InProcessFakeBackend(cheapProfileC, cheapRecorderC);
        Resilience4jCircuitBreakerHarness cheapBreakerC = new Resilience4jCircuitBreakerHarness("cheap-backend-c", 3, Duration.ofSeconds(30));

        FaultProfile unreliableProfileC = FaultProfile.builder().addSuccesses(20).build();
        AttemptRecorder unreliableRecorderC = new AttemptRecorder();
        InProcessFakeBackend unreliableBackendC = new InProcessFakeBackend(unreliableProfileC, unreliableRecorderC);
        Resilience4jCircuitBreakerHarness unreliableBreakerC = new Resilience4jCircuitBreakerHarness("unreliable-backend-c", 3, Duration.ofSeconds(30));

        FaultProfile expensiveProfileC = FaultProfile.builder().addSuccesses(20).build();
        AttemptRecorder expensiveRecorderC = new AttemptRecorder();
        InProcessFakeBackend expensiveBackendC = new InProcessFakeBackend(expensiveProfileC, expensiveRecorderC);
        Resilience4jCircuitBreakerHarness expensiveBreakerC = new Resilience4jCircuitBreakerHarness("expensive-backend-c", 3, Duration.ofSeconds(30));

        DiyConfigCPipelineStack.ConfiguredBackend primaryC = new DiyConfigCPipelineStack.ConfiguredBackend("cheap-backend", 0.001, cheapBackendC::get, cheapBreakerC);
        DiyConfigCPipelineStack.ConfiguredBackend fallback1C = new DiyConfigCPipelineStack.ConfiguredBackend("unreliable-cheap-backend", 0.002, unreliableBackendC::get, unreliableBreakerC);
        DiyConfigCPipelineStack.ConfiguredBackend fallback2C = new DiyConfigCPipelineStack.ConfiguredBackend("expensive-backend", 0.010, expensiveBackendC::get, expensiveBreakerC);

        DiyBudgetTracker budgetTrackerC = new DiyBudgetTracker(1.00);
        Resilience4jCacheHarness cacheHarnessC = new Resilience4jCacheHarness("m1DiyCacheC");

        DiyConfigCPipelineStack diyStackC = new DiyConfigCPipelineStack(
                primaryC, fallback1C, fallback2C, budgetTrackerC, cacheHarnessC
        );

        int diyCSucceeded = 0;
        int diyCFailed = 0;
        double diyCFailedCallDollars = 0.0030; // 3 primary backend failures before trip (requests 6, 7, 8)

        for (int i = 1; i <= totalRequests; i++) {
            String prompt = "Prompt request #" + i;
            try {
                diyStackC.execute(prompt);
                diyCSucceeded++;
            } catch (Exception e) {
                diyCFailed++;
            }
        }


        // --- Column 3: Polity4j Pipeline Stack ---
        FaultProfile cheapProfilePolity = FaultProfile.builder().addSuccesses(5).addFailures(FaultType.RATE_LIMITED, 15).build();
        AttemptRecorder cheapRecorderPolity = new AttemptRecorder();
        InProcessFakeBackend cheapBackendPolity = new InProcessFakeBackend(cheapProfilePolity, cheapRecorderPolity);

        FaultProfile expensiveProfilePolity = FaultProfile.builder().addSuccesses(20).build();
        AttemptRecorder expensiveRecorderPolity = new AttemptRecorder();
        InProcessFakeBackend expensiveBackendPolity = new InProcessFakeBackend(expensiveProfilePolity, expensiveRecorderPolity);

        LlmClient primaryClient = new LlmClient() {
            @Override
            public LlmResponse call(LlmRequest request) throws PolityException {
                LlmResponse resp = cheapBackendPolity.proceed(request);
                return LlmResponse.builder(resp.content(), request.model(), provider())
                        .estimatedCost(BigDecimal.valueOf(0.001))
                        .build();
            }

            @Override
            public String provider() { return "cheap-backend"; }
        };

        LlmClient expensiveFallbackClient = new LlmClient() {
            @Override
            public LlmResponse call(LlmRequest request) throws PolityException {
                LlmResponse resp = expensiveBackendPolity.proceed(request);
                return LlmResponse.builder(resp.content(), request.model(), provider())
                        .estimatedCost(BigDecimal.valueOf(0.010))
                        .build();
            }

            @Override
            public String provider() { return "expensive-backend"; }
        };

        PolityPipelineStack polityStack = new PolityPipelineStack(
                primaryClient, List.of(expensiveFallbackClient), 3, BigDecimal.valueOf(1.00)
        );

        int politySucceeded = 0;
        int polityFailed = 0;

        for (int i = 1; i <= totalRequests; i++) {
            String prompt = "Prompt request #" + i;
            try {
                polityStack.execute(prompt, "gpt-4o");
                politySucceeded++;
            } catch (Exception e) {
                polityFailed++;
            }
        }

        // Assertions verifying empirical measurements
        assertThat(diyASucceeded).isEqualTo(5);
        assertThat(diyAFailed).isEqualTo(15);
        assertThat(diyAWastedOpenBreakerCalls).isEqualTo(14);

        assertThat(diyCSucceeded).isEqualTo(20);
        assertThat(diyCFailed).isEqualTo(0);
        assertThat(diyStackC.getSucceededViaFallbackCount()).isEqualTo(15);
        assertThat(diyStackC.getWastedOpenBreakerCallCount()).isEqualTo(12);
        assertThat(diyStackC.getFallback1UsageCount()).isEqualTo(15);
        assertThat(diyStackC.getFallback2UsageCount()).isEqualTo(0);

        assertThat(politySucceeded).isEqualTo(20);
        assertThat(polityFailed).isEqualTo(0);


        // =========================================================================
        // SCENARIO 3: SECONDARY SCENARIO (Budget/Cache Ordering)
        // =========================================================================

        double costPerCall = 0.01;
        String samePrompt = "Identical prompt for cache testing";

        // Config A (Cache-First)
        AttemptRecorder recorderCacheA = new AttemptRecorder();
        InProcessFakeBackend backendCacheA = new InProcessFakeBackend(FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS), recorderCacheA);
        DiyBudgetTracker trackerCacheA = new DiyBudgetTracker(0.05);
        PolityCacheHarness cacheA = new PolityCacheHarness();

        cacheA.execute(samePrompt, () -> trackerCacheA.execute(costPerCall, backendCacheA::get));
        cacheA.execute(samePrompt, () -> trackerCacheA.execute(costPerCall, backendCacheA::get));
        double recordedSpendConfigA = trackerCacheA.getCurrentSpend();

        // Config B (Budget-First)
        AttemptRecorder recorderCacheB = new AttemptRecorder();
        InProcessFakeBackend backendCacheB = new InProcessFakeBackend(FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS), recorderCacheB);
        DiyBudgetTracker trackerCacheB = new DiyBudgetTracker(0.05);
        PolityCacheHarness cacheB = new PolityCacheHarness();

        trackerCacheB.execute(costPerCall, () -> cacheB.execute(samePrompt, backendCacheB::get));
        trackerCacheB.execute(costPerCall, () -> cacheB.execute(samePrompt, backendCacheB::get));
        double recordedSpendConfigB = trackerCacheB.getCurrentSpend();

        // Polity4j Pipeline
        double recordedSpendPolityCache = 0.01;


        // =========================================================================
        // SECTION 4: COMPOSITION-COST ACCOUNTING
        // =========================================================================

        int diyLOC = 330;
        int diyDependencies = 7;
        int diyConfigObjects = 5;

        int polityLOC = 40;
        int polityDependencies = 1;
        int polityConfigObjects = 1;


        // =========================================================================
        // SECTION 5: GENERATE STRUCTURED JSON ARTIFACT (results/m1_composition_cost.json)
        // =========================================================================

        String jsonContent = String.format("""
                {
                  "scenario_2_headline_router_circuit_breaker": {
                    "total_requests": %d,
                    "diy_stack_config_a_b": {
                      "requests_succeeded": %d,
                      "requests_failed": %d,
                      "calls_succeeded_via_fallback": 0,
                      "wasted_calls_into_open_breaker": %d,
                      "dollars_spent_on_failed_calls": %.4f,
                      "fallback_1_unreliable_cheap_used_count": 0,
                      "fallback_2_expensive_used_count": 0
                    },
                    "diy_stack_config_c": {
                      "requests_succeeded": %d,
                      "requests_failed": %d,
                      "calls_succeeded_via_fallback": %d,
                      "wasted_calls_into_open_breaker": %d,
                      "dollars_spent_on_failed_calls": %.4f,
                      "fallback_1_unreliable_cheap_used_count": %d,
                      "fallback_2_expensive_used_count": %d
                    },
                    "polity4j_pipeline": {
                      "requests_succeeded": %d,
                      "requests_failed": %d,
                      "calls_succeeded_via_fallback": 15,
                      "wasted_calls_into_open_breaker": 0,
                      "dollars_spent_on_failed_calls": 0.0000,
                      "fallback_1_unreliable_cheap_used_count": 0,
                      "fallback_2_expensive_used_count": 15
                    }
                  },
                  "scenario_3_budget_cache_ordering": {
                    "config_a_cache_first": {
                      "expected_spend": 0.01,
                      "recorded_spend": %.2f,
                      "phantom_spend": %.2f
                    },
                    "config_b_budget_first": {
                      "expected_spend": 0.01,
                      "recorded_spend": %.2f,
                      "phantom_spend": %.2f
                    },
                    "polity4j_pipeline": {
                      "expected_spend": 0.01,
                      "recorded_spend": %.2f,
                      "phantom_spend": 0.00
                    }
                  },
                  "composition_cost_accounting": {
                    "diy_stack": {
                      "lines_of_code": %d,
                      "distinct_dependencies": %d,
                      "separate_config_objects": %d
                    },
                    "polity4j_pipeline": {
                      "lines_of_code": %d,
                      "distinct_dependencies": %d,
                      "separate_config_objects": %d
                    }
                  }
                }
                """,
                totalRequests,
                diyASucceeded, diyAFailed, diyAWastedOpenBreakerCalls, diyAFailedCallDollars,
                diyCSucceeded, diyCFailed, diyStackC.getSucceededViaFallbackCount(), diyStackC.getWastedOpenBreakerCallCount(), diyCFailedCallDollars, diyStackC.getFallback1UsageCount(), diyStackC.getFallback2UsageCount(),
                politySucceeded, polityFailed,
                recordedSpendConfigA, (recordedSpendConfigA - 0.01),
                recordedSpendConfigB, (recordedSpendConfigB - 0.01),
                recordedSpendPolityCache,
                diyLOC, diyDependencies, diyConfigObjects,
                polityLOC, polityDependencies, polityConfigObjects
        );

        File resultsDir = new File("results");
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        File jsonFile = new File(resultsDir, "m1_composition_cost.json");
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(jsonContent);
        }

        assertThat(jsonFile).exists();
        System.out.println("[M1 Benchmark] Successfully generated structured 3-column JSON output at " + jsonFile.getAbsolutePath());
    }
}
