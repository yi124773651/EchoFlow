package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p><b>POC-2:</b> THINK steps are now executed via {@link ReactAgentThinkExecutor} backed by
 * Spring AI Alibaba's {@link ReactAgent}. Other step types continue using the existing
 * {@link LlmStepExecutor} path. When the ReactAgent-backed primary fails, fallback uses
 * the original {@link LlmThinkExecutor} with the fallback ChatClient.</p>
 */
@Component
public class StepExecutorRouter implements StepExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(StepExecutorRouter.class);

    private final ReactAgentThinkExecutor reactAgentThinkExecutor;
    private final LlmThinkExecutor thinkExecutor; // kept for fallback
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

        // POC-2: Build ReactAgent for THINK steps
        var thinkChatClient = primaryClients.get(StepType.THINK);
        String thinkInstruction = readPromptContent(thinkPrompt);
        this.reactAgentThinkExecutor = new ReactAgentThinkExecutor(
                buildThinkAgent(thinkChatClient, thinkInstruction));
    }

    /**
     * Package-private constructor for testing — allows injecting a pre-built
     * ReactAgentThinkExecutor and other executors directly.
     */
    StepExecutorRouter(ReactAgentThinkExecutor reactAgentThinkExecutor,
                       LlmThinkExecutor thinkExecutor,
                       LlmResearchExecutor researchExecutor,
                       LlmWriteExecutor writeExecutor,
                       LlmNotifyExecutor notifyExecutor,
                       Map<StepType, ChatClient> primaryClients,
                       ChatClient fallbackClient) {
        this.reactAgentThinkExecutor = reactAgentThinkExecutor;
        this.thinkExecutor = thinkExecutor;
        this.researchExecutor = researchExecutor;
        this.writeExecutor = writeExecutor;
        this.notifyExecutor = notifyExecutor;
        this.primaryClients = primaryClients;
        this.fallbackClient = fallbackClient;
    }

    @Override
    public StepOutput execute(StepExecutionContext context) {
        if (context.stepType() == StepType.THINK) {
            return executeThinkWithReactAgent(context);
        }
        return executeLlmStep(context);
    }

    private StepOutput executeThinkWithReactAgent(StepExecutionContext context) {
        try {
            return reactAgentThinkExecutor.execute(context);
        } catch (StepExecutionException e) {
            var primaryClient = primaryClients.get(StepType.THINK);
            if (fallbackClient == primaryClient) {
                throw e;
            }
            log.warn("ReactAgent THINK failed for step '{}', attempting LLM fallback: {}",
                    context.stepName(), e.getMessage());
            return thinkExecutor.execute(context, fallbackClient);
        }
    }

    private StepOutput executeLlmStep(StepExecutionContext context) {
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

    private static ReactAgent buildThinkAgent(ChatClient chatClient, String instruction) {
        return ReactAgent.builder()
                .name("think_executor")
                .chatClient(chatClient)
                .instruction(instruction)
                .hooks(ModelCallLimitHook.builder().runLimit(5).build())
                .build();
    }

    private static String readPromptContent(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + resource, e);
        }
    }
}
