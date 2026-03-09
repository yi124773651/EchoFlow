package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles NOTIFY steps by logging the notification.
 * Does not call any LLM — simply records that a notification was triggered.
 */
class LogNotifyExecutor {

    private static final Logger log = LoggerFactory.getLogger(LogNotifyExecutor.class);

    StepOutput execute(StepExecutionContext context) {
        log.info("Notification recorded for step: {}", context.stepName());
        return new StepOutput("Notification recorded: " + context.stepName());
    }
}
