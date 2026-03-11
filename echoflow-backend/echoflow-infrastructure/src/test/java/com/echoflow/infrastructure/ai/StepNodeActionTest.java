package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.*;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepNodeActionTest {

    @Mock private StepExecutorPort stepExecutor;
    @Mock private StepProgressListener listener;

    private TaskPlannerPort.PlannedStep thinkStep;
    private TaskPlannerPort.PlannedStep researchStep;

    @BeforeEach
    void setUp() {
        thinkStep = new TaskPlannerPort.PlannedStep("分析需求", StepType.THINK);
        researchStep = new TaskPlannerPort.PlannedStep("搜索资料", StepType.RESEARCH);
    }

    private OverAllState stateWith(String taskDescription, List<String> outputs) {
        var state = new OverAllState();
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);
        state.updateState(Map.of(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, taskDescription));
        for (var output : outputs) {
            state.updateState(Map.of(StepNodeAction.STATE_KEY_OUTPUTS, output));
        }
        return state;
    }

    private OverAllState stateWith(String taskDescription) {
        return stateWith(taskDescription, List.of());
    }

    @Nested
    class SuccessfulExecution {

        @Test
        void calls_listener_and_returns_output_in_state() throws Exception {
            var action = new StepNodeAction(thinkStep, stepExecutor, listener);
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("分析结果"));

            var future = action.apply(stateWith("调研 Java Agent"));
            var result = future.get();

            assertThat(result).containsEntry(StepNodeAction.STATE_KEY_OUTPUTS, "分析结果");

            InOrder inOrder = inOrder(listener);
            inOrder.verify(listener).onStepStarting("分析需求", StepType.THINK);
            inOrder.verify(listener).onStepCompleted("分析需求", "分析结果");
        }

        @Test
        void passes_correct_context_to_executor() throws Exception {
            var action = new StepNodeAction(researchStep, stepExecutor, listener);
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("搜索结果"));

            action.apply(stateWith("调研项目", List.of("之前的分析")));

            var captor = org.mockito.ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor).execute(captor.capture());

            var ctx = captor.getValue();
            assertThat(ctx.taskDescription()).isEqualTo("调研项目");
            assertThat(ctx.stepName()).isEqualTo("搜索资料");
            assertThat(ctx.stepType()).isEqualTo(StepType.RESEARCH);
            assertThat(ctx.previousOutputs()).containsExactly("之前的分析");
        }
    }

    @Nested
    class DegradationOnStepExecutionException {

        @Test
        void calls_listener_skipped_and_returns_empty_map() throws Exception {
            var action = new StepNodeAction(researchStep, stepExecutor, listener);
            when(stepExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("LLM timeout"));

            var future = action.apply(stateWith("测试任务"));
            var result = future.get();

            assertThat(result).isEmpty();

            InOrder inOrder = inOrder(listener);
            inOrder.verify(listener).onStepStarting("搜索资料", StepType.RESEARCH);
            inOrder.verify(listener).onStepSkipped("搜索资料", "LLM timeout");
            verify(listener, never()).onStepCompleted(any(), any());
            verify(listener, never()).onStepFailed(any(), any());
        }
    }

    @Nested
    class FatalFailure {

        @Test
        void calls_listener_failed_and_returns_failed_future() {
            var action = new StepNodeAction(thinkStep, stepExecutor, listener);
            when(stepExecutor.execute(any()))
                    .thenThrow(new RuntimeException("unexpected NPE"));

            var future = action.apply(stateWith("失败测试"));

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("unexpected NPE");

            InOrder inOrder = inOrder(listener);
            inOrder.verify(listener).onStepStarting("分析需求", StepType.THINK);
            inOrder.verify(listener).onStepFailed("分析需求", "unexpected NPE");
            verify(listener, never()).onStepCompleted(any(), any());
            verify(listener, never()).onStepSkipped(any(), any());
        }
    }

    @Nested
    class PreviousOutputsReading {

        @Test
        void reads_empty_outputs_when_state_has_none() throws Exception {
            var action = new StepNodeAction(thinkStep, stepExecutor, listener);
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("结果"));

            action.apply(stateWith("任务"));

            var captor = org.mockito.ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor).execute(captor.capture());
            assertThat(captor.getValue().previousOutputs()).isEmpty();
        }

        @Test
        void reads_accumulated_outputs_from_state() throws Exception {
            var action = new StepNodeAction(researchStep, stepExecutor, listener);
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("搜索结果"));

            action.apply(stateWith("任务", List.of("分析输出", "其他输出")));

            var captor = org.mockito.ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor).execute(captor.capture());
            assertThat(captor.getValue().previousOutputs())
                    .containsExactly("分析输出", "其他输出");
        }
    }
}
