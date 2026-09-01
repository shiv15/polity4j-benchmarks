package io.polity4j.benchmarks.fallback;

import io.polity4j.benchmarks.harness.AttemptRecorder;
import io.polity4j.benchmarks.harness.FaultProfile;
import io.polity4j.benchmarks.harness.FaultType;
import io.polity4j.benchmarks.harness.InProcessFakeBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackContractTest {

    @Test
    @DisplayName("Polity4j: Exhaust retries, return fallback value instead of throwing, log exact attempts")
    void testPolity4jFallbackOnExhaustedRetries() {
        int maxAttempts = 3;
        String fallbackValue = "Polity4j Fallback Success";

        // Fault profile where all attempts fail
        FaultProfile profile = FaultProfile.builder()
                .addFailures(FaultType.RATE_LIMITED, 5)
                .build();
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        PolityFallbackHarness harness = new PolityFallbackHarness(fallbackValue, maxAttempts);

        String result = harness.execute(backend::get);

        assertThat(result).isEqualTo(fallbackValue);
        assertThat(recorder.totalAttempts()).isEqualTo(maxAttempts);

        System.out.printf("[Polity4j Fallback] Returned: '%s' | Total Primary Backend Attempts Before Fallback: %d%n",
                result, recorder.totalAttempts());
    }

    @Test
    @DisplayName("Resilience4j: Exhaust retries, return fallback value instead of throwing, log exact attempts")
    void testResilience4jFallbackOnExhaustedRetries() {
        int maxAttempts = 3;
        String fallbackValue = "Resilience4j Fallback Success";

        // Fault profile where all attempts fail
        FaultProfile profile = FaultProfile.builder()
                .addFailures(FaultType.RATE_LIMITED, 5)
                .build();
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        Resilience4jFallbackHarness harness = new Resilience4jFallbackHarness(fallbackValue, maxAttempts);

        String result = harness.execute(backend::get);

        assertThat(result).isEqualTo(fallbackValue);
        assertThat(recorder.totalAttempts()).isEqualTo(maxAttempts);

        System.out.printf("[Resilience4j Fallback] Returned: '%s' | Total Primary Backend Attempts Before Fallback: %d%n",
                result, recorder.totalAttempts());
    }

    @Test
    @DisplayName("Parity: Both libraries make identical primary attempt counts before invoking fallback")
    void testFallbackAttemptParity() {
        int maxAttempts = 4;

        FaultProfile profilePolity = FaultProfile.builder().addFailures(FaultType.RATE_LIMITED, 6).build();
        AttemptRecorder recorderPolity = new AttemptRecorder();
        InProcessFakeBackend backendPolity = new InProcessFakeBackend(profilePolity, recorderPolity);
        PolityFallbackHarness harnessPolity = new PolityFallbackHarness("Fallback", maxAttempts);

        FaultProfile profileR4j = FaultProfile.builder().addFailures(FaultType.RATE_LIMITED, 6).build();
        AttemptRecorder recorderR4j = new AttemptRecorder();
        InProcessFakeBackend backendR4j = new InProcessFakeBackend(profileR4j, recorderR4j);
        Resilience4jFallbackHarness harnessR4j = new Resilience4jFallbackHarness("Fallback", maxAttempts);

        String resultPolity = harnessPolity.execute(backendPolity::get);
        String resultR4j = harnessR4j.execute(backendR4j::get);

        assertThat(resultPolity).isEqualTo("Fallback");
        assertThat(resultR4j).isEqualTo("Fallback");
        assertThat(recorderPolity.totalAttempts()).isEqualTo(maxAttempts);
        assertThat(recorderR4j.totalAttempts()).isEqualTo(maxAttempts);
    }
}
