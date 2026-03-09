"use client";

import { useEffect, useRef, useCallback, useState } from "react";
import type {
  ExecutionStartedEvent,
  StepStartedEvent,
  StepLogAppendedEvent,
  StepCompletedEvent,
  ExecutionCompletedEvent,
  ExecutionFailedEvent,
  LogType,
} from "@/types/task";
import { taskService } from "@/services/task-service";

export type StepState = {
  stepId: string;
  order: number;
  name: string;
  type: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
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

export function useExecutionStream(taskId: string | null) {
  const [state, setState] = useState<ExecutionState>(initialState);
  const sourceRef = useRef<EventSource | null>(null);

  const connect = useCallback(() => {
    if (!taskId) return;

    // Close any existing connection
    sourceRef.current?.close();
    setState(initialState);

    const es = taskService.streamExecution(taskId);
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
      // SSE will auto-reconnect, but if the stream ended normally
      // (emitter.complete()), readyState will be CLOSED
      if (es.readyState === EventSource.CLOSED) {
        // normal close, do nothing
      }
    };
  }, [taskId]);

  useEffect(() => {
    connect();
    return () => {
      sourceRef.current?.close();
    };
  }, [connect]);

  return state;
}
