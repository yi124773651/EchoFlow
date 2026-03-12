package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Edge routing function that reads the last output from OverAllState (THINK's output),
 * parses the routing hint, and returns a routing key for conditional edges.
 *
 * <p>Used with {@code graph.addConditionalEdges(thinkNode, AsyncEdgeAction.edge_async(router), routeMap)}.</p>
 *
 * <p>Returns {@link #ROUTE_RUN} (execute RESEARCH) or {@link #ROUTE_SKIP}
 * (skip RESEARCH). Safe default: always run RESEARCH if hint is missing.</p>
 */
final class ResearchDecisionRouter implements EdgeAction {

    private static final Logger log = LoggerFactory.getLogger(ResearchDecisionRouter.class);

    static final String ROUTE_RUN = "run";
    static final String ROUTE_SKIP = "skip";

    @Override
    public String apply(OverAllState state) {
        var lastOutput = extractLastOutput(state);
        var hint = RoutingHintParser.parse(lastOutput);

        log.info("Research routing decision: needsResearch={}, reason='{}'",
                hint.needsResearch(), hint.reason());

        return hint.needsResearch() ? ROUTE_RUN : ROUTE_SKIP;
    }

    @SuppressWarnings("unchecked")
    private String extractLastOutput(OverAllState state) {
        return state.<List<String>>value(StepNodeAction.STATE_KEY_OUTPUTS)
                .filter(list -> !list.isEmpty())
                .map(List::getLast)
                .orElse("");
    }
}
