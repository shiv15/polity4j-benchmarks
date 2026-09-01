package io.polity4j.benchmarks.harness;

/**
 * Common fault vocabulary for fault injection tests across resilience libraries.
 */
public enum FaultType {
    RATE_LIMITED,
    OVERLOADED,
    TRANSIENT_5XX,
    PERMANENT_4XX,
    MALFORMED_RESPONSE,
    SUCCESS
}
