"use client";

import type { TaskDto } from "@/types/task";
import { TaskCard } from "./task-card";

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
  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex h-14 items-center justify-between border-b border-border px-4">
        <h2 className="text-sm font-semibold">任务</h2>
        <span className="text-xs text-muted-foreground">{tasks.length}</span>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <span className="text-sm text-muted-foreground">加载中...</span>
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <span className="text-sm text-muted-foreground">暂无任务</span>
          </div>
        ) : (
          <div className="divide-y divide-border">
            {tasks.map((task) => (
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
