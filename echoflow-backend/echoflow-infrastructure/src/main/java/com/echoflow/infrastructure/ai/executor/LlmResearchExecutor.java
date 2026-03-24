package com.echoflow.infrastructure.ai.executor;

import org.springframework.core.io.Resource;

/**
 * Executes RESEARCH steps as a degradation fallback (no tools).
 *
 * <p>When the primary ReactAgent path fails, this executor provides a pure-LLM
 * fallback without tool calling. The LLM generates research output based solely
 * on its parametric knowledge.</p>
 */
class LlmResearchExecutor extends LlmStepExecutor {

    LlmResearchExecutor(Resource promptTemplate) {
        super(promptTemplate);
    }
}
