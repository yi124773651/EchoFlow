package com.echoflow.domain.execution;

/**
 * Value object representing a human approval decision for a step.
 */
public record ApprovalDecision(boolean approved, String reason) {

    public static final ApprovalDecision APPROVED = new ApprovalDecision(true, null);

    public static ApprovalDecision rejected(String reason) {
        return new ApprovalDecision(false, reason);
    }
}
