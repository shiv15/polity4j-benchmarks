package io.polity4j.benchmarks.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.polity4j.benchmarks.harness.AttemptRecorder;
import io.polity4j.benchmarks.harness.FaultProfile;
import io.polity4j.benchmarks.harness.FaultType;
import io.polity4j.benchmarks.harness.InProcessFakeBackend;
import io.polity4j.core.exception.ModelUnavailableException;
import io.polity4j.core.exception.PolityException;
import io.polity4j.reliability.circuitbreaker.CircuitState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerContractTest {

    @Test
    @DisplayName("Polity4j: Sustained failure sequence trips breaker and short-circuits fast")
    void testPolity4jSustainedFailureTripsBreaker() {
        int failureThreshold = 5;
        int totalCallerCalls = 10;

        // Sustained failures: 10 consecutive RATE_LIMITED errors
        FaultProfile profile = FaultProfile.builder()
                .addFailures(FaultType.RATE_LIMITED, totalCallerCalls)
                .build();
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        PolityCircuitBreakerHarness harness = new PolityCircuitBreakerHarness(
                "openai", failureThreshold, Duration.ofSeconds(30));

        int backendCallsBeforeTrip = 0;
        int shortCircuitedCalls = 0;

        for (int i = 1; i <= totalCallerCalls; i++) {
            final int callIndex = i;
            try {
                harness.execute(backend::get);
            } catch (PolityException e) {
                if (e instanceof ModelUnavailableException && harness.state() == CircuitState.OPEN) {
                    shortCircuitedCalls++;
                } else {
                    backendCallsBeforeTrip++;
                }
            }
        }

        assertThat(harness.state()).isEqualTo(CircuitState.OPEN);
        assertThat(recorder.totalAttempts()).isEqualTo(failureThreshold);
        assertThat(backendCallsBeforeTrip).isEqualTo(failureThreshold);
        assertThat(shortCircuitedCalls).isEqualTo(totalCallerCalls - failureThreshold);

        System.out.printf("[Polity4j] Total Caller Calls: %d | Wasted Backend Calls Before Trip: %d | Short-Circuited Calls: %d%n",
                totalCallerCalls, recorder.totalAttempts(), shortCircuitedCalls);
    }

    @Test
    @DisplayName("Resilience4j: Sustained failure sequence trips breaker and short-circuits fast")
    void testResilience4jSustainedFailureTripsBreaker() {
        int failureThreshold = 5;
        int totalCallerCalls = 10;

        // Sustained failures: 10 consecutive RATE_LIMITED errors
        FaultProfile profile = FaultProfile.builder()
                .addFailures(FaultType.RATE_LIMITED, totalCallerCalls)
                .build();
        AttemptRecorder recorder = new AttemptRecorder();
        InProcessFakeBackend backend = new InProcessFakeBackend(profile, recorder);

        Resilience4jCircuitBreakerHarness harness = new Resilience4jCircuitBreakerHarness(
                "openai", failureThreshold, Duration.ofSeconds(30));

        int backendCallsBeforeTrip = 0;
        int shortCircuitedCalls = 0;

        for (int i = 1; i <= totalCallerCalls; i++) {
            try {
                harness.execute(backend::get);
            } catch (CallNotPermittedException e) {
                shortCircuitedCalls++;
            } catch (Exception e) {
                backendCallsBeforeTrip++;
            }
        }

        assertThat(harness.state()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        assertThat(recorder.totalAttempts()).isEqualTo(failureThreshold);
        assertThat(backendCallsBeforeTrip).isEqualTo(failureThreshold);
        assertThat(shortCircuitedCalls).isEqualTo(totalCallerCalls - failureThreshold);

        System.out.printf("[Resilience4j] Total Caller Calls: %d | Wasted Backend Calls Before Trip: %d | Short-Circuited Calls: %d%n",
                totalCallerCalls, recorder.totalAttempts(), shortCircuitedCalls);
    }

    @Test
    @DisplayName("Parity: Both libraries trip at identical attempt index under sustained failure window")
    void testParityUnderSustainedFailureWindow() {
        int nFailures = 4;
        int totalCalls = 8;

        FaultProfile profilePolity = FaultProfile.sustainedFailures(FaultType.OVERLOADED, nFailures, 4);
        AttemptRecorder recorderPolity = new AttemptRecorder();
        InProcessFakeBackend backendPolity = new InProcessFakeBackend(profilePolity, recorderPolity);
        PolityCircuitBreakerHarness harnessPolity = new PolityCircuitBreakerHarness(
                "openai", nFailures, Duration.ofSeconds(10));

        FaultProfile profileR4j = FaultProfile.sustainedFailures(FaultType.OVERLOADED, nFailures, 4);
        AttemptRecorder recorderR4j = new AttemptRecorder();
        InProcessFakeBackend backendR4j = new InProcessFakeBackend(profileR4j, recorderR4j);
        Resilience4jCircuitBreakerHarness harnessR4j = new Resilience4jCircuitBreakerHarness(
                "openai", nFailures, Duration.ofSeconds(10));

        for (int i = 0; i < totalCalls; i++) {
            try { harnessPolity.execute(backendPolity::get); } catch (Exception ignored) {}
            try { harnessR4j.execute(backendR4j::get); } catch (Exception ignored) {}
        }

        assertThat(recorderPolity.totalAttempts()).isEqualTo(nFailures);
        assertThat(recorderR4j.totalAttempts()).isEqualTo(nFailures);
        assertThat(harnessPolity.state()).isEqualTo(CircuitState.OPEN);
        assertThat(harnessR4j.state()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    }
}
