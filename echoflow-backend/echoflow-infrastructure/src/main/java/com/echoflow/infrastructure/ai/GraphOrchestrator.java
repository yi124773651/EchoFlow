package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.echoflow.application.execution.GraphOrchestrationPort;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.TaskPlannerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * StateGraph-based implementation of {@link GraphOrchestrationPort}.
 *
 * <p>Builds a linear StateGraph chain (START → step_1 → step_2 → ... → END)
 * that is equivalent to the original while-loop in {@code ExecuteTaskUseCase}.
 * Each node wraps a {@link StepNodeAction} that delegates to
 * {@link StepExecutorPort} for actual step execution.</p>
 *
 * <p>State is passed between nodes via {@code OverAllState}:
 * <ul>
 *   <li>{@code taskDescription} — REPLACE strategy (constant across all nodes)</li>
 *   <li>{@code outputs} — APPEND strategy (accumulates step outputs, replaces manual previousOutputs)</li>
 * </ul>
 *
 * <p>All StateGraph/OverAllState/KeyStrategy types are confined to this
 * Infrastructure layer. Application and Domain have zero graph-framework imports.</p>
 */
@Component
public class GraphOrchestrator implements GraphOrchestrationPort {

    private static final Logger log = LoggerFactory.getLogger(GraphOrchestrator.class);

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

        // Add nodes
        for (int i = 0; i < steps.size(); i++) {
            var nodeId = nodeId(i);
            var action = new StepNodeAction(steps.get(i), stepExecutor, listener);
            graph.addNode(nodeId, action);
        }

        // Add linear edges: START → step_1 → step_2 → ... → END
        graph.addEdge(StateGraph.START, nodeId(0));
        for (int i = 0; i < steps.size() - 1; i++) {
            graph.addEdge(nodeId(i), nodeId(i + 1));
        }
        graph.addEdge(nodeId(steps.size() - 1), StateGraph.END);

        return graph;
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
