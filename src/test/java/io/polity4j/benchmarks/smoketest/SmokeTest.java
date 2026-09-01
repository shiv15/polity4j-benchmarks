package io.polity4j.benchmarks.smoketest;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.polity4j.benchmarks.circuitbreaker.Resilience4jCircuitBreakerHarness;
import io.polity4j.benchmarks.diy.DiyBudgetTracker;
import io.polity4j.benchmarks.diy.DiyRouter;
import io.polity4j.benchmarks.harness.AttemptRecorder;
import io.polity4j.benchmarks.harness.FaultProfile;
import io.polity4j.benchmarks.harness.FaultType;
import io.polity4j.benchmarks.harness.InProcessFakeBackend;
import io.polity4j.benchmarks.cache.PolityCacheHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmokeTest {

    @Test
    @DisplayName("Smoke Test (a): Wiring Cache in front of DiyBudgetTracker vs behind DiyBudgetTracker")
    void smokeTestCacheAndBudgetTrackerWiring() {
        double costPerCall = 0.01;
        String prompt = "Capital of France?";

        // --- Ordering 1: Cache IN FRONT of DiyBudgetTracker (Client -> Cache -> BudgetTracker -> Backend) ---
        AttemptRecorder recorder1 = new AttemptRecorder();
        InProcessFakeBackend backend1 = new InProcessFakeBackend(FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS), recorder1);
        DiyBudgetTracker budgetTracker1 = new DiyBudgetTracker(0.05);
        PolityCacheHarness cacheHarness1 = new PolityCacheHarness();

        // Call 1
        String res1a = cacheHarness1.execute(prompt, () -> budgetTracker1.execute(costPerCall, backend1::get));
        assertThat(res1a).isEqualTo("InProcess Success");
        assertThat(recorder1.totalAttempts()).isEqualTo(1);
        assertThat(budgetTracker1.getCurrentSpend()).isEqualTo(0.01);

        // Call 2 (Cache Hit)
        String res1b = cacheHarness1.execute(prompt, () -> budgetTracker1.execute(costPerCall, backend1::get));
        assertThat(res1b).isEqualTo("InProcess Success");
        assertThat(recorder1.totalAttempts()).isEqualTo(1);
        // Because Cache sat IN FRONT of BudgetTracker, BudgetTracker was NOT invoked on Call 2!
        int trackerCallsWhenCacheInFront = (int) Math.round(budgetTracker1.getCurrentSpend() / costPerCall);
        assertThat(trackerCallsWhenCacheInFront).isEqualTo(1);

        // --- Ordering 2: DiyBudgetTracker IN FRONT of Cache (Client -> BudgetTracker -> Cache -> Backend) ---
        AttemptRecorder recorder2 = new AttemptRecorder();
        InProcessFakeBackend backend2 = new InProcessFakeBackend(FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS), recorder2);
        DiyBudgetTracker budgetTracker2 = new DiyBudgetTracker(0.05);
        PolityCacheHarness cacheHarness2 = new PolityCacheHarness();

        // Call 1
        String res2a = budgetTracker2.execute(costPerCall, () -> cacheHarness2.execute(prompt, backend2::get));
        assertThat(res2a).isEqualTo("InProcess Success");
        assertThat(recorder2.totalAttempts()).isEqualTo(1);
        assertThat(budgetTracker2.getCurrentSpend()).isEqualTo(0.01);

        // Call 2 (Cache Hit inside CacheHarness, but BudgetTracker executed first!)
        String res2b = budgetTracker2.execute(costPerCall, () -> cacheHarness2.execute(prompt, backend2::get));
        assertThat(res2b).isEqualTo("InProcess Success");
        assertThat(recorder2.totalAttempts()).isEqualTo(1); // Backend only called once!
        int trackerCallsWhenTrackerInFront = (int) Math.round(budgetTracker2.getCurrentSpend() / costPerCall);
        assertThat(trackerCallsWhenTrackerInFront).isEqualTo(2); // BUT BudgetTracker counted 2 calls!

        System.out.printf("[Smoke Test a] Cache-in-Front Spend Count: %d | BudgetTracker-in-Front Spend Count: %d (Actual Backend Calls: 1)%n",
                trackerCallsWhenCacheInFront, trackerCallsWhenTrackerInFront);
    }

    @Test
    @DisplayName("Smoke Test (b): DiyRouter routes to cheapest backend even when its CircuitBreaker is OPEN")
    void smokeTestRouterUnawareOfCircuitBreaker() {
        int failureThreshold = 3;

        // Backend A: Cheap ($0.001 per call), protected by Resilience4j CircuitBreaker
        FaultProfile cheapFailProfile = FaultProfile.sustainedFailures(FaultType.RATE_LIMITED, 5, 0);
        AttemptRecorder cheapRecorder = new AttemptRecorder();
        InProcessFakeBackend cheapBackend = new InProcessFakeBackend(cheapFailProfile, cheapRecorder);
        Resilience4jCircuitBreakerHarness cheapBreaker = new Resilience4jCircuitBreakerHarness(
                "cheap-backend", failureThreshold, Duration.ofSeconds(30));

        // Trip cheap-backend's circuit breaker OPEN
        for (int i = 0; i < failureThreshold; i++) {
            try {
                cheapBreaker.execute(cheapBackend::get);
            } catch (Exception ignored) {}
        }
        assertThat(cheapBreaker.state()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        // Backend B: Expensive ($0.010 per call), healthy
        FaultProfile expensiveProfile = FaultProfile.of(FaultType.SUCCESS);
        AttemptRecorder expensiveRecorder = new AttemptRecorder();
        InProcessFakeBackend expensiveBackend = new InProcessFakeBackend(expensiveProfile, expensiveRecorder);

        // DiyRouter configured with cheap vs expensive backends
        DiyRouter.Backend<String> cheapRoute = new DiyRouter.Backend<>(
                "cheap-backend", 0.001, () -> cheapBreaker.execute(cheapBackend::get));
        DiyRouter.Backend<String> expensiveRoute = new DiyRouter.Backend<>(
                "expensive-backend", 0.010, expensiveBackend::get);

        DiyRouter<String> router = new DiyRouter<>(List.of(cheapRoute, expensiveRoute));

        // 1. Confirm router selects cheap-backend based purely on cost
        assertThat(router.getCheapestBackendName()).isEqualTo("cheap-backend");

        // 2. Confirm executing router throws CallNotPermittedException because cheap-backend breaker is OPEN!
        assertThatThrownBy(router::routeAndExecute)
                .isInstanceOf(CallNotPermittedException.class);

        // Confirm expensive backend was never tried by DiyRouter
        assertThat(expensiveRecorder.totalAttempts()).isEqualTo(0);

        System.out.printf("[Smoke Test b] Selected Backend: '%s' | Breaker State: OPEN | Threw CallNotPermittedException: true | Backup Attempts: %d%n",
                router.getCheapestBackendName(), expensiveRecorder.totalAttempts());
    }
}
