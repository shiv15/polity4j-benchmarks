package io.polity4j.benchmarks.harness;

import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.PipelineChain;
import io.polity4j.core.exception.ModelUnavailableException;
import io.polity4j.core.exception.OverloadedException;
import io.polity4j.core.exception.RateLimitException;
import io.polity4j.core.exception.ResponseValidationException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * In-process fake backend driven by a FaultProfile and logging to an AttemptRecorder.
 * Implements both Polity4j PipelineChain and java.util.function.Supplier for cross-library compatibility.
 */
public final class InProcessFakeBackend implements PipelineChain, Supplier<String> {

    private final FaultProfile faultProfile;
    private final AttemptRecorder recorder;
    private final AtomicInteger attemptCount = new AtomicInteger(0);

    public InProcessFakeBackend(FaultProfile faultProfile, AttemptRecorder recorder) {
        this.faultProfile = faultProfile;
        this.recorder = recorder;
    }

    public int getAttemptCount() {
        return attemptCount.get();
    }

    @Override
    public LlmResponse proceed(LlmRequest request) {
        int attempt = attemptCount.incrementAndGet();
        FaultType fault = faultProfile.getFaultForAttempt(attempt);
        boolean isTerminal = (fault == FaultType.SUCCESS);

        recorder.recordAttempt(attempt, fault, isTerminal);

        switch (fault) {
            case RATE_LIMITED:
                throw new RateLimitException("openai", 0);
            case OVERLOADED:
                throw new OverloadedException("openai");
            case TRANSIENT_5XX:
                throw new ModelUnavailableException("gpt-4o", "openai");
            case PERMANENT_4XX:
                throw new RuntimeException("Permanent 4XX error: Bad Request");
            case MALFORMED_RESPONSE:
                throw new ResponseValidationException("Malformed response payload", "Truncated JSON payload");
            case SUCCESS:
            default:
                return LlmResponse.builder("InProcess Success", "gpt-4o", "openai").build();
        }
    }

    @Override
    public String get() {
        LlmResponse response = proceed(LlmRequest.builder("dummy prompt", "gpt-4o").build());
        return response.content();
    }
}
