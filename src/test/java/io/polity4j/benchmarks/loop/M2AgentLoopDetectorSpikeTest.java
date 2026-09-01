package io.polity4j.benchmarks.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.polity4j.core.LlmClient;
import io.polity4j.core.LlmRequest;
import io.polity4j.core.LlmResponse;
import io.polity4j.core.PipelineChain;
import io.polity4j.core.exception.AgentLoopException;
import io.polity4j.core.exception.PolityException;
import io.polity4j.reliability.loop.AgentLoopConfig;
import io.polity4j.reliability.loop.AgentLoopDetectorModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class M2AgentLoopDetectorSpikeTest {

    public record SpikeTrace(
            String id,
            String category,
            String subCategory,
            String description,
            String expectedLabel,
            String expectedReason,
            List<String> prompts,
            List<Double> costs
    ) {}

    public record TraceResult(
            String id,
            String category,
            String subCategory,
            String expectedLabel,
            String actualLabel,
            String expectedReason,
            String actualReason,
            int trippedAtStep,
            int totalSteps,
            boolean isCorrect,
            String note
    ) {}

    @Test
    @DisplayName("M2 Spike: Polity4j AgentLoopDetectorModule Accuracy Against Synthetic Traces")
    void executeSpikeBenchmark() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getClassLoader().getResourceAsStream("m2_spike_dataset.json");
        assertThat(is).isNotNull();

        List<Map<String, Object>> rawTraces = mapper.readValue(is, new TypeReference<>() {});
        List<TraceResult> results = new ArrayList<>();

        AgentLoopConfig config = AgentLoopConfig.builder()
                .maxConsecutiveDuplicates(3)
                .maxRequestsPerSession(10)
                .slidingWindowMs(60_000L)
                .maxIterations(10)
                .maxCost("0.10")
                .build();

        int correctCount = 0;
        int falsePositiveCount = 0;
        int falseNegativeCount = 0;

        System.out.println("=== M2 AGENT LOOP DETECTOR SPIKE RESULTS ===");

        for (Map<String, Object> map : rawTraces) {
            String id = (String) map.get("id");
            String category = (String) map.get("category");
            String subCategory = (String) map.get("sub_category");
            String expectedLabel = (String) map.get("expected_label");
            String expectedReason = (String) map.get("expected_reason");

            @SuppressWarnings("unchecked")
            List<String> prompts = (List<String>) map.get("prompts");
            @SuppressWarnings("unchecked")
            List<Double> rawCosts = (List<Double>) map.get("costs");

            AgentLoopDetectorModule detector = new AgentLoopDetectorModule(config);
            String callerId = "session_" + id;

            boolean tripped = false;
            String actualReason = "NONE";
            int trippedAtStep = -1;

            for (int i = 0; i < prompts.size(); i++) {
                String prompt = prompts.get(i);
                final int stepNumber = i + 1;
                final BigDecimal stepCost = (rawCosts != null && i < rawCosts.size())
                        ? BigDecimal.valueOf(rawCosts.get(i))
                        : BigDecimal.valueOf(0.001);

                LlmRequest request = LlmRequest.builder(prompt, "gpt-4o")
                        .callerId(callerId)
                        .build();

                PipelineChain chain = req -> LlmResponse.builder("Success response for step " + stepNumber, "gpt-4o", "mock")
                        .estimatedCost(stepCost)
                        .build();

                try {
                    detector.process(request, chain);
                } catch (AgentLoopException e) {
                    tripped = true;
                    actualReason = e.tripReason().name();
                    trippedAtStep = i + 1;
                    break; // Stop trace processing once loop detector trips
                }
            }

            String actualLabel = tripped ? "LOOP" : "NOT_LOOP";
            boolean isCorrect = actualLabel.equals(expectedLabel);

            String note = "";
            if (isCorrect) {
                correctCount++;
            } else if ("NOT_LOOP".equals(expectedLabel) && "LOOP".equals(actualLabel)) {
                falsePositiveCount++;
                note = "FALSE POSITIVE: Legitimate trace wrongly flagged as loop (" + actualReason + ")";
            } else if ("LOOP".equals(expectedLabel) && "NOT_LOOP".equals(actualLabel)) {
                falseNegativeCount++;
                note = "FALSE NEGATIVE: Real loop missed by detector";
            }

            TraceResult res = new TraceResult(
                    id, category, subCategory, expectedLabel, actualLabel, expectedReason, actualReason,
                    trippedAtStep, prompts.size(), isCorrect, note
            );
            results.add(res);

            System.out.printf("[%s] Expected: %-8s | Actual: %-8s | Reason: %-25s | Step: %d/%d | Match: %b %s%n",
                    id, expectedLabel, actualLabel, actualReason,
                    trippedAtStep > 0 ? trippedAtStep : prompts.size(), prompts.size(),
                    isCorrect, note.isEmpty() ? "" : "(" + note + ")"
            );
        }

        System.out.println("------------------------------------------------------------------------");
        System.out.printf("Total Traces: %d | Correct: %d | False Positives: %d | False Negatives: %d%n",
                rawTraces.size(), correctCount, falsePositiveCount, falseNegativeCount);
        double accuracy = (double) correctCount / rawTraces.size() * 100.0;
        System.out.printf("Spike Accuracy: %.2f%%%n", accuracy);
    }
}
