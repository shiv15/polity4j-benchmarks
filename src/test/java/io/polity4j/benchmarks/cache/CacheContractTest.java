package io.polity4j.benchmarks.cache;

import io.polity4j.benchmarks.harness.AttemptRecorder;
import io.polity4j.benchmarks.harness.FaultProfile;
import io.polity4j.benchmarks.harness.FaultType;
import io.polity4j.benchmarks.harness.InProcessFakeBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheContractTest {

    @Test
    @DisplayName("Polity4j: Repeated identical request is served from cache with zero additional backend calls")
    void testPolity4jCacheShortCircuitsBackend() {
        FaultProfile profile = FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS);
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        PolityCacheHarness harness = new PolityCacheHarness();

        String prompt = "What is the capital of France?";

        // Request 1: Cache miss -> calls backend
        String res1 = harness.execute(prompt, backend::get);
        assertThat(res1).isEqualTo("InProcess Success");
        assertThat(recorder.totalAttempts()).isEqualTo(1);
        assertThat(harness.hits()).isEqualTo(0);
        assertThat(harness.misses()).isEqualTo(1);

        // Request 2: Cache hit -> served from cache, zero backend calls
        String res2 = harness.execute(prompt, backend::get);
        assertThat(res2).isEqualTo("InProcess Success");
        assertThat(recorder.totalAttempts()).isEqualTo(1); // STILL 1!
        assertThat(harness.hits()).isEqualTo(1);
        assertThat(harness.misses()).isEqualTo(1);

        System.out.printf("[Polity4j Cache] Call 1 Backend Attempts: 1 | Call 2 Backend Attempts: 0 (Total Attempts: %d, Hits: %d, Misses: %d)%n",
                recorder.totalAttempts(), harness.hits(), harness.misses());
    }

    @Test
    @DisplayName("Resilience4j: Repeated identical request is served from JCache with zero additional backend calls")
    void testResilience4jCacheShortCircuitsBackend() {
        FaultProfile profile = FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS);
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        Resilience4jCacheHarness harness = new Resilience4jCacheHarness("testCacheR4j");

        String cacheKey = "prompt:capital-of-france";

        // Request 1: Cache miss -> calls backend
        String res1 = harness.execute(cacheKey, backend::get);
        assertThat(res1).isEqualTo("InProcess Success");
        assertThat(recorder.totalAttempts()).isEqualTo(1);

        // Request 2: Cache hit -> served from JCache, zero backend calls
        String res2 = harness.execute(cacheKey, backend::get);
        assertThat(res2).isEqualTo("InProcess Success");
        assertThat(recorder.totalAttempts()).isEqualTo(1); // STILL 1!

        System.out.printf("[Resilience4j Cache] Call 1 Backend Attempts: 1 | Call 2 Backend Attempts: 0 (Total Attempts: %d)%n",
                recorder.totalAttempts());
    }

    @Test
    @DisplayName("Parity: Both libraries produce identical attempt counts (1 total call for 2 requests)")
    void testCacheParity() {
        FaultProfile profileP = FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS);
        AttemptRecorder recorderP = new AttemptRecorder();
        InProcessFakeBackend backendP = new InProcessFakeBackend(profileP, recorderP);
        PolityCacheHarness harnessP = new PolityCacheHarness();

        FaultProfile profileR = FaultProfile.of(FaultType.SUCCESS, FaultType.SUCCESS);
        AttemptRecorder recorderR = new AttemptRecorder();
        InProcessFakeBackend backendR = new InProcessFakeBackend(profileR, recorderR);
        Resilience4jCacheHarness harnessR = new Resilience4jCacheHarness("parityCacheR4j");

        String key = "prompt:same-key";

        // Request 1
        harnessP.execute(key, backendP::get);
        harnessR.execute(key, backendR::get);
        assertThat(recorderP.totalAttempts()).isEqualTo(1);
        assertThat(recorderR.totalAttempts()).isEqualTo(1);

        // Request 2
        harnessP.execute(key, backendP::get);
        harnessR.execute(key, backendR::get);
        assertThat(recorderP.totalAttempts()).isEqualTo(1);
        assertThat(recorderR.totalAttempts()).isEqualTo(1);
    }
}
