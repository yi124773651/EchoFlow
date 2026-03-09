package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.StepOutput;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Routes step execution to the appropriate executor based on {@link com.echoflow.domain.execution.StepType}.
 *
 * <p>This is the single {@link StepExecutorPort} implementation registered as a Spring component.
 * Internally it delegates to specialized executors for each step type.</p>
 */
@Component
public class StepExecutorRouter implements StepExecutorPort {

    private final LlmThinkExecutor thinkExecutor;
    private final LlmResearchExecutor researchExecutor;
    private final LlmWriteExecutor writeExecutor;
    private final LogNotifyExecutor notifyExecutor;

    public StepExecutorRouter(ChatClient.Builder chatClientBuilder,
                              @Value("classpath:prompts/step-think.st") Resource thinkPrompt,
                              @Value("classpath:prompts/step-research.st") Resource researchPrompt,
                              @Value("classpath:prompts/step-write.st") Resource writePrompt) {
        var chatClient = chatClientBuilder.build();
        this.thinkExecutor = new LlmThinkExecutor(chatClient, thinkPrompt);
        this.researchExecutor = new LlmResearchExecutor(chatClient, researchPrompt);
        this.writeExecutor = new LlmWriteExecutor(chatClient, writePrompt);
        this.notifyExecutor = new LogNotifyExecutor();
    }

    @Override
    public StepOutput execute(StepExecutionContext context) {
        return switch (context.stepType()) {
            case THINK -> thinkExecutor.execute(context);
            case RESEARCH -> researchExecutor.execute(context);
            case WRITE -> writeExecutor.execute(context);
            case NOTIFY -> notifyExecutor.execute(context);
        };
    }
}
