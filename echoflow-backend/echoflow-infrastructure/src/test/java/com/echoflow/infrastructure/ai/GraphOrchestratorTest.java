package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.*;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphOrchestrator} — verifies that the StateGraph linear chain
 * produces the same behavior as the original while-loop in ExecuteTaskUseCase.
 */
@ExtendWith(MockitoExtension.class)
class GraphOrchestratorTest {

    @Mock private StepExecutorPort stepExecutor;
    @Mock private StepProgressListener listener;

    private GraphOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new GraphOrchestrator(stepExecutor);
    }

    @Nested
    class SingleStepExecution {

        @Test
        void executes_single_step_and_notifies_listener() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK));
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("分析结果"));

            orchestrator.executeSteps("调研任务", steps, listener);

            verify(listener).onStepStarting("分析", StepType.THINK);
            verify(listener).onStepCompleted("分析", "分析结果");
            verify(stepExecutor).execute(any());
        }
    }

    @Nested
    class MultiStepLinearExecution {

        @Test
        void executes_steps_in_order_and_accumulates_outputs() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput("分析结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps("写报告", steps, listener);

            // Verify execution order
            InOrder inOrder = inOrder(listener);
            inOrder.verify(listener).onStepStarting("分析", StepType.THINK);
            inOrder.verify(listener).onStepCompleted("分析", "分析结果");
            inOrder.verify(listener).onStepStarting("搜索", StepType.RESEARCH);
            inOrder.verify(listener).onStepCompleted("搜索", "搜索结果");
            inOrder.verify(listener).onStepStarting("撰写", StepType.WRITE);
            inOrder.verify(listener).onStepCompleted("撰写", "报告");

            // Verify previousOutputs accumulation
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(3)).execute(captor.capture());
            var contexts = captor.getAllValues();

            assertThat(contexts.get(0).previousOutputs()).isEmpty();
            assertThat(contexts.get(1).previousOutputs()).containsExactly("分析结果");
            assertThat(contexts.get(2).previousOutputs()).containsExactly("分析结果", "搜索结果");
        }
    }

    @Nested
    class SkippedStepHandling {

        @Test
        void skipped_step_output_not_in_subsequent_previous_outputs() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput("分析结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenThrow(new StepExecutionException("LLM timeout"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps("测试任务", steps, listener);

            // Verify skip notification
            verify(listener).onStepSkipped("搜索", "LLM timeout");

            // Verify WRITE only received THINK output (not skipped RESEARCH)
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(3)).execute(captor.capture());
            var contexts = captor.getAllValues();

            assertThat(contexts.get(2).previousOutputs()).containsExactly("分析结果");
        }

        @Test
        void graph_continues_after_skipped_step() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenThrow(new StepExecutionException("degradation"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));

            orchestrator.executeSteps("任务", steps, listener);

            verify(listener).onStepSkipped("分析", "degradation");
            verify(listener).onStepCompleted("搜索", "搜索结果");
            verify(stepExecutor, times(2)).execute(any());
        }
    }

    @Nested
    class FatalFailureHandling {

        @Test
        void fatal_exception_propagates_and_stops_subsequent_steps() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenThrow(new RuntimeException("unexpected NPE"));

            assertThatThrownBy(() ->
                    orchestrator.executeSteps("任务", steps, listener))
                    .isInstanceOf(Exception.class);

            verify(listener).onStepFailed("分析", "unexpected NPE");
            // Second step should NOT be executed
            verify(stepExecutor, times(1)).execute(any());
        }
    }

    @Nested
    class EmptySteps {

        @Test
        void does_nothing_when_steps_is_empty() {
            orchestrator.executeSteps("任务", List.of(), listener);

            verifyNoInteractions(stepExecutor, listener);
        }
    }

    @Nested
    class NodeIdGeneration {

        @Test
        void generates_sequential_node_ids() {
            assertThat(GraphOrchestrator.nodeId(0)).isEqualTo("step_1");
            assertThat(GraphOrchestrator.nodeId(1)).isEqualTo("step_2");
            assertThat(GraphOrchestrator.nodeId(9)).isEqualTo("step_10");
        }
    }
}
