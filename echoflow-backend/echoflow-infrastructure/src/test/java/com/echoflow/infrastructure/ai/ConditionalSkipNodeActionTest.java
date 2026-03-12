package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void calls_starting_then_skipped_on_listener() throws Exception {
        var action = new ConditionalSkipNodeAction(
                "搜索资料", StepType.RESEARCH, listener, "THINK determined research not needed");

        action.apply(emptyState()).get();

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onStepStarting("搜索资料", StepType.RESEARCH);
        inOrder.verify(listener).onStepSkipped("搜索资料", "THINK determined research not needed");
    }

    @Test
    void returns_empty_map() throws Exception {
        var action = new ConditionalSkipNodeAction(
                "搜索", StepType.RESEARCH, listener, "skip reason");

        var result = action.apply(emptyState()).get();

        assertThat(result).isEmpty();
    }

    @Test
    void never_calls_completed_or_failed() throws Exception {
        var action = new ConditionalSkipNodeAction(
                "搜索", StepType.RESEARCH, listener, "skip reason");

        action.apply(emptyState()).get();

        verify(listener, never()).onStepCompleted(any(), any());
        verify(listener, never()).onStepFailed(any(), any());
    }

    @Test
    void passes_correct_step_info_to_listener() throws Exception {
        var action = new ConditionalSkipNodeAction(
                "深度调研", StepType.RESEARCH, listener, "不需要调研");

        action.apply(emptyState()).get();

        verify(listener).onStepStarting("深度调研", StepType.RESEARCH);
        verify(listener).onStepSkipped("深度调研", "不需要调研");
    }
}
