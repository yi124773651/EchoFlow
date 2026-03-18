package com.echoflow.application.execution;

import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.ExecutionId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ApprovalGateServiceTest {

    private final ApprovalGateService service = new ApprovalGateService();

    @Test
    void createGate_returns_incomplete_future() {
        var id = ExecutionId.generate();
        var gate = service.createGate(id);
        assertThat(gate).isNotDone();
    }

    @Test
    void decide_approved_completes_future() throws Exception {
        var id = ExecutionId.generate();
        var gate = service.createGate(id);
        var result = service.decide(id, ApprovalDecision.APPROVED);

        assertThat(result).isTrue();
        assertThat(gate.get(1, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
    }

    @Test
    void decide_rejected_completes_future() throws Exception {
        var id = ExecutionId.generate();
        var gate = service.createGate(id);
        var decision = ApprovalDecision.rejected("不合格");
        service.decide(id, decision);

        assertThat(gate.get(1, TimeUnit.SECONDS)).isEqualTo(decision);
    }

    @Test
    void decide_returns_false_when_no_pending_gate() {
        var result = service.decide(ExecutionId.generate(), ApprovalDecision.APPROVED);
        assertThat(result).isFalse();
    }

    @Test
    void createGate_throws_when_gate_already_exists() {
        var id = ExecutionId.generate();
        service.createGate(id);
        assertThatIllegalStateException()
                .isThrownBy(() -> service.createGate(id));
    }

    @Test
    void cancel_removes_gate_and_cancels_future() {
        var id = ExecutionId.generate();
        var gate = service.createGate(id);
        service.cancel(id);

        assertThat(gate.isCancelled()).isTrue();
        assertThat(service.isAwaitingApproval(id)).isFalse();
    }

    @Test
    void isAwaitingApproval_true_when_gate_exists() {
        var id = ExecutionId.generate();
        service.createGate(id);
        assertThat(service.isAwaitingApproval(id)).isTrue();
    }

    @Test
    void isAwaitingApproval_false_after_decide() {
        var id = ExecutionId.generate();
        service.createGate(id);
        service.decide(id, ApprovalDecision.APPROVED);
        assertThat(service.isAwaitingApproval(id)).isFalse();
    }
}
