package com.echoflow.web.task;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating a task.
 */
public record CreateTaskRequest(
        @NotBlank(message = "description must not be blank")
        String description
) {}
