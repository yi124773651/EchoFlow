package com.echoflow.web.task;

import com.echoflow.application.execution.ApproveStepUseCase;
import com.echoflow.application.execution.ExecuteTaskUseCase;
import com.echoflow.application.task.*;
import com.echoflow.domain.EntityNotFoundException;
import com.echoflow.domain.execution.ExecutionRepository;
import com.echoflow.domain.task.TaskId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final SubmitTaskUseCase submitTaskUseCase;
    private final TaskQueryService taskQueryService;
    private final ExecuteTaskUseCase executeTaskUseCase;
    private final ApproveStepUseCase approveStepUseCase;
    private final ExecutionRepository executionRepository;
    private final SseExecutionEventPublisher ssePublisher;

    public TaskController(SubmitTaskUseCase submitTaskUseCase,
                          TaskQueryService taskQueryService,
                          ExecuteTaskUseCase executeTaskUseCase,
                          ApproveStepUseCase approveStepUseCase,
                          ExecutionRepository executionRepository,
                          SseExecutionEventPublisher ssePublisher) {
        this.submitTaskUseCase = submitTaskUseCase;
        this.taskQueryService = taskQueryService;
        this.executeTaskUseCase = executeTaskUseCase;
        this.approveStepUseCase = approveStepUseCase;
        this.executionRepository = executionRepository;
        this.ssePublisher = ssePublisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) {
        var command = new SubmitTaskCommand(request.description());
        var result = submitTaskUseCase.execute(command);

        // Trigger async execution on a virtual thread
        var taskId = result.id();
        Thread.startVirtualThread(() -> executeTaskUseCase.execute(taskId));

        return TaskResponse.from(result);
    }

    @GetMapping
    public List<TaskResponse> list() {
        return taskQueryService.findAll().stream()
                .map(TaskResponse::from)
                .toList();
    }

    @GetMapping("/{taskId}")
    public TaskDetailResult detail(@PathVariable UUID taskId) {
        return taskQueryService.findDetail(new TaskId(taskId));
    }

    @GetMapping(value = "/{taskId}/execution/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID taskId) {
        return ssePublisher.register(new TaskId(taskId));
    }

    @PostMapping("/{taskId}/execution/approve")
    public void approveStep(@PathVariable UUID taskId) {
        var executionId = findExecutionId(taskId);
        if (!approveStepUseCase.approve(executionId)) {
            throw new IllegalStateException("No pending approval for task " + taskId);
        }
    }

    @PostMapping("/{taskId}/execution/reject")
    public void rejectStep(@PathVariable UUID taskId,
                            @RequestBody(required = false) RejectRequest request) {
        var executionId = findExecutionId(taskId);
        var reason = request != null && request.reason() != null ? request.reason() : "Rejected by user";
        if (!approveStepUseCase.reject(executionId, reason)) {
            throw new IllegalStateException("No pending approval for task " + taskId);
        }
    }

    private com.echoflow.domain.execution.ExecutionId findExecutionId(UUID taskId) {
        return executionRepository.findByTaskId(new TaskId(taskId))
                .orElseThrow(() -> new EntityNotFoundException("Execution", new TaskId(taskId)))
                .id();
    }

    record RejectRequest(String reason) {}
}
