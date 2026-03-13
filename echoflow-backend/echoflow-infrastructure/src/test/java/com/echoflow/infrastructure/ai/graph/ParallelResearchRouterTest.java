package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.MultiCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelResearchRouterTest {

    // Router for RESEARCH at indices 1..2 (two parallel RESEARCH steps)
    private final ParallelResearchRouter router = new ParallelResearchRouter(1, 2);

    private static final RunnableConfig CONFIG =
            RunnableConfig.builder().threadId("test").build();

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
    void returns_all_run_routes_when_think_says_yes() throws Exception {
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: YES\nreason: Need data"));

        MultiCommand result = router.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(
                ParallelResearchRouter.runRouteKey(1),
                ParallelResearchRouter.runRouteKey(2));
    }

    @Test
    void returns_skip_route_when_think_says_no() throws Exception {
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: NO\nreason: Simple task"));

        MultiCommand result = router.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(ParallelResearchRouter.ROUTE_SKIP);
    }

    @Test
    void returns_all_run_routes_when_no_routing_hint() throws Exception {
        var state = stateWithOutputs(List.of("Plain analysis without routing block"));

        MultiCommand result = router.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(
                ParallelResearchRouter.runRouteKey(1),
                ParallelResearchRouter.runRouteKey(2));
    }

    @Test
    void returns_all_run_routes_when_outputs_empty() throws Exception {
        var state = stateWithOutputs(List.of());

        MultiCommand result = router.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(
                ParallelResearchRouter.runRouteKey(1),
                ParallelResearchRouter.runRouteKey(2));
    }

    @Test
    void handles_three_research_indices() throws Exception {
        var router3 = new ParallelResearchRouter(2, 4);
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: YES\nreason: Need data"));

        MultiCommand result = router3.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(
                ParallelResearchRouter.runRouteKey(2),
                ParallelResearchRouter.runRouteKey(3),
                ParallelResearchRouter.runRouteKey(4));
    }

    @Test
    void handles_single_research_index() throws Exception {
        var routerSingle = new ParallelResearchRouter(1, 1);
        var state = stateWithOutputs(List.of(
                "Analysis...\n\n[ROUTING]\nneeds_research: YES\nreason: Need data"));

        MultiCommand result = routerSingle.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(ParallelResearchRouter.runRouteKey(1));
    }

    @Test
    void reads_last_output_from_multiple() throws Exception {
        var state = stateWithOutputs(List.of(
                "First step output",
                "Think output\n\n[ROUTING]\nneeds_research: NO\nreason: done"));

        MultiCommand result = router.apply(state, CONFIG);

        assertThat(result.gotoNodes()).containsExactly(ParallelResearchRouter.ROUTE_SKIP);
    }
}
