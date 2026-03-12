package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.MultiCommand;
import com.alibaba.cloud.ai.graph.action.MultiCommandAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Parallel routing function that reads the THINK step's output from OverAllState,
 * parses the routing hint, and returns a {@link MultiCommand} indicating which
 * RESEARCH nodes to execute in parallel — or a single skip route.
 *
 * <p>Used with {@code graph.addParallelConditionalEdges(thinkNode,
 * AsyncMultiCommandAction.node_async(router), routeMap)}.</p>
 *
 * <p>When research is needed, returns all run route keys for parallel fan-out.
 * When skipped, returns a single {@link #ROUTE_SKIP} key.
 * Safe default: always run research if hint is missing or unparseable.</p>
 */
final class ParallelResearchRouter implements MultiCommandAction {

    private static final Logger log = LoggerFactory.getLogger(ParallelResearchRouter.class);

    static final String ROUTE_SKIP = "skip";
    private static final String RUN_ROUTE_PREFIX = "run_step_";

    private final List<String> runRouteKeys;

    /**
     * @param researchStart inclusive start index of RESEARCH steps
     * @param researchEnd   inclusive end index of RESEARCH steps
     */
    ParallelResearchRouter(int researchStart, int researchEnd) {
        this.runRouteKeys = IntStream.rangeClosed(researchStart, researchEnd)
                .mapToObj(ParallelResearchRouter::runRouteKey)
                .toList();
    }

    @Override
    public MultiCommand apply(OverAllState state, RunnableConfig config) {
        var lastOutput = extractLastOutput(state);
        var hint = RoutingHintParser.parse(lastOutput);

        log.info("Parallel research routing: needsResearch={}, reason='{}', parallelNodes={}",
                hint.needsResearch(), hint.reason(), hint.needsResearch() ? runRouteKeys.size() : 0);

        if (hint.needsResearch()) {
            return new MultiCommand(runRouteKeys);
        } else {
            return new MultiCommand(List.of(ROUTE_SKIP));
        }
    }

    static String runRouteKey(int index) {
        return RUN_ROUTE_PREFIX + (index + 1);
    }

    @SuppressWarnings("unchecked")
    private String extractLastOutput(OverAllState state) {
        return state.<List<String>>value(StepNodeAction.STATE_KEY_OUTPUTS)
                .filter(list -> !list.isEmpty())
                .map(List::getLast)
                .orElse("");
    }
}
