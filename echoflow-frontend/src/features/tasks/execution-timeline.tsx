"use client";

import { useExecutionStream, type StepState } from "@/hooks/use-execution-stream";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

const STATUS_ICON: Record<string, string> = {
  PENDING: "\u25CB",   // ○
  RUNNING: "\u25D4",   // ◔
  COMPLETED: "\u25CF", // ●
  FAILED: "\u2716",    // ✖
};

const LOG_PREFIX: Record<string, string> = {
  THOUGHT: "\uD83D\uDCAD",   // 💭
  ACTION: "\u26A1",           // ⚡
  OBSERVATION: "\uD83D\uDC41\uFE0F", // 👁️
  ERROR: "\u274C",            // ❌
};

function StepCard({ step }: { step: StepState }) {
  const statusColor =
    step.status === "COMPLETED"
      ? "text-green-600"
      : step.status === "RUNNING"
        ? "text-blue-600"
        : step.status === "FAILED"
          ? "text-red-600"
          : "text-muted-foreground";

  return (
    <div className="rounded-lg border border-border p-4">
      <div className="flex items-center gap-2">
        <span className={`text-lg ${statusColor}`}>
          {STATUS_ICON[step.status] ?? "?"}
        </span>
        <span className="font-medium text-sm">{step.name}</span>
        <span className="ml-auto text-xs text-muted-foreground uppercase">
          {step.type}
        </span>
      </div>

      {step.logs.length > 0 && (
        <div className="mt-3 space-y-1 pl-7">
          {step.logs.map((log, i) => (
            <div key={i} className="flex items-start gap-2 text-xs">
              <span>{LOG_PREFIX[log.type] ?? "?"}</span>
              <span className="text-muted-foreground">{log.content}</span>
            </div>
          ))}
        </div>
      )}

      {step.output && step.status === "COMPLETED" && (
        <div className="mt-3 pl-7">
          {step.type === "WRITE" ? (
            <div className="prose prose-sm prose-neutral dark:prose-invert max-w-none max-h-96 overflow-y-auto rounded border border-border p-3">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {step.output}
              </ReactMarkdown>
            </div>
          ) : (
            <pre className="text-xs bg-muted rounded p-2 whitespace-pre-wrap overflow-x-auto max-h-40">
              {step.output}
            </pre>
          )}
        </div>
      )}

      {step.output && step.status === "FAILED" && (
        <div className="mt-3 pl-7">
          <p className="text-xs text-red-600">{step.output}</p>
        </div>
      )}
    </div>
  );
}

export function ExecutionTimeline({ taskId }: { taskId: string }) {
  const execution = useExecutionStream(taskId);

  if (execution.status === "IDLE") {
    return (
      <div className="text-center text-sm text-muted-foreground py-8">
        等待执行开始...
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-sm">
        <span className="font-medium">执行状态:</span>
        <span
          className={
            execution.status === "COMPLETED"
              ? "text-green-600"
              : execution.status === "FAILED"
                ? "text-red-600"
                : "text-blue-600"
          }
        >
          {execution.status}
        </span>
      </div>

      {execution.error && (
        <p className="text-sm text-red-600 bg-red-50 rounded p-2">
          {execution.error}
        </p>
      )}

      <div className="space-y-2">
        {execution.steps.map((step) => (
          <StepCard key={step.stepId} step={step} />
        ))}
      </div>
    </div>
  );
}
