package com.echoflow.application.execution;

/**
 * Port for publishing execution events (SSE, WebSocket, etc.).
 * Implementation lives in Infrastructure or Web layer.
 */
public interface ExecutionEventPublisher {

    void publish(ExecutionEvent event);
}
