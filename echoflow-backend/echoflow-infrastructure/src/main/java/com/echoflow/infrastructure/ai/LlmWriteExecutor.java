package com.echoflow.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * Executes WRITE steps. Passes previous context to synthesize
 * a comprehensive Markdown report.
 */
class LlmWriteExecutor extends LlmStepExecutor {

    LlmWriteExecutor(ChatClient chatClient, Resource promptTemplate) {
        super(chatClient, promptTemplate);
    }
}
