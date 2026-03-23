"use client";

import { useEffect, useRef } from "react";
import { useExecutionStream, type ExecutionState } from "@/hooks/use-execution-stream";
import { ProgressBar } from "./progress-bar";
import { StepNode } from "./step-node";

const STATUS_LABEL: Record<ExecutionState["status"], string> = {
  IDLE: "等待执行",
  RUNNING: "执行中",
  WAITING_APPROVAL: "等待审批",
  COMPLETED: "已完成",
  FAILED: "执行失败",
};

export function ExecutionDetail({
  taskId,
  taskDescription,
  onStatusChange,
}: {
  taskId: string;
  taskDescription: string;
  onStatusChange?: (taskId: string, status: ExecutionState["status"]) => void;
}) {
  const execution = useExecutionStream(taskId);
  const prevStatusRef = useRef(execution.status);

  useEffect(() => {
    if (prevStatusRef.current !== execution.status) {
      prevStatusRef.current = execution.status;
      onStatusChange?.(taskId, execution.status);
    }
  }, [execution.status, taskId, onStatusChange]);

  const completedCount = execution.steps.filter(
    (s) => s.status === "COMPLETED" || s.status === "SKIPPED"
  ).length;
  const isFailed = execution.status === "FAILED";

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-border px-6 py-4">
        <p className="text-sm font-medium leading-relaxed">{taskDescription}</p>
        <div className="mt-2 flex items-center gap-2">
          <span
            className={
              execution.status === "COMPLETED"
                ? "text-emerald-600"
                : execution.status === "FAILED"
                  ? "text-red-600"
                  : execution.status === "WAITING_APPROVAL"
                    ? "text-amber-600"
                    : "text-blue-600"
            }
          >
            <span className="text-xs font-medium">
              {STATUS_LABEL[execution.status]}
            </span>
          </span>
        </div>
        {execution.steps.length > 0 && (
          <div className="mt-3">
            <ProgressBar
              completed={completedCount}
              total={execution.steps.length}
              failed={isFailed}
            />
          </div>
        )}
      </div>

      {/* Error banner */}
      {execution.error && (
        <div className="mx-6 mt-4 rounded-md bg-red-50 dark:bg-red-950/30 p-3 text-xs text-red-600 dark:text-red-400">
          {execution.error}
        </div>
      )}

      {/* Step timeline */}
      <div className="flex-1 overflow-y-auto px-6 py-4">
        {execution.status === "IDLE" ? (
          <div className="flex h-full items-center justify-center">
            <div className="text-center">
              <div className="flex items-center justify-center gap-1.5 mb-2">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-muted-foreground" />
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-muted-foreground [animation-delay:150ms]" />
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-muted-foreground [animation-delay:300ms]" />
              </div>
              <p className="text-sm text-muted-foreground">等待执行开始...</p>
            </div>
          </div>
        ) : (
          execution.steps.map((step, i) => (
            <StepNode
              key={step.stepId}
              step={step}
              taskId={taskId}
              isLast={i === execution.steps.length - 1}
            />
          ))
        )}
      </div>
    </div>
  );
}
