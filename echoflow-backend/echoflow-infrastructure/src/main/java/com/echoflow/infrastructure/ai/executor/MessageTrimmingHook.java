package com.echoflow.infrastructure.ai.executor;

import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Trims message history to a maximum number of messages before
 * sending to the model. Prevents token overflow in long conversations.
 *
 * <p>Extends {@link MessagesModelHook} to intercept messages before model invocation.
 * When message count exceeds {@code maxMessages}, keeps only the most recent ones.</p>
 */
class MessageTrimmingHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(MessageTrimmingHook.class);

    private final int maxMessages;
    private int beforeModelCallCount = 0;
    private int afterModelCallCount = 0;

    MessageTrimmingHook(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public String getName() {
        return "MessageTrimmingHook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        beforeModelCallCount++;
        log.info("MessageTrimmingHook.beforeModel called (count={}), messages={}",
                beforeModelCallCount, previousMessages.size());

        if (previousMessages.size() > maxMessages) {
            List<Message> trimmed = previousMessages.subList(
                    previousMessages.size() - maxMessages, previousMessages.size());
            log.info("Trimmed messages from {} to {}", previousMessages.size(), trimmed.size());
            return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
        }
        return new AgentCommand(previousMessages);
    }

    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        afterModelCallCount++;
        log.info("MessageTrimmingHook.afterModel called (count={})", afterModelCallCount);
        return new AgentCommand(messages);
    }

    int beforeModelCallCount() {
        return beforeModelCallCount;
    }

    int afterModelCallCount() {
        return afterModelCallCount;
    }
}
