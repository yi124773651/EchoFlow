import { cn } from "@/lib/utils";
import type { LogType } from "@/types/task";

const LOG_STYLES: Record<LogType, { icon: string; className: string }> = {
  THOUGHT: { icon: "💭", className: "text-muted-foreground italic" },
  ACTION: { icon: "⚡", className: "text-blue-600 dark:text-blue-400 font-mono" },
  OBSERVATION: { icon: "👁", className: "text-foreground" },
  ERROR: { icon: "✖", className: "text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-950/30 rounded px-1" },
};

export function StepLogs({
  logs,
  isRunning,
}: {
  logs: { type: LogType; content: string }[];
  isRunning: boolean;
}) {
  if (logs.length === 0 && !isRunning) return null;

  return (
    <div className="space-y-1 pt-2">
      {logs.map((log, i) => {
        const style = LOG_STYLES[log.type] ?? LOG_STYLES.OBSERVATION;
        return (
          <div key={i} className="flex items-start gap-2 text-xs leading-relaxed">
            <span className="mt-0.5 shrink-0">{style.icon}</span>
            <span className={cn("break-words", style.className)}>{log.content}</span>
          </div>
        );
      })}
      {isRunning && (
        <div className="flex items-center gap-1.5 pt-1">
          <span className="h-1 w-1 animate-pulse rounded-full bg-blue-500" />
          <span className="h-1 w-1 animate-pulse rounded-full bg-blue-500 [animation-delay:150ms]" />
          <span className="h-1 w-1 animate-pulse rounded-full bg-blue-500 [animation-delay:300ms]" />
        </div>
      )}
    </div>
  );
}
