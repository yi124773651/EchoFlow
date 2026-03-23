import { cn } from "@/lib/utils";

export function ProgressBar({
  completed,
  total,
  failed,
}: {
  completed: number;
  total: number;
  failed: boolean;
}) {
  const percent = total === 0 ? 0 : Math.round((completed / total) * 100);

  return (
    <div className="flex items-center gap-3">
      <div className="h-1.5 flex-1 rounded-full bg-muted">
        <div
          className={cn(
            "h-full rounded-full transition-all duration-500 ease-out",
            failed ? "bg-red-500" : "bg-emerald-500"
          )}
          style={{ width: `${percent}%` }}
        />
      </div>
      <span className="text-xs tabular-nums text-muted-foreground">
        {completed}/{total}
      </span>
    </div>
  );
}
