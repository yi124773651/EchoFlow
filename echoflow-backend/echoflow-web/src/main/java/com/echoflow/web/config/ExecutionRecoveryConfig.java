package com.echoflow.web.config;

import com.echoflow.application.execution.ExecutionRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Triggers execution recovery on application startup.
 *
 * <p>Orphaned RUNNING executions are marked FAILED.
 * WAITING_APPROVAL executions are resumed with new approval gates.</p>
 */
@Configuration
public class ExecutionRecoveryConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryConfig.class);

    @Bean
    ApplicationListener<ApplicationReadyEvent> executionRecoveryListener(
            ExecutionRecoveryService recoveryService) {
        return event -> {
            log.info("Application ready — initiating execution recovery");
            recoveryService.recoverInterruptedExecutions();
        };
    }
}
