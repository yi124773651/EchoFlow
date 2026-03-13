package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConditionalSkipNodeActionTest {

    @Mock
    private StepProgressListener listener;

    private OverAllState emptyState() {
        var state = new OverAllState();
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);
        state.updateState(Map.of(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, "test"));
        return state;
    }

    @Test
    void calls_starting_then_skipped_for_single_step() throws Exception {
        var action = new ConditionalSkipNodeAction(
                List.of(new TaskPlannerPort.PlannedStep("搜索资料", StepType.RESEARCH)),
                listener, "THINK determined research not needed");

        action.apply(emptyState()).get();

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onStepStarting("搜索资料", StepType.RESEARCH);
        inOrder.verify(listener).onStepSkipped("搜索资料", "THINK determined research not needed");
    }

    @Test
    void skips_multiple_steps_in_order() throws Exception {
        var action = new ConditionalSkipNodeAction(
                List.of(
                        new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                        new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH)),
                listener, "skip reason");

        action.apply(emptyState()).get();

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onStepStarting("搜索1", StepType.RESEARCH);
        inOrder.verify(listener).onStepSkipped("搜索1", "skip reason");
        inOrder.verify(listener).onStepStarting("搜索2", StepType.RESEARCH);
        inOrder.verify(listener).onStepSkipped("搜索2", "skip reason");
    }

    @Test
    void returns_empty_map() throws Exception {
        var action = new ConditionalSkipNodeAction(
                List.of(new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH)),
                listener, "skip reason");

        var result = action.apply(emptyState()).get();

        assertThat(result).isEmpty();
    }

    @Test
    void never_calls_completed_or_failed() throws Exception {
        var action = new ConditionalSkipNodeAction(
                List.of(new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH)),
                listener, "skip reason");

        action.apply(emptyState()).get();

        verify(listener, never()).onStepCompleted(any(), any());
        verify(listener, never()).onStepFailed(any(), any());
    }
}
