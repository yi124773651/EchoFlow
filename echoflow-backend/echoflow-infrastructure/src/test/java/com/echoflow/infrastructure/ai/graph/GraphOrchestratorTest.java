package com.echoflow.infrastructure.ai.graph;

import com.echoflow.application.execution.*;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.ExecutionId;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepType;
import com.echoflow.infrastructure.persistence.checkpoint.CheckpointJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphOrchestrator} — verifies that the StateGraph linear chain
 * produces the same behavior as the original while-loop in ExecuteTaskUseCase.
 */
@ExtendWith(MockitoExtension.class)
class GraphOrchestratorTest {

    @Mock private StepExecutorPort stepExecutor;
    @Mock private StepProgressListener listener;
    @Mock private CheckpointJpaRepository checkpointRepo;

    private static final ExecutionId EXEC_ID = ExecutionId.generate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GraphOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(listener.onStepAwaitingApproval(any(), any()))
                .thenReturn(ApprovalDecision.APPROVED);
        orchestrator = new GraphOrchestrator(stepExecutor, null, 3, checkpointRepo, objectMapper);
    }

    @Nested
    class SingleStepExecution {

        @Test
        void executes_single_step_and_notifies_listener() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK));
            when(stepExecutor.execute(any())).thenReturn(new StepOutput("分析结果"));

            orchestrator.executeSteps(EXEC_ID,"调研任务", steps, listener);

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

            orchestrator.executeSteps(EXEC_ID,"写报告", steps, listener);

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

            orchestrator.executeSteps(EXEC_ID,"测试任务", steps, listener);

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

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

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
                    orchestrator.executeSteps(EXEC_ID,"任务", steps, listener))
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
            orchestrator.executeSteps(EXEC_ID,"任务", List.of(), listener);

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

        @Test
        void skip_node_has_fixed_id() {
            assertThat(GraphOrchestrator.SKIP_NODE_ID).isEqualTo("skip_research");
        }
    }

    @Nested
    class FindResearchRange {

        @Test
        void detects_think_followed_by_research() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            var range = orchestrator.findResearchRange(steps);

            assertThat(range).isNotNull();
            assertThat(range[0]).isEqualTo(0); // thinkIndex
            assertThat(range[1]).isEqualTo(1); // researchStart
            assertThat(range[2]).isEqualTo(1); // researchEnd
        }

        @Test
        void detects_consecutive_research_steps() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            var range = orchestrator.findResearchRange(steps);

            assertThat(range).isNotNull();
            assertThat(range[0]).isEqualTo(0);
            assertThat(range[1]).isEqualTo(1);
            assertThat(range[2]).isEqualTo(2);
        }

        @Test
        void returns_null_when_no_think_step() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            assertThat(orchestrator.findResearchRange(steps)).isNull();
        }

        @Test
        void returns_null_when_think_not_followed_by_research() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            assertThat(orchestrator.findResearchRange(steps)).isNull();
        }

        @Test
        void returns_null_when_think_is_last_step() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK));

            assertThat(orchestrator.findResearchRange(steps)).isNull();
        }
    }

    @Nested
    class ConditionalRouting {

        private static final String THINK_OUTPUT_SKIP =
                "Analysis complete.\n\n[ROUTING]\nneeds_research: NO\nreason: Simple task";
        private static final String THINK_OUTPUT_RUN =
                "Analysis complete.\n\n[ROUTING]\nneeds_research: YES\nreason: Need data";
        private static final String THINK_OUTPUT_NO_HINT =
                "Plain analysis without any routing hint";

        @Test
        void skips_research_when_think_says_no() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_SKIP));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"简单任务", steps, listener);

            // THINK executes, RESEARCH skipped, WRITE executes
            InOrder inOrder = inOrder(listener, stepExecutor);
            inOrder.verify(listener).onStepStarting("分析", StepType.THINK);
            inOrder.verify(stepExecutor).execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK));
            inOrder.verify(listener).onStepCompleted("分析", THINK_OUTPUT_SKIP);
            inOrder.verify(listener).onStepStarting("搜索", StepType.RESEARCH);
            inOrder.verify(listener).onStepSkipped(eq("搜索"), anyString());
            inOrder.verify(listener).onStepStarting("撰写", StepType.WRITE);
            inOrder.verify(stepExecutor).execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE));
            inOrder.verify(listener).onStepCompleted("撰写", "报告");

            // RESEARCH executor never called
            verify(stepExecutor, never()).execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH));
        }

        @Test
        void runs_research_when_think_says_yes() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"复杂任务", steps, listener);

            // All steps execute normally
            verify(stepExecutor, times(3)).execute(any());
            verify(listener).onStepCompleted("分析", THINK_OUTPUT_RUN);
            verify(listener).onStepCompleted("搜索", "搜索结果");
            verify(listener).onStepCompleted("撰写", "报告");
            verify(listener, never()).onStepSkipped(any(), any());
        }

        @Test
        void runs_research_when_no_routing_hint() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_NO_HINT));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // Safe default: all steps execute (including RESEARCH)
            verify(stepExecutor, times(3)).execute(any());
            verify(listener, never()).onStepSkipped(any(), any());
        }

        @Test
        void skips_consecutive_research_steps() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_SKIP));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"简单任务", steps, listener);

            // Both RESEARCH steps skipped
            verify(listener).onStepSkipped(eq("搜索1"), anyString());
            verify(listener).onStepSkipped(eq("搜索2"), anyString());
            verify(listener).onStepCompleted("撰写", "报告");
            verify(stepExecutor, never()).execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH));
        }

        @Test
        void runs_consecutive_research_steps_in_parallel() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索1".equals(ctx.stepName()))))
                    .thenReturn(new StepOutput("结果1"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索2".equals(ctx.stepName()))))
                    .thenReturn(new StepOutput("结果2"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"复杂任务", steps, listener);

            // All 4 steps execute, no skips
            verify(stepExecutor, times(4)).execute(any());
            verify(listener, never()).onStepSkipped(any(), any());

            // Both RESEARCH steps completed (order may vary)
            verify(listener).onStepCompleted("搜索1", "结果1");
            verify(listener).onStepCompleted("搜索2", "结果2");
        }

        @Test
        void parallel_research_outputs_accumulated_for_write() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索1".equals(ctx.stepName()))))
                    .thenReturn(new StepOutput("结果1"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索2".equals(ctx.stepName()))))
                    .thenReturn(new StepOutput("结果2"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"复杂任务", steps, listener);

            // Verify WRITE receives THINK + both RESEARCH outputs (order may vary)
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(4)).execute(captor.capture());
            var writeContext = captor.getAllValues().stream()
                    .filter(ctx -> ctx.stepType() == StepType.WRITE)
                    .findFirst().orElseThrow();

            assertThat(writeContext.previousOutputs())
                    .contains(THINK_OUTPUT_RUN)
                    .containsAll(List.of("结果1", "结果2"))
                    .hasSize(3);
        }

        @Test
        void parallel_research_each_receives_only_think_output() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // Parallel RESEARCH steps each see only THINK output (not each other's)
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(4)).execute(captor.capture());
            var researchContexts = captor.getAllValues().stream()
                    .filter(ctx -> ctx.stepType() == StepType.RESEARCH)
                    .toList();

            for (var ctx : researchContexts) {
                assertThat(ctx.previousOutputs()).containsExactly(THINK_OUTPUT_RUN);
            }
        }

        @Test
        void parallel_research_one_degrades_others_complete() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索1".equals(ctx.stepName()))))
                    .thenThrow(new StepExecutionException("LLM timeout"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && "搜索2".equals(ctx.stepName()))))
                    .thenReturn(new StepOutput("结果2"));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // 搜索1 degraded (skipped), 搜索2 completed, WRITE continues
            verify(listener).onStepSkipped("搜索1", "LLM timeout");
            verify(listener).onStepCompleted("搜索2", "结果2");
            verify(listener).onStepCompleted("撰写", "报告");

            // WRITE receives THINK + only 搜索2 output (搜索1 skipped, no output)
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(4)).execute(captor.capture());
            var writeContext = captor.getAllValues().stream()
                    .filter(ctx -> ctx.stepType() == StepType.WRITE)
                    .findFirst().orElseThrow();
            assertThat(writeContext.previousOutputs())
                    .contains(THINK_OUTPUT_RUN, "结果2")
                    .hasSize(2);
        }

        @Test
        void linear_chain_for_no_think_research_pattern() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(any())).thenReturn(new StepOutput("output"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // All executed linearly
            verify(stepExecutor, times(3)).execute(any());
            verify(listener, never()).onStepSkipped(any(), any());
        }

        @Test
        void linear_chain_when_think_not_followed_by_research() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(any())).thenReturn(new StepOutput("output"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            verify(stepExecutor, times(3)).execute(any());
            verify(listener, never()).onStepSkipped(any(), any());
        }

        @Test
        void skip_preserves_output_accumulation() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_SKIP));
            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("报告"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // WRITE should only have THINK output (RESEARCH was skipped, no output)
            var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
            verify(stepExecutor, times(2)).execute(captor.capture());
            var contexts = captor.getAllValues();

            // THINK has no previous outputs
            assertThat(contexts.get(0).previousOutputs()).isEmpty();
            // WRITE has only THINK output
            assertThat(contexts.get(1).previousOutputs()).containsExactly(THINK_OUTPUT_SKIP);
        }

        @Test
        void skipped_research_at_end_of_plan() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH));

            when(stepExecutor.execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_SKIP));

            orchestrator.executeSteps(EXEC_ID,"简单任务", steps, listener);

            verify(listener).onStepCompleted("分析", THINK_OUTPUT_SKIP);
            verify(listener).onStepSkipped(eq("搜索"), anyString());
            verify(stepExecutor, times(1)).execute(any());
        }
    }

    @Nested
    class WriteReviewLoop {

        private static final String THINK_OUTPUT_RUN =
                "Analysis complete.\n\n[ROUTING]\nneeds_research: YES\nreason: Need data";

        @Mock private LlmWriteReviewer reviewer;

        @BeforeEach
        void setUpReviewOrchestrator() {
            orchestrator = new GraphOrchestrator(stepExecutor, reviewer, 3, checkpointRepo, objectMapper);
        }

        @Test
        void approve_on_first_attempt() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput("分析结果"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("# Report"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.NOTIFY)))
                    .thenReturn(new StepOutput("已通知"));
            when(reviewer.evaluate("test task", "撰写", "# Report"))
                    .thenReturn(new ReviewResult(90, true, "No issues"));

            orchestrator.executeSteps(EXEC_ID,"test task", steps, listener);

            // All steps complete in order
            InOrder inOrder = inOrder(listener);
            inOrder.verify(listener).onStepStarting("分析", StepType.THINK);
            inOrder.verify(listener).onStepCompleted("分析", "分析结果");
            inOrder.verify(listener).onStepStarting("撰写", StepType.WRITE);
            // onStepCompleted for WRITE comes from review_gate, not the WRITE node
            inOrder.verify(listener).onStepCompleted("撰写", "# Report");
            inOrder.verify(listener).onStepStarting("通知", StepType.NOTIFY);
            inOrder.verify(listener).onStepCompleted("通知", "已通知");

            // Reviewer called once
            verify(reviewer, times(1)).evaluate(any(), any(), any());
        }

        @Test
        void revise_then_approve() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput("分析结果"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("# Draft"))
                    .thenReturn(new StepOutput("# Revised"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.NOTIFY)))
                    .thenReturn(new StepOutput("已通知"));
            when(reviewer.evaluate(eq("test task"), eq("撰写"), any()))
                    .thenReturn(new ReviewResult(50, false, "Needs more detail"))
                    .thenReturn(new ReviewResult(85, true, "Good"));

            orchestrator.executeSteps(EXEC_ID,"test task", steps, listener);

            // WRITE starts once, completes once (with revised output)
            verify(listener).onStepStarting("撰写", StepType.WRITE);
            verify(listener).onStepCompleted("撰写", "# Revised");

            // Reviewer called twice (reject then approve)
            verify(reviewer, times(2)).evaluate(any(), any(), any());

            // WRITE executor called twice (original + revision)
            verify(stepExecutor, times(2)).execute(argThat(ctx ->
                    ctx != null && ctx.stepType() == StepType.WRITE));

            // Progress logged during review + revision
            verify(listener).onStepProgress(eq("撰写"), eq(LogType.THOUGHT),
                    argThat(s -> s.contains("score 50/100")));
            verify(listener).onStepProgress(eq("撰写"), eq(LogType.ACTION),
                    argThat(s -> s.contains("Revising")));

            // NOTIFY still completes
            verify(listener).onStepCompleted("通知", "已通知");
        }

        @Test
        void max_attempts_forces_approve() {
            orchestrator = new GraphOrchestrator(stepExecutor, reviewer, 2, checkpointRepo, objectMapper);

            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("# Draft"))
                    .thenReturn(new StepOutput("# Draft v2"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.NOTIFY)))
                    .thenReturn(new StepOutput("已通知"));
            when(reviewer.evaluate(any(), any(), any()))
                    .thenReturn(new ReviewResult(50, false, "Needs work"))
                    .thenReturn(new ReviewResult(60, false, "Still needs work"));

            orchestrator.executeSteps(EXEC_ID,"test task", steps, listener);

            // Force-approved after max attempts
            verify(listener).onStepCompleted("撰写", "# Draft v2");
            verify(listener).onStepCompleted("通知", "已通知");
            verify(reviewer, times(2)).evaluate(any(), any(), any());
        }

        @Test
        void conditional_graph_with_review_loop() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("搜索", StepType.RESEARCH),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput(THINK_OUTPUT_RUN));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.RESEARCH)))
                    .thenReturn(new StepOutput("搜索结果"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenReturn(new StepOutput("# Report"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.NOTIFY)))
                    .thenReturn(new StepOutput("已通知"));
            when(reviewer.evaluate(any(), any(), any()))
                    .thenReturn(new ReviewResult(90, true, "Good"));

            orchestrator.executeSteps(EXEC_ID,"任务", steps, listener);

            // All steps complete
            verify(listener).onStepCompleted("分析", THINK_OUTPUT_RUN);
            verify(listener).onStepCompleted("搜索", "搜索结果");
            verify(listener).onStepCompleted("撰写", "# Report");
            verify(listener).onStepCompleted("通知", "已通知");
            verify(reviewer, times(1)).evaluate(any(), any(), any());
        }

        @Test
        void write_degradation_skips_review() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.THINK)))
                    .thenReturn(new StepOutput("分析结果"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.WRITE)))
                    .thenThrow(new StepExecutionException("LLM timeout"));
            when(stepExecutor.execute(argThat(ctx -> ctx != null && ctx.stepType() == StepType.NOTIFY)))
                    .thenReturn(new StepOutput("已通知"));

            orchestrator.executeSteps(EXEC_ID,"test task", steps, listener);

            // WRITE skipped, review auto-approves (blank output)
            verify(listener).onStepSkipped("撰写", "LLM timeout");
            verify(listener).onStepCompleted("通知", "已通知");
            // Reviewer never called because writeOutput is blank
            verifyNoInteractions(reviewer);
        }

        @Test
        void review_enabled_but_no_write_step() {
            var steps = List.of(
                    new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                    new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY));

            when(stepExecutor.execute(any())).thenReturn(new StepOutput("output"));

            orchestrator.executeSteps(EXEC_ID,"task", steps, listener);

            verify(stepExecutor, times(2)).execute(any());
            verifyNoInteractions(reviewer);
        }
    }
}
