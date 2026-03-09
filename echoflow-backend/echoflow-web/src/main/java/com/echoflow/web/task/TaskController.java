package com.echoflow.web.task;

import com.echoflow.application.execution.ExecuteTaskUseCase;
import com.echoflow.application.task.*;
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
    private final SseExecutionEventPublisher ssePublisher;

    public TaskController(SubmitTaskUseCase submitTaskUseCase,
                          TaskQueryService taskQueryService,
                          ExecuteTaskUseCase executeTaskUseCase,
                          SseExecutionEventPublisher ssePublisher) {
        this.submitTaskUseCase = submitTaskUseCase;
        this.taskQueryService = taskQueryService;
        this.executeTaskUseCase = executeTaskUseCase;
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
}
