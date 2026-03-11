package com.echoflow.infrastructure.ai;

import org.springframework.core.io.Resource;

/**
 * Executes WRITE steps. Passes previous context to synthesize
 * a comprehensive Markdown report.
 */
class LlmWriteExecutor extends LlmStepExecutor {

    LlmWriteExecutor(Resource promptTemplate) {
        super(promptTemplate);
    }
}
