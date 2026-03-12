package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchDecisionRouterTest {

    private ResearchDecisionRouter router;

    @BeforeEach
    void setUp() {
        router = new ResearchDecisionRouter();
    }

    private OverAllState stateWithOutputs(List<String> outputs) {
        var state = new OverAllState();
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);
        state.updateState(Map.of(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, "test task"));
        for (var output : outputs) {
            state.updateState(Map.of(StepNodeAction.STATE_KEY_OUTPUTS, output));
        }
        return state;
    }

    @Test
    void returns_run_when_think_output_has_YES_hint() throws Exception {
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: YES\nreason: Need external data"));

        assertThat(router.apply(state)).isEqualTo(ResearchDecisionRouter.ROUTE_RUN);
    }

    @Test
    void returns_skip_when_think_output_has_NO_hint() throws Exception {
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: NO\nreason: Simple task"));

        assertThat(router.apply(state)).isEqualTo(ResearchDecisionRouter.ROUTE_SKIP);
    }

    @Test
    void returns_run_when_no_routing_block() throws Exception {
        var state = stateWithOutputs(List.of("Just plain analysis without routing"));

        assertThat(router.apply(state)).isEqualTo(ResearchDecisionRouter.ROUTE_RUN);
    }

    @Test
    void returns_run_when_outputs_empty() throws Exception {
        var state = stateWithOutputs(List.of());

        assertThat(router.apply(state)).isEqualTo(ResearchDecisionRouter.ROUTE_RUN);
    }

    @Test
    void reads_last_output_from_multiple() throws Exception {
        var state = stateWithOutputs(List.of(
                "First step output",
                "Think output\n\n[ROUTING]\nneeds_research: NO\nreason: done"));

        assertThat(router.apply(state)).isEqualTo(ResearchDecisionRouter.ROUTE_SKIP);
    }
}
