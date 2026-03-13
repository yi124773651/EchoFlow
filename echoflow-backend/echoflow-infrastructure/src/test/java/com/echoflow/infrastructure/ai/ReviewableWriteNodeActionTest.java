package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.StepOutput;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewableWriteNodeActionTest {

    @Mock private StepExecutorPort stepExecutor;
    @Mock private StepProgressListener listener;

    private OverAllState createState(String taskDescription) {
        var state = new OverAllState();
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, KeyStrategy.REPLACE);
        state.updateState(Map.of(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, taskDescription));
        return state;
    }

    @Test
    void first_entry_fires_onStepStarting_and_stores_output() throws Exception {
        var step = new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE);
        var action = new ReviewableWriteNodeAction(step, stepExecutor, listener);
        when(stepExecutor.execute(any())).thenReturn(new StepOutput("# Draft Report"));

        var state = createState("test task");
        var result = action.apply(state).get();

        verify(listener).onStepStarting("撰写", StepType.WRITE);
        verify(listener, never()).onStepCompleted(any(), any());
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, "# Draft Report");
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, 1);
    }

    @Test
    void degradation_fires_onStepSkipped_and_sets_auto_approve() throws Exception {
        var step = new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE);
        var action = new ReviewableWriteNodeAction(step, stepExecutor, listener);
        when(stepExecutor.execute(any())).thenThrow(new StepExecutionException("LLM timeout"));

        var state = createState("test task");
        var result = action.apply(state).get();

        verify(listener).onStepStarting("撰写", StepType.WRITE);
        verify(listener).onStepSkipped("撰写", "LLM timeout");
        verify(listener, never()).onStepCompleted(any(), any());
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, "");
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve");
    }

    @Test
    void fatal_error_fires_onStepFailed_and_returns_failed_future() throws Exception {
        var step = new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE);
        var action = new ReviewableWriteNodeAction(step, stepExecutor, listener);
        when(stepExecutor.execute(any())).thenThrow(new RuntimeException("fatal NPE"));

        var state = createState("test task");
        var future = action.apply(state);

        verify(listener).onStepFailed("撰写", "fatal NPE");
        assertThat(future).isCompletedExceptionally();
    }
}
