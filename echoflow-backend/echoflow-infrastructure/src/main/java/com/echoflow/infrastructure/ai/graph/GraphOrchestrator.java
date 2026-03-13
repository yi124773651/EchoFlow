package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncMultiCommandAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.echoflow.application.execution.GraphOrchestrationPort;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import com.echoflow.infrastructure.ai.config.WriteReviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>When a {@link LlmWriteReviewer} is provided, WRITE steps are wrapped in
 * a review loop: WRITE → review_gate → (conditional) → revise_write → review_gate.
 * The review gate evaluates output quality via LLM-as-Judge and routes to either
 * approve (continue to next node) or revise (backward edge creating cycle).</p>
 *
 * <p>State is passed between nodes via {@code OverAllState}:
 * <ul>
 *   <li>{@code taskDescription} — REPLACE strategy (constant across all nodes)</li>
 *   <li>{@code outputs} — APPEND strategy (accumulates step outputs)</li>
 *   <li>{@code writeOutput} — REPLACE strategy (latest WRITE output, review-only)</li>
 *   <li>{@code writeAttempts} — REPLACE strategy (attempt counter, review-only)</li>
 *   <li>{@code reviewDecision} — REPLACE strategy ("approve"/"revise", review-only)</li>
 *   <li>{@code reviewFeedback} — REPLACE strategy (latest feedback, review-only)</li>
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
    static final String REVIEW_GATE_ID = "review_gate";
    static final String REVISE_NODE_ID = "revise_write";

    private final StepExecutorPort stepExecutor;
    private final LlmWriteReviewer writeReviewer;
    private final int maxAttempts;

    @Autowired
    public GraphOrchestrator(StepExecutorPort stepExecutor,
                              ObjectProvider<LlmWriteReviewer> writeReviewerProvider,
                              WriteReviewProperties reviewProperties) {
        this(stepExecutor,
             writeReviewerProvider.getIfAvailable(),
             reviewProperties.maxAttempts());
    }

    GraphOrchestrator(StepExecutorPort stepExecutor,
                       LlmWriteReviewer writeReviewer,
                       int maxAttempts) {
        this.stepExecutor = stepExecutor;
        this.writeReviewer = writeReviewer;
        this.maxAttempts = maxAttempts;
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
        var builder = KeyStrategy.builder()
                .addStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE)
                .addStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);

        if (reviewEnabled()) {
            builder.addStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, KeyStrategy.REPLACE)
                   .addStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, KeyStrategy.REPLACE)
                   .addStrategy(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, KeyStrategy.REPLACE)
                   .addStrategy(WriteReviewGateAction.STATE_KEY_REVIEW_FEEDBACK, KeyStrategy.REPLACE);
        }

        var keyStrategyFactory = builder.build();
        var graph = new StateGraph("execution", keyStrategyFactory);
        var researchRange = findResearchRange(steps);

        if (researchRange == null) {
            return buildLinearGraph(graph, steps, listener);
        }

        return buildConditionalGraph(graph, steps, listener, researchRange);
    }

    /**
     * Build a linear chain: START → step_1 → step_2 → ... → END.
     * When review is enabled, WRITE steps are wrapped with a review loop.
     */
    private StateGraph buildLinearGraph(StateGraph graph,
                                        List<TaskPlannerPort.PlannedStep> steps,
                                        StepProgressListener listener) throws GraphStateException {
        for (int i = 0; i < steps.size(); i++) {
            graph.addNode(nodeId(i), createNodeAction(steps.get(i), listener));
        }

        graph.addEdge(StateGraph.START, nodeId(0));
        for (int i = 0; i < steps.size(); i++) {
            String nextTarget = (i + 1 < steps.size()) ? nodeId(i + 1) : StateGraph.END;
            if (shouldAddReviewLoop(steps.get(i))) {
                addWriteReviewLoop(graph, nodeId(i), nextTarget, steps.get(i), listener);
            } else {
                graph.addEdge(nodeId(i), nextTarget);
            }
        }

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
     *                                              R2 ──────────┤─→ write → review_gate ─[revise]→ revise_write ─┐
     *                          └──→ skip_research ──────────────┘              └─[approve]─→ notify → END        │
     *                                                                              ↑────────────────────────────┘
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

        // 1. Add all step nodes (WRITE steps use ReviewableWriteNodeAction when review enabled)
        for (int i = 0; i < steps.size(); i++) {
            graph.addNode(nodeId(i), createNodeAction(steps.get(i), listener));
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
            for (int i = convergeIndex; i < steps.size(); i++) {
                String nextTarget = (i + 1 < steps.size()) ? nodeId(i + 1) : StateGraph.END;
                if (shouldAddReviewLoop(steps.get(i))) {
                    addWriteReviewLoop(graph, nodeId(i), nextTarget, steps.get(i), listener);
                } else {
                    graph.addEdge(nodeId(i), nextTarget);
                }
            }
        }

        return graph;
    }

    private AsyncNodeAction createNodeAction(TaskPlannerPort.PlannedStep step,
                                              StepProgressListener listener) {
        if (shouldAddReviewLoop(step)) {
            return new ReviewableWriteNodeAction(step, stepExecutor, listener);
        }
        return new StepNodeAction(step, stepExecutor, listener);
    }

    private boolean shouldAddReviewLoop(TaskPlannerPort.PlannedStep step) {
        return reviewEnabled() && step.type() == StepType.WRITE;
    }

    boolean reviewEnabled() {
        return writeReviewer != null;
    }

    /**
     * Insert a review loop after a WRITE node: WRITE → review_gate → (conditional)
     * → revise_write → review_gate (backward edge). The review_gate routes to either
     * "approve" (→ nextTarget) or "revise" (→ revise_write).
     */
    private void addWriteReviewLoop(StateGraph graph, String writeNodeId, String nextTarget,
                                     TaskPlannerPort.PlannedStep writeStep,
                                     StepProgressListener listener) throws GraphStateException {
        log.info("Adding write review loop after '{}' (maxAttempts={})", writeStep.name(), maxAttempts);

        graph.addNode(REVIEW_GATE_ID, new WriteReviewGateAction(
                writeStep.name(), writeReviewer, listener, maxAttempts));
        graph.addNode(REVISE_NODE_ID, new WriteReviseAction(
                writeStep.name(), stepExecutor, listener));

        // WRITE → review_gate
        graph.addEdge(writeNodeId, REVIEW_GATE_ID);
        // revise_write → review_gate (backward edge creating cycle)
        graph.addEdge(REVISE_NODE_ID, REVIEW_GATE_ID);
        // review_gate → conditional routing
        graph.addConditionalEdges(REVIEW_GATE_ID,
                AsyncEdgeAction.edge_async(state ->
                    state.<String>value(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION)
                        .orElse("approve")),
                Map.of("revise", REVISE_NODE_ID, "approve", nextTarget));
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
