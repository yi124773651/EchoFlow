"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { Check, X, Minus, ChevronRight } from "lucide-react";
import { Collapsible } from "@base-ui/react/collapsible";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/button";
import { taskService } from "@/services/task-service";
import { StepLogs } from "./step-logs";
import type { StepState } from "@/hooks/use-execution-stream";

const STEP_TYPE_LABEL: Record<string, string> = {
  THINK: "思考",
  RESEARCH: "调研",
  WRITE: "撰写",
  NOTIFY: "通知",
};

function StatusDot({ status }: { status: StepState["status"] }) {
  const base = "relative z-10 flex h-6 w-6 items-center justify-center rounded-full border-2 bg-background";

  switch (status) {
    case "COMPLETED":
      return (
        <span className={cn(base, "border-emerald-500 bg-emerald-500")}>
          <Check className="h-3 w-3 text-white" />
        </span>
      );
    case "RUNNING":
      return (
        <span className={cn(base, "border-blue-500")}>
          <span className="h-2 w-2 animate-pulse rounded-full bg-blue-500" />
        </span>
      );
    case "WAITING_APPROVAL":
      return (
        <span className={cn(base, "border-amber-500 bg-amber-500")}>
          <Minus className="h-3 w-3 text-white" />
        </span>
      );
    case "FAILED":
      return (
        <span className={cn(base, "border-red-500 bg-red-500")}>
          <X className="h-3 w-3 text-white" />
        </span>
      );
    case "SKIPPED":
      return (
        <span className={cn(base, "border-muted-foreground")}>
          <Minus className="h-3 w-3 text-muted-foreground" />
        </span>
      );
    default: // PENDING
      return <span className={cn(base, "border-border")} />;
  }
}

function shouldAutoExpand(status: StepState["status"]): boolean {
  return status === "RUNNING" || status === "WAITING_APPROVAL" || status === "FAILED";
}

export function StepNode({
  step,
  taskId,
  isLast,
}: {
  step: StepState;
  taskId: string;
  isLast: boolean;
}) {
  const [approving, setApproving] = useState(false);
  const [manualOverride, setManualOverride] = useState<boolean | null>(null);
  const prevStatusRef = useRef(step.status);
  const nodeRef = useRef<HTMLDivElement>(null);

  // Reset override on status change + auto-scroll to running step
  useEffect(() => {
    if (prevStatusRef.current !== step.status) {
      setManualOverride(null);
      prevStatusRef.current = step.status;
      if (step.status === "RUNNING" && nodeRef.current) {
        nodeRef.current.scrollIntoView({ behavior: "smooth", block: "nearest" });
      }
    }
  }, [step.status]);

  const autoExpand = shouldAutoExpand(step.status);
  const isOpen = manualOverride ?? autoExpand;
  const hasContent = step.logs.length > 0 || step.output != null || step.status === "WAITING_APPROVAL";

  const lineStyle = cn(
    "absolute left-3 top-6 w-px",
    isLast ? "h-0" : "h-full",
    step.status === "PENDING" || step.status === "SKIPPED" ? "border-l border-dashed border-border" : "bg-border"
  );

  async function handleApprove() {
    setApproving(true);
    try { await taskService.approveStep(taskId); } finally { setApproving(false); }
  }

  async function handleReject() {
    setApproving(true);
    try { await taskService.rejectStep(taskId); } finally { setApproving(false); }
  }

  return (
    <div ref={nodeRef} className="relative flex gap-3">
      {/* Timeline line */}
      <div className={lineStyle} />

      {/* Status dot */}
      <div className="shrink-0">
        <StatusDot status={step.status} />
      </div>

      {/* Content */}
      <Collapsible.Root
        open={isOpen}
        onOpenChange={(open) => setManualOverride(open)}
        className="min-w-0 flex-1 pb-6"
      >
        <Collapsible.Trigger className="flex w-full items-center gap-2 text-left">
          <span className="text-sm font-medium">{step.name}</span>
          <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] uppercase text-muted-foreground">
            {STEP_TYPE_LABEL[step.type] ?? step.type}
          </span>
          {hasContent && (
            <ChevronRight
              className={cn(
                "ml-auto h-3.5 w-3.5 text-muted-foreground transition-transform",
                isOpen && "rotate-90"
              )}
            />
          )}
        </Collapsible.Trigger>

        <Collapsible.Panel>
          <div className="pr-2">
            {/* Running: show live logs */}
            {step.status === "RUNNING" && (
              <StepLogs
                logs={step.logs.map((l) => ({ type: l.type, content: l.content }))}
                isRunning
              />
            )}

            {/* Completed output */}
            {step.output && step.status === "COMPLETED" && (
              <div className="mt-2">
                {step.type === "WRITE" ? (
                  <div className="relative">
                    <button
                      onClick={() => navigator.clipboard.writeText(step.output!)}
                      className="absolute right-2 top-2 rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground hover:bg-muted transition-colors z-10"
                    >
                      复制
                    </button>
                    <div className="prose prose-sm prose-neutral dark:prose-invert max-w-none max-h-80 overflow-y-auto rounded-md border border-border p-3 pt-8">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {step.output}
                      </ReactMarkdown>
                    </div>
                  </div>
                ) : (
                  /* THINK / RESEARCH / NOTIFY: plain text summary */
                  <p className="text-sm text-foreground leading-relaxed">
                    {step.output}
                  </p>
                )}

                {/* Collapsed raw logs for THINK/RESEARCH (only if logs exist) */}
                {(step.type === "THINK" || step.type === "RESEARCH") && step.logs.length > 0 && (
                  <details className="mt-2">
                    <summary className="cursor-pointer text-xs text-muted-foreground hover:text-foreground transition-colors">
                      查看详情
                    </summary>
                    <div className="mt-1">
                      <StepLogs
                        logs={step.logs.map((l) => ({ type: l.type, content: l.content }))}
                        isRunning={false}
                      />
                    </div>
                  </details>
                )}
              </div>
            )}

            {/* Failed output */}
            {step.output && step.status === "FAILED" && (
              <p className="mt-2 rounded-md bg-red-50 dark:bg-red-950/30 p-2 text-xs text-red-600 dark:text-red-400">
                {step.output}
              </p>
            )}

            {/* Skipped output */}
            {step.output && step.status === "SKIPPED" && (
              <p className="mt-2 text-xs text-muted-foreground">
                已跳过: {step.output}
              </p>
            )}

            {/* Approval buttons */}
            {step.status === "WAITING_APPROVAL" && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  size="xs"
                  onClick={handleApprove}
                  disabled={approving}
                  className="bg-emerald-600 hover:bg-emerald-700 text-white"
                >
                  批准执行
                </Button>
                <Button
                  size="xs"
                  variant="outline"
                  onClick={handleReject}
                  disabled={approving}
                  className="text-red-600 border-red-200 hover:bg-red-50"
                >
                  拒绝跳过
                </Button>
              </div>
            )}
          </div>
        </Collapsible.Panel>
      </Collapsible.Root>
    </div>
  );
}
