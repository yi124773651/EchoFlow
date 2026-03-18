package com.echoflow.domain.execution;

import com.echoflow.domain.task.TaskId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class ExecutionTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(120);
    private static final TaskId TASK_ID = TaskId.generate();

    // --- Creation ---

    @Test
    void create_initializes_in_planning_status() {
        var exec = Execution.create(ExecutionId.generate(), TASK_ID, NOW);

        assertThat(exec.status()).isEqualTo(ExecutionStatus.PLANNING);
        assertThat(exec.taskId()).isEqualTo(TASK_ID);
        assertThat(exec.startedAt()).isEqualTo(NOW);
        assertThat(exec.completedAt()).isNull();
        assertThat(exec.steps()).isEmpty();
    }

    @Test
    void create_rejects_null_taskId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Execution.create(ExecutionId.generate(), null, NOW));
    }

    // --- Adding steps ---

    @Test
    void addStep_during_planning() {
        var exec = aPlanningExecution();
        var step = exec.addStep(StepId.generate(), "调研项目", StepType.RESEARCH, NOW);

        assertThat(exec.steps()).hasSize(1);
        assertThat(step.name()).isEqualTo("调研项目");
        assertThat(step.type()).isEqualTo(StepType.RESEARCH);
        assertThat(step.status()).isEqualTo(StepStatus.PENDING);
        assertThat(step.order()).isEqualTo(1);
    }

    @Test
    void addStep_assigns_sequential_order() {
        var exec = aPlanningExecution();
        exec.addStep(StepId.generate(), "步骤1", StepType.THINK, NOW);
        exec.addStep(StepId.generate(), "步骤2", StepType.RESEARCH, NOW);
        var step3 = exec.addStep(StepId.generate(), "步骤3", StepType.WRITE, NOW);

        assertThat(step3.order()).isEqualTo(3);
    }

    @Test
    void addStep_rejected_when_running() {
        var exec = aRunningExecution();
        assertThatThrownBy(() -> exec.addStep(StepId.generate(), "late step", StepType.THINK, NOW))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    // --- Start running ---

    @Test
    void startRunning_transitions_from_planning() {
        var exec = aPlanningExecution();
        exec.addStep(StepId.generate(), "步骤", StepType.THINK, NOW);
        exec.startRunning();
        assertThat(exec.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void startRunning_requires_at_least_one_step() {
        var exec = aPlanningExecution();
        assertThatThrownBy(exec::startRunning)
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void startRunning_from_running_throws() {
        var exec = aRunningExecution();
        assertThatThrownBy(exec::startRunning)
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    // --- Step execution ---

    @Test
    void startNextStep_starts_first_pending() {
        var exec = aRunningExecution();
        var started = exec.startNextStep();
        assertThat(started.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(started.order()).isEqualTo(1);
    }

    @Test
    void completeStep_transitions_step() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.completeStep(step.id(), "调研完成");

        assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.output()).isEqualTo("调研完成");
    }

    @Test
    void failStep_transitions_step() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.failStep(step.id(), "API 超时");

        assertThat(step.status()).isEqualTo(StepStatus.FAILED);
        assertThat(step.output()).isEqualTo("API 超时");
    }

    // --- Step logs ---

    @Test
    void appendStepLog_adds_to_running_step() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        var log = new StepLog(LogType.THOUGHT, "正在思考如何调研", NOW);
        exec.appendStepLog(step.id(), log);

        assertThat(step.logs()).hasSize(1);
        assertThat(step.logs().getFirst().content()).isEqualTo("正在思考如何调研");
    }

    @Test
    void appendStepLog_to_pending_step_throws() {
        var exec = aRunningExecution();
        var pendingStepId = exec.steps().getFirst().id();
        var log = new StepLog(LogType.THOUGHT, "test", NOW);
        assertThatThrownBy(() -> exec.appendStepLog(pendingStepId, log))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    // --- Completion ---

    @Test
    void markCompleted_when_all_steps_done() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.completeStep(step.id(), "done");

        exec.markCompleted(LATER);

        assertThat(exec.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(exec.completedAt()).isEqualTo(LATER);
    }

    @Test
    void markCompleted_when_steps_pending_throws() {
        var exec = aRunningExecutionWithTwoSteps();
        var step = exec.startNextStep();
        exec.completeStep(step.id(), "done");
        // second step still pending
        assertThatThrownBy(() -> exec.markCompleted(LATER))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void markFailed_from_running() {
        var exec = aRunningExecution();
        exec.markFailed(LATER);

        assertThat(exec.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(exec.completedAt()).isEqualTo(LATER);
    }

    // --- hasPendingSteps ---

    @Test
    void hasPendingSteps_true_when_steps_pending() {
        var exec = aRunningExecution();
        assertThat(exec.hasPendingSteps()).isTrue();
    }

    @Test
    void hasPendingSteps_false_when_all_done() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.completeStep(step.id(), "done");
        assertThat(exec.hasPendingSteps()).isFalse();
    }

    // --- StepLog validation ---

    @Test
    void stepLog_rejects_null_type() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepLog(null, "content", NOW));
    }

    @Test
    void stepLog_rejects_blank_content() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepLog(LogType.THOUGHT, "  ", NOW));
    }

    // --- Skip (degradation) ---

    @Test
    void skipStep_transitions_running_step_to_skipped() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.skipStep(step.id(), "LLM timeout after 2 retries");

        assertThat(step.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(step.output()).isEqualTo("LLM timeout after 2 retries");
    }

    @Test
    void markCompleted_succeeds_with_skipped_steps() {
        var exec = aRunningExecutionWithTwoSteps();
        var step1 = exec.startNextStep();
        exec.completeStep(step1.id(), "done");
        var step2 = exec.startNextStep();
        exec.skipStep(step2.id(), "degraded");

        exec.markCompleted(LATER);

        assertThat(exec.status()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void hasPendingSteps_false_after_skip() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.skipStep(step.id(), "degraded");

        assertThat(exec.hasPendingSteps()).isFalse();
    }

    @Test
    void skipStep_from_completed_throws() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.completeStep(step.id(), "done");

        assertThatThrownBy(() -> exec.skipStep(step.id(), "too late"))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    // --- startStepByName ---

    @Test
    void startStepByName_starts_specific_step() {
        var exec = aRunningExecutionWithThreeSteps();
        var step = exec.startStepByName("搜索");
        assertThat(step.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(step.name()).isEqualTo("搜索");
    }

    @Test
    void startStepByName_throws_when_not_found() {
        var exec = aRunningExecution();
        assertThatThrownBy(() -> exec.startStepByName("不存在"))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void startStepByName_throws_when_step_not_pending() {
        var exec = aRunningExecution();
        exec.startStepByName("调研"); // now RUNNING
        assertThatThrownBy(() -> exec.startStepByName("调研"))
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void startStepByName_allows_parallel_starts() {
        var exec = aRunningExecutionWithThreeSteps();
        var step1 = exec.startStepByName("搜索");
        var step2 = exec.startStepByName("写报告");
        assertThat(step1.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(step2.status()).isEqualTo(StepStatus.RUNNING);
    }

    // --- Human Approval (Step level) ---

    @Test
    void step_transitions_to_waiting_approval_from_running() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();

        exec.markWaitingApproval();
        step.markWaitingApproval();

        assertThat(step.status()).isEqualTo(StepStatus.WAITING_APPROVAL);
    }

    @Test
    void step_resumes_from_waiting_approval_to_running() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.markWaitingApproval();
        step.markWaitingApproval();
        step.resumeFromApproval();
        exec.resumeRunning();

        assertThat(step.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(exec.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void step_waiting_approval_can_be_skipped_on_rejection() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.markWaitingApproval();
        step.markWaitingApproval();

        exec.skipStep(step.id(), "Rejected by user");

        assertThat(step.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(step.output()).isEqualTo("Rejected by user");
    }

    @Test
    void step_waiting_approval_from_pending_throws() {
        var exec = aRunningExecution();
        var step = exec.steps().getFirst();
        assertThatThrownBy(step::markWaitingApproval)
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void step_appendLog_allowed_during_waiting_approval() {
        var exec = aRunningExecution();
        var step = exec.startNextStep();
        exec.markWaitingApproval();
        step.markWaitingApproval();

        var log = new StepLog(LogType.ACTION, "Awaiting human approval", NOW);
        exec.appendStepLog(step.id(), log);

        assertThat(step.logs()).hasSize(1);
    }

    // --- Human Approval (Execution level) ---

    @Test
    void execution_transitions_to_waiting_approval_from_running() {
        var exec = aRunningExecution();
        exec.markWaitingApproval();
        assertThat(exec.status()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
    }

    @Test
    void execution_resumes_running_from_waiting_approval() {
        var exec = aRunningExecution();
        exec.markWaitingApproval();
        exec.resumeRunning();
        assertThat(exec.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void execution_resume_from_running_throws() {
        var exec = aRunningExecution();
        assertThatThrownBy(exec::resumeRunning)
                .isInstanceOf(IllegalExecutionStateException.class);
    }

    @Test
    void execution_markFailed_allowed_during_waiting_approval() {
        var exec = aRunningExecution();
        exec.markWaitingApproval();
        exec.markFailed(LATER);
        assertThat(exec.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    // --- ApprovalDecision ---

    @Test
    void approvalDecision_approved_constant() {
        assertThat(ApprovalDecision.APPROVED.approved()).isTrue();
        assertThat(ApprovalDecision.APPROVED.reason()).isNull();
    }

    @Test
    void approvalDecision_rejected_factory() {
        var decision = ApprovalDecision.rejected("内容不符合要求");
        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo("内容不符合要求");
    }

    // --- Helpers ---

    private Execution aPlanningExecution() {
        return Execution.create(ExecutionId.generate(), TASK_ID, NOW);
    }

    private Execution aRunningExecution() {
        var exec = aPlanningExecution();
        exec.addStep(StepId.generate(), "调研", StepType.RESEARCH, NOW);
        exec.startRunning();
        return exec;
    }

    private Execution aRunningExecutionWithTwoSteps() {
        var exec = aPlanningExecution();
        exec.addStep(StepId.generate(), "调研", StepType.RESEARCH, NOW);
        exec.addStep(StepId.generate(), "写报告", StepType.WRITE, NOW);
        exec.startRunning();
        return exec;
    }

    private Execution aRunningExecutionWithThreeSteps() {
        var exec = aPlanningExecution();
        exec.addStep(StepId.generate(), "分析", StepType.THINK, NOW);
        exec.addStep(StepId.generate(), "搜索", StepType.RESEARCH, NOW);
        exec.addStep(StepId.generate(), "写报告", StepType.WRITE, NOW);
        exec.startRunning();
        return exec;
    }
}
