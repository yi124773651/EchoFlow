package com.echoflow.infrastructure.ai.executor;

import org.springframework.core.io.Resource;

/**
 * Executes NOTIFY steps as a degradation fallback (no tools).
 *
 * <p>When the primary ReactAgent path fails, this executor provides a pure-LLM
 * fallback without tool calling. The LLM generates notification content based
 * solely on its parametric knowledge.</p>
 */
class LlmNotifyExecutor extends LlmStepExecutor {

    LlmNotifyExecutor(Resource promptTemplate) {
        super(promptTemplate);
    }
}
