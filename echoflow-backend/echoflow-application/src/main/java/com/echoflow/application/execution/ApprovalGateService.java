package com.echoflow.application.execution;

import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.ExecutionId;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages per-execution approval gates using {@link CompletableFuture}.
 *
 * <p>When a WRITE step requires human approval, {@link #createGate} creates a
 * future that blocks the virtual thread. The REST endpoint calls {@link #decide}
 * to complete the future, unblocking the execution.</p>
 */
@Service
public class ApprovalGateService {

    private final ConcurrentMap<ExecutionId, CompletableFuture<ApprovalDecision>> pendingApprovals
            = new ConcurrentHashMap<>();

    /**
     * Create a blocking gate for this execution.
     *
     * @throws IllegalStateException if a gate already exists for this execution
     */
    public CompletableFuture<ApprovalDecision> createGate(ExecutionId executionId) {
        var future = new CompletableFuture<ApprovalDecision>();
        var existing = pendingApprovals.putIfAbsent(executionId, future);
        if (existing != null) {
            throw new IllegalStateException("Approval gate already exists for " + executionId);
        }
        return future;
    }

    /**
     * Deliver an approval decision. Returns false if no pending gate exists.
     */
    public boolean decide(ExecutionId executionId, ApprovalDecision decision) {
        var future = pendingApprovals.remove(executionId);
        if (future == null) return false;
        return future.complete(decision);
    }

    /**
     * Check if an execution is currently awaiting approval.
     */
    public boolean isAwaitingApproval(ExecutionId executionId) {
        return pendingApprovals.containsKey(executionId);
    }

    /**
     * Cancel a pending gate (e.g. on execution failure or timeout).
     */
    public void cancel(ExecutionId executionId) {
        var future = pendingApprovals.remove(executionId);
        if (future != null) {
            future.cancel(true);
        }
    }
}
