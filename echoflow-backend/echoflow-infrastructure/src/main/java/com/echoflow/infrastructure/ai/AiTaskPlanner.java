package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.application.execution.TaskPlanningException;
import com.echoflow.domain.execution.StepType;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * LLM-powered task planner using Spring AI (OpenAI-compatible).
 *
 * <p>Calls the configured chat model to decompose a user's task description
 * into a list of typed execution steps. Validates the LLM output before
 * returning it.</p>
 */
@Component
public class AiTaskPlanner implements TaskPlannerPort {

    private static final Logger log = LoggerFactory.getLogger(AiTaskPlanner.class);
    private static final Set<String> VALID_TYPES = Set.of("THINK", "RESEARCH", "WRITE", "NOTIFY");
    private static final int MAX_RETRIES = 2;
    private static final int MAX_STEPS = 10;

    private final ChatClient chatClient;
    private final Resource promptTemplate;

    public AiTaskPlanner(ChatClient.Builder chatClientBuilder,
                         @Value("classpath:prompts/task-planner.st") Resource promptTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplate = promptTemplate;
    }

    @Override
    public List<PlannedStep> planSteps(String taskDescription) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var rawSteps = callLlm(taskDescription);
                return validate(rawSteps);
            } catch (Exception e) {
                lastException = e;
                log.warn("Task planning attempt {} failed: {}", attempt, e.getMessage());
            }
        }

        throw new TaskPlanningException(
                "Task planning failed after " + MAX_RETRIES + " attempts", lastException);
    }

    private List<LlmStep> callLlm(String taskDescription) {
        return chatClient.prompt()
                .user(u -> u.text(promptTemplate).param("taskDescription", taskDescription))
                .call()
                .entity(new ParameterizedTypeReference<>() {});
    }

    /**
     * Validate and convert LLM output. LLM output is untrusted input (Rule 5, agent.md).
     */
    private List<PlannedStep> validate(List<LlmStep> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            throw new TaskPlanningException("LLM returned null or empty step list");
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw new TaskPlanningException("LLM returned too many steps: " + rawSteps.size());
        }

        return rawSteps.stream().map(raw -> {
            if (raw.name() == null || raw.name().isBlank()) {
                throw new TaskPlanningException("LLM returned a step with blank name");
            }
            if (raw.type() == null || !VALID_TYPES.contains(raw.type().toUpperCase())) {
                throw new TaskPlanningException(
                        "LLM returned invalid step type: " + raw.type());
            }
            return new PlannedStep(
                    raw.name().strip(),
                    StepType.valueOf(raw.type().toUpperCase())
            );
        }).toList();
    }

    /**
     * Raw LLM output record — untrusted, needs validation before domain use.
     */
    record LlmStep(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type
    ) {}
}
