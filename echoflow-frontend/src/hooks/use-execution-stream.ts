"use client";

import { useEffect, useRef, useState } from "react";
import type {
  ExecutionStartedEvent,
  StepStartedEvent,
  StepLogAppendedEvent,
  StepCompletedEvent,
  ExecutionFailedEvent,
  StepAwaitingApprovalEvent,
  StepApprovalDecidedEvent,
  ExecutionSnapshot,
  LogType,
} from "@/types/task";
import { taskService } from "@/services/task-service";

export type StepState = {
  stepId: string;
  order: number;
  name: string;
  type: string;
  status: "PENDING" | "RUNNING" | "WAITING_APPROVAL" | "COMPLETED" | "SKIPPED" | "FAILED";
  output: string | null;
  logs: { type: LogType; content: string; timestamp: string }[];
};

export type ExecutionState = {
  executionId: string | null;
  status: "IDLE" | "RUNNING" | "WAITING_APPROVAL" | "COMPLETED" | "FAILED";
  steps: StepState[];
  error: string | null;
};

const initialState: ExecutionState = {
  executionId: null,
  status: "IDLE",
  steps: [],
  error: null,
};

const STEP_STATUS_ORDER: Record<string, number> = {
  PENDING: 0,
  RUNNING: 1,
  WAITING_APPROVAL: 2,
  COMPLETED: 3,
  SKIPPED: 3,
  FAILED: 3,
};

function snapshotToState(exec: ExecutionSnapshot): ExecutionState {
  return {
    executionId: exec.executionId.value,
    status: exec.status === "PLANNING" ? "RUNNING" : (exec.status as ExecutionState["status"]),
    error: null,
    steps: exec.steps.map((s) => ({
      stepId: s.stepId.value,
      order: s.order,
      name: s.name,
      type: s.type,
      status: s.status as StepState["status"],
      output: s.output,
      logs: s.logs.map((l) => ({
        type: l.type,
        content: l.content,
        timestamp: l.loggedAt,
      })),
    })),
  };
}

/** Merge REST snapshot into SSE state, keeping the more advanced state. */
function reconcile(
  sseState: ExecutionState,
  snapshot: ExecutionSnapshot,
): ExecutionState {
  const snapped = snapshotToState(snapshot);

  // SSE already received ExecutionStarted — merge per-step
  if (sseState.executionId) {
    return {
      ...sseState,
      steps: sseState.steps.map((sseStep) => {
        const snapStep = snapped.steps.find((s) => s.stepId === sseStep.stepId);
        if (!snapStep) return sseStep;
        const sseOrder = STEP_STATUS_ORDER[sseStep.status] ?? 0;
        const snapOrder = STEP_STATUS_ORDER[snapStep.status] ?? 0;
        if (snapOrder > sseOrder) return snapStep;
        return {
          ...sseStep,
          logs: sseStep.logs.length >= snapStep.logs.length ? sseStep.logs : snapStep.logs,
        };
      }),
    };
  }

  // SSE hasn't received anything yet — use REST snapshot as-is
  return snapped;
}

function attachSseHandlers(
  es: EventSource,
  setState: React.Dispatch<React.SetStateAction<ExecutionState>>,
  onTerminal: () => void,
) {
  es.addEventListener("ExecutionStarted", (e) => {
    const data: ExecutionStartedEvent = JSON.parse(e.data);
    setState({
      executionId: data.executionId.value,
      status: "RUNNING",
      error: null,
      steps: data.steps.map((s) => ({
        stepId: s.stepId.value,
        order: s.order,
        name: s.name,
        type: s.type,
        status: "PENDING",
        output: null,
        logs: [],
      })),
    });
  });

  es.addEventListener("StepStarted", (e) => {
    const data: StepStartedEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value ? { ...s, status: "RUNNING" } : s,
      ),
    }));
  });

  es.addEventListener("StepLogAppended", (e) => {
    const data: StepLogAppendedEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? {
              ...s,
              logs: [
                ...s.logs,
                {
                  type: data.logType,
                  content: data.content,
                  timestamp: data.timestamp,
                },
              ],
            }
          : s,
      ),
    }));
  });

  es.addEventListener("StepCompleted", (e) => {
    const data: StepCompletedEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? { ...s, status: "COMPLETED", output: data.output }
          : s,
      ),
    }));
  });

  es.addEventListener("StepFailed", (e) => {
    const data = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? { ...s, status: "FAILED", output: data.reason }
          : s,
      ),
    }));
  });

  es.addEventListener("StepSkipped", (e) => {
    const data = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? { ...s, status: "SKIPPED", output: data.reason }
          : s,
      ),
    }));
  });

  es.addEventListener("StepAwaitingApproval", (e) => {
    const data: StepAwaitingApprovalEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      status: "WAITING_APPROVAL",
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? { ...s, status: "WAITING_APPROVAL" }
          : s,
      ),
    }));
  });

  es.addEventListener("StepApprovalDecided", (e) => {
    const data: StepApprovalDecidedEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      status: "RUNNING",
      steps: prev.steps.map((s) =>
        s.stepId === data.stepId.value
          ? { ...s, status: data.approved ? "RUNNING" : "SKIPPED" }
          : s,
      ),
    }));
  });

  es.addEventListener("ExecutionCompleted", (e) => {
    JSON.parse(e.data); // validate
    setState((prev) => ({ ...prev, status: "COMPLETED" }));
    es.close();
    onTerminal();
  });

  es.addEventListener("ExecutionFailed", (e) => {
    const data: ExecutionFailedEvent = JSON.parse(e.data);
    setState((prev) => ({
      ...prev,
      status: "FAILED",
      error: data.reason,
    }));
    es.close();
    onTerminal();
  });

  es.onerror = () => {
    if (es.readyState === EventSource.CLOSED) {
      // normal close after completion
    }
  };
}

export function useExecutionStream(taskId: string | null) {
  const [state, setState] = useState<ExecutionState>(initialState);
  const sourceRef = useRef<EventSource | null>(null);

  // Reset state during render when taskId changes (React 19 recommended pattern)
  const [prevTaskId, setPrevTaskId] = useState<string | null>(null);
  if (taskId !== prevTaskId) {
    setPrevTaskId(taskId);
    setState(initialState);
  }

  useEffect(() => {
    if (!taskId) {
      return;
    }

    // Cleanup previous connection
    sourceRef.current?.close();
    sourceRef.current = null;

    let cancelled = false;

    // 1. SSE FIRST — ensure emitter is registered before backend publishes
    const es = taskService.streamExecution(taskId);
    sourceRef.current = es;
    attachSseHandlers(es, setState, () => {
      sourceRef.current = null;
    });

    // 2. REST reconcile — catch up on any state missed before SSE connected
    taskService.detail(taskId).then((detail) => {
      if (cancelled) return;

      if (detail.execution) {
        setState((prev) => reconcile(prev, detail.execution!));

        // Terminal state: close SSE
        if (detail.execution.status === "COMPLETED" || detail.execution.status === "FAILED") {
          es.close();
          sourceRef.current = null;
        }
      }
      // No execution yet — SSE is already listening, nothing to do
    }).catch(() => {
      // REST failed — SSE is already connected as fallback
    });

    return () => {
      cancelled = true;
      sourceRef.current?.close();
      sourceRef.current = null;
    };
  }, [taskId]);

  return state;
}
