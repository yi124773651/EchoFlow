"use client";

import { cn } from "@/lib/utils";
import type { TaskStatus } from "@/types/task";

const STATUS_CONFIG: Record<TaskStatus, { label: string; dotColor: string }> = {
  SUBMITTED: { label: "已提交", dotColor: "bg-amber-400" },
  EXECUTING: { label: "执行中", dotColor: "bg-blue-500" },
  COMPLETED: { label: "已完成", dotColor: "bg-emerald-500" },
  FAILED: { label: "失败", dotColor: "bg-red-500" },
};

function timeAgo(dateStr: string): string {
  const seconds = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000);
  if (seconds < 60) return "刚刚";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  return `${days} 天前`;
}

export function TaskCard({
  description,
  status,
  createdAt,
  selected,
  onClick,
}: {
  description: string;
  status: TaskStatus;
  createdAt: string;
  selected: boolean;
  onClick: () => void;
}) {
  const config = STATUS_CONFIG[status];

  return (
    <button
      onClick={onClick}
      className={cn(
        "group flex w-full flex-col gap-1 border-l-2 px-3 py-2.5 text-left transition-colors",
        selected
          ? "border-l-primary bg-accent"
          : "border-l-transparent hover:bg-muted/50"
      )}
    >
      <p className="line-clamp-2 text-sm font-medium leading-snug">
        {description}
      </p>
      <div className="flex items-center gap-2">
        <span className={cn("h-1.5 w-1.5 rounded-full", config.dotColor)} />
        <span className="text-xs text-muted-foreground">{config.label}</span>
        <span className="text-xs text-muted-foreground">·</span>
        <span className="text-xs text-muted-foreground">{timeAgo(createdAt)}</span>
      </div>
    </button>
  );
}
