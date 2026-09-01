package io.polity4j.benchmarks.diy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiyRouterTest {

    @Test
    @DisplayName("DiyRouter: Selects the cheapest backend when all backends are healthy")
    void testPicksCheapestBackendWhenAllHealthy() {
        DiyRouter.Backend<String> gpt4 = new DiyRouter.Backend<>(
                "expensive-gpt4", 0.03, () -> "GPT-4 Response");
        DiyRouter.Backend<String> haiku = new DiyRouter.Backend<>(
                "cheap-haiku", 0.002, () -> "Haiku Response");
        DiyRouter.Backend<String> mini = new DiyRouter.Backend<>(
                "medium-mini", 0.01, () -> "Mini Response");

        DiyRouter<String> router = new DiyRouter<>(List.of(gpt4, haiku, mini));

        assertThat(router.getCheapestBackendName()).isEqualTo("cheap-haiku");
        
        String result = router.routeAndExecute();
        assertThat(result).isEqualTo("Haiku Response");
    }
}
