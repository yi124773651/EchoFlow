package com.echoflow.web;

import com.echoflow.application.execution.*;
import com.echoflow.application.task.TaskDetailResult;
import com.echoflow.domain.execution.StepStatus;
import com.echoflow.domain.execution.StepType;
import com.echoflow.web.task.CreateTaskRequest;
import com.echoflow.web.task.TaskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end smoke tests verifying the full pipeline:
 * HTTP POST → ExecuteTaskUseCase → GraphOrchestrator(real StateGraph)
 * → mocked StepExecutorPort → DB persistence → HTTP GET verification.
 *
 * <p>Only {@link TaskPlannerPort} and {@link StepExecutorPort} are mocked.
 * Everything else — GraphOrchestrator, ExecuteTaskUseCase, JPA repositories,
 * Flyway migrations, SSE publisher — runs with real beans against a
 * Testcontainers PostgreSQL instance.</p>
 */
class EndToEndSmokeTest extends AbstractSmokeTest {

    @MockitoBean
    private TaskPlannerPort taskPlanner;

    @MockitoBean
    private StepExecutorPort stepExecutor;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void full_pipeline_completes_with_all_steps() {
        stubPlannerWith4Steps();
        when(stepExecutor.execute(any())).thenAnswer(inv -> {
            var ctx = inv.getArgument(0, StepExecutionContext.class);
            return switch (ctx.stepType()) {
                case THINK -> new StepOutput("思考结果：任务需要调研和撰写\n\n[ROUTING]\nneeds_research: YES\nreason: Need data");
                case RESEARCH -> new StepOutput("调研结果：找到相关信息");
                case WRITE -> new StepOutput("撰写结果：# 报告\n\n内容完成");
                case NOTIFY -> new StepOutput("通知结果：已发送通知");
            };
        });

        var taskId = createTask("测试任务");
        var detail = awaitTaskCompletion(taskId);

        assertThat(detail.taskStatus()).isEqualTo("COMPLETED");
        assertThat(detail.execution()).isNotNull();
        assertThat(detail.execution().steps()).hasSize(4);

        var steps = detail.execution().steps();
        assertThat(steps.get(0).type()).isEqualTo(StepType.THINK);
        assertThat(steps.get(0).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(0).output()).contains("思考结果");

        assertThat(steps.get(1).type()).isEqualTo(StepType.RESEARCH);
        assertThat(steps.get(1).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(1).output()).contains("调研结果");

        assertThat(steps.get(2).type()).isEqualTo(StepType.WRITE);
        assertThat(steps.get(2).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(2).output()).contains("报告");

        assertThat(steps.get(3).type()).isEqualTo(StepType.NOTIFY);
        assertThat(steps.get(3).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(3).output()).contains("通知结果");

        for (var step : steps) {
            assertThat(step.logs()).isNotEmpty();
        }
    }

    @Test
    void think_skips_research_when_routing_says_no() {
        stubPlannerWith4Steps();
        when(stepExecutor.execute(any())).thenAnswer(inv -> {
            var ctx = inv.getArgument(0, StepExecutionContext.class);
            return switch (ctx.stepType()) {
                case THINK -> new StepOutput("分析完成\n\n[ROUTING]\nneeds_research: NO\nreason: Simple task");
                case WRITE -> new StepOutput("撰写结果：简单报告");
                case NOTIFY -> new StepOutput("通知结果：已通知");
                default -> new StepOutput("不应到达");
            };
        });

        var taskId = createTask("简单任务");
        var detail = awaitTaskCompletion(taskId);

        assertThat(detail.taskStatus()).isEqualTo("COMPLETED");
        assertThat(detail.execution().steps()).hasSize(4);

        var steps = detail.execution().steps();
        assertThat(steps.get(0).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(1).type()).isEqualTo(StepType.RESEARCH);
        assertThat(steps.get(1).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(steps.get(2).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(3).status()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void research_failure_degrades_but_pipeline_continues() {
        stubPlannerWith4Steps();
        when(stepExecutor.execute(any())).thenAnswer(inv -> {
            var ctx = inv.getArgument(0, StepExecutionContext.class);
            return switch (ctx.stepType()) {
                case THINK -> new StepOutput("分析完成\n\n[ROUTING]\nneeds_research: YES\nreason: Need data");
                case RESEARCH -> throw new StepExecutionException("LLM timeout");
                case WRITE -> new StepOutput("撰写结果：降级后继续");
                case NOTIFY -> new StepOutput("通知结果：已通知");
            };
        });

        var taskId = createTask("降级测试");
        var detail = awaitTaskCompletion(taskId);

        assertThat(detail.taskStatus()).isEqualTo("COMPLETED");

        var steps = detail.execution().steps();
        assertThat(steps.get(0).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(1).type()).isEqualTo(StepType.RESEARCH);
        assertThat(steps.get(1).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(steps.get(2).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(steps.get(3).status()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void fatal_step_failure_marks_task_as_failed() {
        when(taskPlanner.planSteps(any())).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)));

        when(stepExecutor.execute(any())).thenThrow(new RuntimeException("Fatal LLM error"));

        var taskId = createTask("失败测试");
        var detail = awaitTaskTerminal(taskId);

        assertThat(detail.taskStatus()).isEqualTo("FAILED");
    }

    // -- Helpers --

    private void stubPlannerWith4Steps() {
        when(taskPlanner.planSteps(any())).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("调研", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY)));
    }

    private String createTask(String description) {
        var response = restTemplate.postForEntity(
                "/api/tasks", new CreateTaskRequest(description), TaskResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().id();
    }

    private TaskDetailResult awaitTaskCompletion(String taskId) {
        return awaitTaskStatus(taskId, "COMPLETED");
    }

    private TaskDetailResult awaitTaskTerminal(String taskId) {
        var ref = new TaskDetailResult[1];
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var detail = restTemplate.getForObject(
                            "/api/tasks/" + taskId, TaskDetailResult.class);
                    assertThat(detail).isNotNull();
                    assertThat(detail.taskStatus()).isIn("COMPLETED", "FAILED");
                    ref[0] = detail;
                });
        return ref[0];
    }

    private TaskDetailResult awaitTaskStatus(String taskId, String expectedStatus) {
        var ref = new TaskDetailResult[1];
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var detail = restTemplate.getForObject(
                            "/api/tasks/" + taskId, TaskDetailResult.class);
                    assertThat(detail).isNotNull();
                    assertThat(detail.taskStatus()).isEqualTo(expectedStatus);
                    ref[0] = detail;
                });
        return ref[0];
    }
}
