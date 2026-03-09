package com.echoflow.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * Executes RESEARCH steps. Passes previous context to build on
 * earlier analysis results.
 */
class LlmResearchExecutor extends LlmStepExecutor {

    LlmResearchExecutor(ChatClient chatClient, Resource promptTemplate) {
        super(chatClient, promptTemplate);
    }
}
