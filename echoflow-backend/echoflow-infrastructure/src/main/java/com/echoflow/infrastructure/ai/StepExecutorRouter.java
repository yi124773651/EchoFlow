package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.StepOutput;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Routes step execution to the appropriate executor based on {@link StepType}
 * and resolves the correct {@link ChatClient} for each step type via multi-model routing.
 *
 * <p>This is the single {@link StepExecutorPort} implementation registered as a Spring component.
 * Internally it delegates to specialized executors for each step type, passing the
 * routing-resolved ChatClient per call.</p>
 *
 * <p>When the primary model fails (all retries exhausted), it automatically falls back
 * to the configured fallback model if different from the primary.</p>
 */
@Component
public class StepExecutorRouter implements StepExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(StepExecutorRouter.class);

    private final LlmThinkExecutor thinkExecutor;
    private final LlmResearchExecutor researchExecutor;
    private final LlmWriteExecutor writeExecutor;
    private final LlmNotifyExecutor notifyExecutor;
    private final Map<StepType, ChatClient> primaryClients;
    private final ChatClient fallbackClient;

    public StepExecutorRouter(ChatClientProvider chatClientProvider,
                              MultiModelProperties properties,
                              @Value("classpath:prompts/step-think.st") Resource thinkPrompt,
                              @Value("classpath:prompts/step-research.st") Resource researchPrompt,
                              @Value("classpath:prompts/step-write.st") Resource writePrompt,
                              @Value("classpath:prompts/step-notify.st") Resource notifyPrompt,
                              @Value("${echoflow.github.api-base-url:https://api.github.com}") String githubBaseUrl,
                              @Value("${echoflow.github.token:}") String githubToken,
                              @Value("${echoflow.github.connect-timeout:5s}") Duration githubConnectTimeout,
                              @Value("${echoflow.github.read-timeout:10s}") Duration githubReadTimeout,
                              @Value("${echoflow.github.max-results:5}") int githubMaxResults,
                              @Value("${echoflow.webhook.url:}") String webhookUrl,
                              @Value("${echoflow.webhook.connect-timeout:5s}") Duration webhookConnectTimeout,
                              @Value("${echoflow.webhook.read-timeout:10s}") Duration webhookReadTimeout) {
        var gitHubSearchTool = new GitHubSearchTool(
                githubBaseUrl, githubToken,
                githubConnectTimeout, githubReadTimeout,
                githubMaxResults);
        var webhookNotifyTool = new WebhookNotifyTool(
                webhookUrl, webhookConnectTimeout, webhookReadTimeout);

        this.thinkExecutor = new LlmThinkExecutor(thinkPrompt);
        this.researchExecutor = new LlmResearchExecutor(researchPrompt, gitHubSearchTool);
        this.writeExecutor = new LlmWriteExecutor(writePrompt);
        this.notifyExecutor = new LlmNotifyExecutor(notifyPrompt, webhookNotifyTool);

        var routing = properties.routing();
        this.primaryClients = Map.of(
                StepType.THINK, chatClientProvider.resolve(routing.aliasFor("think")),
                StepType.RESEARCH, chatClientProvider.resolve(routing.aliasFor("research")),
                StepType.WRITE, chatClientProvider.resolve(routing.aliasFor("write")),
                StepType.NOTIFY, chatClientProvider.resolve(routing.aliasFor("notify")));
        this.fallbackClient = chatClientProvider.resolve(routing.fallback());
    }

    @Override
    public StepOutput execute(StepExecutionContext context) {
        var executor = resolveExecutor(context.stepType());
        var primaryClient = primaryClients.get(context.stepType());

        try {
            return executor.execute(context, primaryClient);
        } catch (StepExecutionException e) {
            if (fallbackClient == primaryClient) {
                throw e;
            }
            log.warn("Primary model failed for step '{}' (type={}), attempting fallback: {}",
                    context.stepName(), context.stepType(), e.getMessage());
            return executor.execute(context, fallbackClient);
        }
    }

    private LlmStepExecutor resolveExecutor(StepType type) {
        return switch (type) {
            case THINK -> thinkExecutor;
            case RESEARCH -> researchExecutor;
            case WRITE -> writeExecutor;
            case NOTIFY -> notifyExecutor;
        };
    }
}
