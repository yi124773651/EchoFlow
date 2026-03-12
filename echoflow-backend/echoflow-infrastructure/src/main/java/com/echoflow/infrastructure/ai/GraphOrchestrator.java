package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncMultiCommandAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.echoflow.application.execution.GraphOrchestrationPort;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StateGraph-based implementation of {@link GraphOrchestrationPort}.
 *
 * <p>Builds a StateGraph that executes planned steps. When the step list
 * contains a THINK step followed by RESEARCH step(s), conditional routing
 * is applied: the THINK output's routing hint determines whether RESEARCH
 * steps are executed in parallel or skipped. Otherwise, a simple linear chain
 * is built.</p>
 *
 * <p>State is passed between nodes via {@code OverAllState}:
 * <ul>
 *   <li>{@code taskDescription} — REPLACE strategy (constant across all nodes)</li>
 *   <li>{@code outputs} — APPEND strategy (accumulates step outputs)</li>
 * </ul>
 *
 * <p>All StateGraph/OverAllState/KeyStrategy types are confined to this
 * Infrastructure layer. Application and Domain have zero graph-framework imports.</p>
 */
@Component
public class GraphOrchestrator implements GraphOrchestrationPort {

    private static final Logger log = LoggerFactory.getLogger(GraphOrchestrator.class);

    private static final String CONDITIONAL_SKIP_REASON =
            "Conditionally skipped: THINK analysis determined research is not needed";

    static final String SKIP_NODE_ID = "skip_research";

    private final StepExecutorPort stepExecutor;

    public GraphOrchestrator(StepExecutorPort stepExecutor) {
        this.stepExecutor = stepExecutor;
    }

    @Override
    public void executeSteps(String taskDescription,
                             List<TaskPlannerPort.PlannedStep> steps,
                             StepProgressListener listener) {
        if (steps.isEmpty()) {
            return;
        }

        try {
            var graph = buildGraph(steps, listener);
            var compiled = compileGraph(graph);
            compiled.invoke(Map.of(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, taskDescription));
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build/compile StateGraph", e);
        }
    }

