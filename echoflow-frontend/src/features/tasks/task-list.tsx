"use client";

import { useState } from "react";
import { Search } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TaskDto, TaskStatus } from "@/types/task";
import { TaskCard } from "./task-card";

type FilterStatus = "ALL" | TaskStatus;

const FILTERS: { value: FilterStatus; label: string }[] = [
  { value: "ALL", label: "全部" },
  { value: "EXECUTING", label: "执行中" },
  { value: "COMPLETED", label: "已完成" },
  { value: "FAILED", label: "失败" },
];

export function TaskList({
  tasks,
  loading,
  selectedTaskId,
  onSelect,
}: {
  tasks: TaskDto[];
  loading: boolean;
  selectedTaskId: string | null;
  onSelect: (taskId: string) => void;
}) {
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<FilterStatus>("ALL");

  const filtered = tasks.filter((t) => {
    if (filter !== "ALL" && t.status !== filter) return false;
    if (search && !t.description.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex h-14 items-center justify-between border-b border-border px-4">
        <h2 className="text-sm font-semibold">任务</h2>
        <span className="text-xs text-muted-foreground">{filtered.length}</span>
      </div>

      {/* Search */}
      <div className="border-b border-border px-3 py-2">
        <div className="flex items-center gap-2 rounded-md bg-muted px-2.5 py-1.5">
          <Search className="h-3.5 w-3.5 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="搜索任务..."
            className="flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground"
          />
        </div>
      </div>

      {/* Filter tabs */}
      <div className="flex gap-1 border-b border-border px-3 py-2">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => setFilter(f.value)}
            className={cn(
              "rounded-md px-2 py-1 text-xs transition-colors",
              filter === f.value
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-muted"
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <span className="text-sm text-muted-foreground">加载中...</span>
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <span className="text-sm text-muted-foreground">
              {tasks.length === 0 ? "暂无任务" : "无匹配结果"}
            </span>
          </div>
        ) : (
          <div className="divide-y divide-border">
            {filtered.map((task) => (
              <TaskCard
                key={task.id}
                description={task.description}
                status={task.status}
                createdAt={task.createdAt}
                selected={selectedTaskId === task.id}
                onClick={() => onSelect(task.id)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
