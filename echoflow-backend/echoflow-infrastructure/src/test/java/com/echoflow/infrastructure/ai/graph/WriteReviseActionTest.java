package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.StepOutput;
import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteReviseActionTest {

    @Mock private StepExecutorPort stepExecutor;
    @Mock private StepProgressListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(listener.onStepAwaitingApproval(any(), any()))
                .thenReturn(ApprovalDecision.APPROVED);
    }

    private OverAllState createState(String writeOutput, String feedback, int attempts) {
        var state = new OverAllState();
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_TASK_DESCRIPTION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(StepNodeAction.STATE_KEY_OUTPUTS, KeyStrategy.APPEND);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, KeyStrategy.REPLACE);
        state.registerKeyAndStrategy(WriteReviewGateAction.STATE_KEY_REVIEW_FEEDBACK, KeyStrategy.REPLACE);
        state.updateState(Map.of(
                StepNodeAction.STATE_KEY_TASK_DESCRIPTION, "test task",
                ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, writeOutput,
                ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, attempts,
                WriteReviewGateAction.STATE_KEY_REVIEW_FEEDBACK, feedback));
        return state;
    }

    @Test
    void builds_enriched_context_and_updates_output() throws Exception {
        var action = new WriteReviseAction("撰写", stepExecutor, listener);
        when(stepExecutor.execute(any())).thenReturn(new StepOutput("# Revised Report"));

        var state = createState("# Draft", "Needs more detail on architecture", 1);
        var result = action.apply(state).get();

        // Verify enriched context was passed to executor
        var contextCaptor = ArgumentCaptor.forClass(StepExecutionContext.class);
        verify(stepExecutor).execute(contextCaptor.capture());
        var ctx = contextCaptor.getValue();
        assertThat(ctx.taskDescription()).isEqualTo("test task");
        assertThat(ctx.stepName()).isEqualTo("撰写");
        assertThat(ctx.stepType()).isEqualTo(StepType.WRITE);
        assertThat(ctx.previousOutputs()).anyMatch(o -> o.contains("[REVISION FEEDBACK]"));
        assertThat(ctx.previousOutputs()).anyMatch(o -> o.contains("Needs more detail on architecture"));

        // Verify state updates
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, "# Revised Report");
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, 2);

        // Verify progress logging
        verify(listener).onStepProgress("撰写", LogType.ACTION, "Revising draft based on review feedback...");
        verify(listener).onStepProgress("撰写", LogType.OBSERVATION, "# Revised Report");
    }

    @Test
    void revision_failure_keeps_original_and_increments_attempts() throws Exception {
        var action = new WriteReviseAction("撰写", stepExecutor, listener);
        when(stepExecutor.execute(any())).thenThrow(new RuntimeException("LLM timeout"));

        var state = createState("# Original Draft", "Add more details", 2);
        var result = action.apply(state).get();

        // Original writeOutput should NOT be overwritten (key absent from result)
        assertThat(result).doesNotContainKey(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT);
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, 3);

        // Error progress logged
        verify(listener).onStepProgress(eq("撰写"), eq(LogType.ERROR), contains("Revision failed"));
    }
}
