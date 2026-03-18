package com.echoflow.application.execution;

import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.ExecutionId;
import org.springframework.stereotype.Service;

/**
 * Use case: approve or reject a step that is waiting for human approval.
 * Called from the REST endpoint to unblock the virtual thread.
 */
@Service
public class ApproveStepUseCase {

    private final ApprovalGateService approvalGateService;

    public ApproveStepUseCase(ApprovalGateService approvalGateService) {
        this.approvalGateService = approvalGateService;
    }

    public boolean approve(ExecutionId executionId) {
        return approvalGateService.decide(executionId, ApprovalDecision.APPROVED);
    }

    public boolean reject(ExecutionId executionId, String reason) {
        return approvalGateService.decide(executionId, ApprovalDecision.rejected(reason));
    }
}