    private StateGraph buildGraph(List<TaskPlannerPort.PlannedStep> steps,
                                  StepProgressListener listener) throws GraphStateException {
        var keyStrategyFactory = KeyStrategy.builder()
                .addStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE)
                .addStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND)
                .build();

        var graph = new StateGraph("execution", keyStrategyFactory);
        var researchRange = findResearchRange(steps);

        if (researchRange == null) {
            return buildLinearGraph(graph, steps, listener);
        }

        return buildConditionalGraph(graph, steps, listener, researchRange);
    }

    /**
     * Build a linear chain: START → step_1 → step_2 → ... → END.
     * Used when no THINK→RESEARCH pattern is detected.
     */
    private StateGraph buildLinearGraph(StateGraph graph,
                                        List<TaskPlannerPort.PlannedStep> steps,
                                        StepProgressListener listener) throws GraphStateException {
        for (int i = 0; i < steps.size(); i++) {
            graph.addNode(nodeId(i), new StepNodeAction(steps.get(i), stepExecutor, listener));
        }

        graph.addEdge(StateGraph.START, nodeId(0));
        for (int i = 0; i < steps.size() - 1; i++) {
            graph.addEdge(nodeId(i), nodeId(i + 1));
        }
        graph.addEdge(nodeId(steps.size() - 1), StateGraph.END);

        return graph;
    }

    /**
     * Build a graph with conditional routing after THINK node.
     * When research is needed, RESEARCH steps fan out in parallel and fan in at the
     * convergence point (first post-RESEARCH node). When skipped, a single aggregated
     * skip node fires listener callbacks for all RESEARCH steps instantly.
     *
     * <p>All routeMap value nodes must have outgoing edges to the same convergence
     * point, as required by {@code ConditionalParallelNode.findParallelNodeTargets}.</p>
     *
     * <pre>
     * START → ... → think ─[parallelConditional]─→ R1 ──────────┐
     *                                              R2 ──────────┤─→ write → ... → END
     *                          └──→ skip_research ──────────────┘
     * </pre>
     */
    private StateGraph buildConditionalGraph(StateGraph graph,
                                              List<TaskPlannerPort.PlannedStep> steps,
                                              StepProgressListener listener,
                                              int[] researchRange) throws GraphStateException {
        int thinkIndex = researchRange[0];
        int researchStart = researchRange[1];
        int researchEnd = researchRange[2];

        log.info("Parallel conditional routing: THINK at index {}, RESEARCH range [{}, {}] ({} parallel nodes)",
                thinkIndex, researchStart, researchEnd, researchEnd - researchStart + 1);

        // 1. Add all regular step nodes
        for (int i = 0; i < steps.size(); i++) {
            graph.addNode(nodeId(i), new StepNodeAction(steps.get(i), stepExecutor, listener));
        }

        // 2. Add single aggregated skip node for all RESEARCH steps
        var researchSteps = steps.subList(researchStart, researchEnd + 1);
        graph.addNode(SKIP_NODE_ID,
                new ConditionalSkipNodeAction(researchSteps, listener, CONDITIONAL_SKIP_REASON));

        // 3. Wire edges: START → ... → THINK (linear prefix)
        graph.addEdge(StateGraph.START, nodeId(0));
        for (int i = 0; i < thinkIndex; i++) {
            graph.addEdge(nodeId(i), nodeId(i + 1));
        }

        // 4. Parallel conditional edge from THINK node
        var router = new ParallelResearchRouter(researchStart, researchEnd);
        var routeMap = new HashMap<String, String>();
        for (int i = researchStart; i <= researchEnd; i++) {
            routeMap.put(ParallelResearchRouter.runRouteKey(i), nodeId(i));
        }
        routeMap.put(ParallelResearchRouter.ROUTE_SKIP, SKIP_NODE_ID);

        graph.addParallelConditionalEdges(nodeId(thinkIndex),
                AsyncMultiCommandAction.node_async(router),
                routeMap);

        // 5. All routeMap value nodes converge to the same target
        int convergeIndex = researchEnd + 1;
        String convergeTarget = convergeIndex < steps.size() ? nodeId(convergeIndex) : StateGraph.END;

        // RUN path: each RESEARCH node fans in to convergence point
        for (int i = researchStart; i <= researchEnd; i++) {
            graph.addEdge(nodeId(i), convergeTarget);
        }

        // SKIP path: single skip node goes to same convergence point
        graph.addEdge(SKIP_NODE_ID, convergeTarget);

        // 6. Linear suffix from convergence point to END
        if (convergeIndex < steps.size()) {
            for (int i = convergeIndex; i < steps.size() - 1; i++) {
                graph.addEdge(nodeId(i), nodeId(i + 1));
            }
            graph.addEdge(nodeId(steps.size() - 1), StateGraph.END);
        }

        return graph;
    }

    /**
     * Detect THINK→RESEARCH pattern in the step list.
     *
     * @return int[3] = {thinkIndex, researchStart, researchEnd}, or null if no pattern
     */
    int[] findResearchRange(List<TaskPlannerPort.PlannedStep> steps) {
        // Find first THINK step
        int thinkIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).type() == StepType.THINK) {
                thinkIndex = i;
                break;
            }
        }
        if (thinkIndex < 0 || thinkIndex >= steps.size() - 1) {
            return null;
        }

        // Check if THINK is immediately followed by RESEARCH
        int researchStart = thinkIndex + 1;
        if (steps.get(researchStart).type() != StepType.RESEARCH) {
            return null;
        }

        // Find contiguous RESEARCH range
        int researchEnd = researchStart;
        while (researchEnd < steps.size() - 1
                && steps.get(researchEnd + 1).type() == StepType.RESEARCH) {
            researchEnd++;
        }

        return new int[]{thinkIndex, researchStart, researchEnd};
    }

    private com.alibaba.cloud.ai.graph.CompiledGraph compileGraph(StateGraph graph) throws GraphStateException {
        var saverConfig = SaverConfig.builder()
                .register(MemorySaver.builder().build())
                .build();

        return graph.compile(CompileConfig.builder()
                .saverConfig(saverConfig)
                .build());
    }

    static String nodeId(int index) {
        return "step_" + (index + 1);
    }
}
