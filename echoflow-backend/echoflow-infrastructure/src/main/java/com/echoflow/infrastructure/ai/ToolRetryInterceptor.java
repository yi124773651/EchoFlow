package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POC-3: Retries failed tool calls with exponential backoff.
 *
 * <p>Extends {@link ToolInterceptor} to intercept tool calls and retry on failure.
 * Uses the verified {@link ToolCallResponse#error(String, String, String)} factory method
 * for error responses.</p>
 */
class ToolRetryInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolRetryInterceptor.class);

    private final int maxRetries;
    private int interceptCallCount = 0;
    private int retryCount = 0;

    ToolRetryInterceptor(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    @Override
    public String getName() {
        return "ToolRetryInterceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        interceptCallCount++;
        log.info("ToolRetryInterceptor.interceptToolCall called (count={}), tool={}",
                interceptCallCount, request.getToolName());

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                ToolCallResponse response = handler.call(request);
                if (!response.isError()) {
                    return response;
                }
                log.warn("Tool call returned error on attempt {}: {}", attempt, response.getResult());
                lastException = new RuntimeException("Tool returned error: " + response.getResult());
            } catch (Exception e) {
                lastException = e;
                log.warn("Tool call failed on attempt {}/{}: {}",
                        attempt, maxRetries + 1, e.getMessage());
            }

            if (attempt <= maxRetries) {
                retryCount++;
                try {
                    Thread.sleep((long) Math.pow(2, attempt - 1) * 100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.error("Tool call exhausted {} retries for tool: {}",
                maxRetries, request.getToolName());
        return ToolCallResponse.error(
                request.getToolCallId(),
                request.getToolName(),
                "Tool call failed after " + (maxRetries + 1) + " attempts: " +
                        (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    int interceptCallCount() {
        return interceptCallCount;
    }

    int retryCount() {
        return retryCount;
    }
}
