package com.echoflow.infrastructure.ai.executor;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepOutput;
import com.echoflow.infrastructure.ai.tool.WebhookNotifyTool;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes NOTIFY steps using ReactAgent with Webhook notification tool calling.
 *
 * <p>When the execution context carries a per-task {@code webhookUrl}, this executor
 * dynamically creates a {@link WebhookNotifyTool} copy with the task URL and builds
 * the ReactAgent with it. Otherwise, the default tool (from StepToolRegistry) is used.</p>
 */
class ReactAgentNotifyExecutor extends ReactAgentStepExecutor {

    private static final ThreadLocal<StepExecutionContext> CURRENT_CONTEXT = new ThreadLocal<>();

    ReactAgentNotifyExecutor(ChatClient chatClient, String promptContent,
                             List<Object> tools) {
        super(chatClient, promptContent, tools);
    }

    @Override
    protected String agentName() {
        return "notify_executor";
    }

    @Override
    StepOutput execute(StepExecutionContext context) {
        CURRENT_CONTEXT.set(context);
        try {
            return super.execute(context);
        } finally {
            CURRENT_CONTEXT.remove();
        }
    }

    @Override
    protected Builder configureAgent(Builder builder) {
        var effectiveTools = resolveTools();
        if (!effectiveTools.isEmpty()) {
            builder = builder
                    .methodTools(effectiveTools.toArray())
                    .interceptors(new ToolRetryInterceptor(2));
        }
        return builder;
    }

    private List<Object> resolveTools() {
        var ctx = CURRENT_CONTEXT.get();
        if (ctx == null || ctx.webhookUrl() == null) {
            return tools();
        }
        var resolved = new ArrayList<Object>(tools().size());
        for (var tool : tools()) {
            if (tool instanceof WebhookNotifyTool webhook) {
                resolved.add(webhook.withUrl(ctx.webhookUrl()));
            } else {
                resolved.add(tool);
            }
        }
        return resolved;
    }
}
