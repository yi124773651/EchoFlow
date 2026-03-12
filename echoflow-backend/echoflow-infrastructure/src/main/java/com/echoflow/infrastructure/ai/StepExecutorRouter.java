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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Routes step execution to the appropriate ReactAgent-backed executor based on
 * {@link StepType} and resolves the correct {@link ChatClient} for each step type
 * via multi-model routing.
 *
 * <p>All step types are executed via {@link ReactAgentStepExecutor} subclasses
 * (primary path). When the ReactAgent path fails, execution falls back to the
 * original {@link LlmStepExecutor} subclass with the fallback {@link ChatClient}.</p>
 *
 * <p>This is the single {@link StepExecutorPort} implementation registered as a
 * Spring component.</p>
 */
@Component
public class StepExecutorRouter implements StepExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(StepExecutorRouter.class);

    private final Map<StepType, ReactAgentStepExecutor> reactExecutors;
    private final Map<StepType, LlmStepExecutor> llmExecutors;
    private final Map<StepType, ChatClient> primaryClients;
    private final ChatClient fallbackClient;

    @Autowired
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
        // Tools
        var gitHubSearchTool = new GitHubSearchTool(
                githubBaseUrl, githubToken,
                githubConnectTimeout, githubReadTimeout,
                githubMaxResults);
        var webhookNotifyTool = new WebhookNotifyTool(
                webhookUrl, webhookConnectTimeout, webhookReadTimeout);

        // Routing
        var routing = properties.routing();
        this.primaryClients = Map.of(
                StepType.THINK, chatClientProvider.resolve(routing.aliasFor("think")),
                StepType.RESEARCH, chatClientProvider.resolve(routing.aliasFor("research")),
                StepType.WRITE, chatClientProvider.resolve(routing.aliasFor("write")),
                StepType.NOTIFY, chatClientProvider.resolve(routing.aliasFor("notify")));
        this.fallbackClient = chatClientProvider.resolve(routing.fallback());

        // Prompt content
        var thinkContent = readPromptContent(thinkPrompt);
        var researchContent = readPromptContent(researchPrompt);
        var writeContent = readPromptContent(writePrompt);
        var notifyContent = readPromptContent(notifyPrompt);

        // ReactAgent executors (primary path)
        this.reactExecutors = Map.of(
                StepType.THINK, new ReactAgentThinkExecutor(
                        primaryClients.get(StepType.THINK), thinkContent),
                StepType.RESEARCH, new ReactAgentResearchExecutor(
                        primaryClients.get(StepType.RESEARCH), researchContent, gitHubSearchTool),
                StepType.WRITE, new ReactAgentWriteExecutor(
                        primaryClients.get(StepType.WRITE), writeContent),
                StepType.NOTIFY, new ReactAgentNotifyExecutor(
                        primaryClients.get(StepType.NOTIFY), notifyContent, webhookNotifyTool));

        // LLM executors (fallback path)
        this.llmExecutors = Map.of(
                StepType.THINK, new LlmThinkExecutor(thinkPrompt),
                StepType.RESEARCH, new LlmResearchExecutor(researchPrompt, gitHubSearchTool),
                StepType.WRITE, new LlmWriteExecutor(writePrompt),
                StepType.NOTIFY, new LlmNotifyExecutor(notifyPrompt, webhookNotifyTool));
    }

    /**
     * Package-private constructor for testing — allows injecting pre-built executors.
     */
    StepExecutorRouter(Map<StepType, ReactAgentStepExecutor> reactExecutors,
                       Map<StepType, LlmStepExecutor> llmExecutors,
                       Map<StepType, ChatClient> primaryClients,
                       ChatClient fallbackClient) {
        this.reactExecutors = reactExecutors;
        this.llmExecutors = llmExecutors;
        this.primaryClients = primaryClients;
        this.fallbackClient = fallbackClient;
    }

    @Override
    public StepOutput execute(StepExecutionContext context) {
        var reactExecutor = reactExecutors.get(context.stepType());
        try {
            return reactExecutor.execute(context);
        } catch (StepExecutionException e) {
            var primaryClient = primaryClients.get(context.stepType());
            if (fallbackClient == primaryClient) {
                throw e;
            }
            log.warn("ReactAgent failed for step '{}' (type={}), fallback to LLM: {}",
                    context.stepName(), context.stepType(), e.getMessage());
            return llmExecutors.get(context.stepType()).execute(context, fallbackClient);
        }
    }

    private static String readPromptContent(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + resource, e);
        }
    }
}
