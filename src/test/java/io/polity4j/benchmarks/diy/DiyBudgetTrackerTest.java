package io.polity4j.benchmarks.diy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiyBudgetTrackerTest {

    @Test
    @DisplayName("DiyBudgetTracker: Accurately limits spend when no caching is involved")
    void testLimitsSpendWithoutCaching() {
        double maxBudget = 0.03;
        double costPerCall = 0.01;
        DiyBudgetTracker tracker = new DiyBudgetTracker(maxBudget);

        // Calls 1, 2, 3 succeed
        String r1 = tracker.execute(costPerCall, () -> "Call 1 OK");
        String r2 = tracker.execute(costPerCall, () -> "Call 2 OK");
        String r3 = tracker.execute(costPerCall, () -> "Call 3 OK");

        assertThat(r1).isEqualTo("Call 1 OK");
        assertThat(r2).isEqualTo("Call 2 OK");
        assertThat(r3).isEqualTo("Call 3 OK");
        assertThat(tracker.getCurrentSpend()).isEqualTo(0.03);

        // Call 4 exceeds max budget of 0.03
        assertThatThrownBy(() -> tracker.execute(costPerCall, () -> "Call 4 Fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Budget limit exceeded");

        assertThat(tracker.getCurrentSpend()).isEqualTo(0.03);
    }
}
