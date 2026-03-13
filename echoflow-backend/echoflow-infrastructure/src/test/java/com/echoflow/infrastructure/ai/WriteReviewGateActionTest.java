package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.LogType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteReviewGateActionTest {

    @Mock private LlmWriteReviewer reviewer;
    @Mock private StepProgressListener listener;

    private OverAllState createState(String writeOutput, int attempts) {
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
                ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, attempts));
        return state;
    }

    @Test
    void approved_calls_onStepCompleted_and_appends_output() throws Exception {
        var action = new WriteReviewGateAction("撰写", reviewer, listener, 3);
        when(reviewer.evaluate("test task", "撰写", "# Report"))
                .thenReturn(new ReviewResult(90, true, "No issues found"));

        var state = createState("# Report", 1);
        var result = action.apply(state).get();

        verify(listener).onStepCompleted("撰写", "# Report");
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve");
        assertThat(result).containsEntry(StepNodeAction.STATE_KEY_OUTPUTS, "# Report");
    }

    @Test
    void rejected_sets_revise_decision_and_logs_feedback() throws Exception {
        var action = new WriteReviewGateAction("撰写", reviewer, listener, 3);
        when(reviewer.evaluate("test task", "撰写", "# Draft"))
                .thenReturn(new ReviewResult(50, false, "Needs more detail"));

        var state = createState("# Draft", 1);
        var result = action.apply(state).get();

        verify(listener, never()).onStepCompleted(any(), any());
        verify(listener).onStepProgress(eq("撰写"), eq(LogType.THOUGHT), contains("score 50/100"));
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "revise");
        assertThat(result).containsEntry(WriteReviewGateAction.STATE_KEY_REVIEW_FEEDBACK, "Needs more detail");
    }

    @Test
    void max_attempts_forces_approve() throws Exception {
        var action = new WriteReviewGateAction("撰写", reviewer, listener, 3);
        when(reviewer.evaluate("test task", "撰写", "# Draft v3"))
                .thenReturn(new ReviewResult(60, false, "Still needs work"));

        var state = createState("# Draft v3", 3);
        var result = action.apply(state).get();

        verify(listener).onStepCompleted("撰写", "# Draft v3");
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve");
    }

    @Test
    void blank_output_auto_approves_without_calling_reviewer() throws Exception {
        var action = new WriteReviewGateAction("撰写", reviewer, listener, 3);

        var state = createState("", 1);
        var result = action.apply(state).get();

        verifyNoInteractions(reviewer);
        verify(listener, never()).onStepCompleted(any(), any());
        assertThat(result).containsEntry(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve");
    }
}
