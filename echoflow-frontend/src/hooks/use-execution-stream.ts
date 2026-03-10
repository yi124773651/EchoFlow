"use client";

import { useEffect, useRef, useState } from "react";
import type {
  ExecutionStartedEvent,
  StepStartedEvent,
  StepLogAppendedEvent,
  StepCompletedEvent,
  ExecutionCompletedEvent,
  ExecutionFailedEvent,
  ExecutionSnapshot,
  LogType,
} from "@/types/task";
import { taskService } from "@/services/task-service";

export type StepState = {
  stepId: string;
  order: number;
  name: string;
  type: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "SKIPPED" | "FAILED";
  output: string | null;
  logs: { type: LogType; content: string; timestamp: string }[];
};

export type ExecutionState = {
  executionId: string | null;
  status: "IDLE" | "RUNNING" | "COMPLETED" | "FAILED";
  steps: StepState[];
  error: string | null;
};

const initialState: ExecutionState = {
  executionId: null,
  status: "IDLE",
  steps: [],
  error: null,
};

function snapshotToState(exec: ExecutionSnapshot): ExecutionState {
  return {
    executionId: exec.executionId.value,
    status: exec.status === "PLANNING" ? "RUNNING" : exec.status as ExecutionState["status"],
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

export function useExecutionStream(taskId: string | null) {
  const [state, setState] = useState<ExecutionState>(initialState);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!taskId) {
      setState(initialState);
      return;
    }

    // Cleanup previous connection
    sourceRef.current?.close();
    sourceRef.current = null;
    setState(initialState);

    let cancelled = false;

    // 1. Load current state via REST API first
    taskService.detail(taskId).then((detail) => {
      if (cancelled) return;

      if (detail.execution) {
        const loaded = snapshotToState(detail.execution);
        setState(loaded);

        // Only connect SSE if execution is still in progress
        if (detail.execution.status === "RUNNING" || detail.execution.status === "PLANNING") {
          connectSse(taskId);
        }
      } else {
        // No execution yet (SUBMITTED), connect SSE to wait for it
        connectSse(taskId);
      }
    }).catch(() => {
      if (cancelled) return;
      // REST failed, try SSE directly as fallback
      connectSse(taskId);
    });

    function connectSse(tid: string) {
      const es = taskService.streamExecution(tid);
      sourceRef.current = es;

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

      es.addEventListener("ExecutionCompleted", (e) => {
        const _data: ExecutionCompletedEvent = JSON.parse(e.data);
        setState((prev) => ({ ...prev, status: "COMPLETED" }));
        es.close();
      });

      es.addEventListener("ExecutionFailed", (e) => {
        const data: ExecutionFailedEvent = JSON.parse(e.data);
        setState((prev) => ({
          ...prev,
          status: "FAILED",
          error: data.reason,
        }));
        es.close();
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) {
          // normal close after completion
        }
      };
    }

    return () => {
      cancelled = true;
      sourceRef.current?.close();
      sourceRef.current = null;
    };
  }, [taskId]);

  return state;
}
