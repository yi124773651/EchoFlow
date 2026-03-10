"use client";

import { useState, useEffect, useCallback } from "react";
import type { TaskDto } from "@/types/task";
import { taskService } from "@/services/task-service";
import { TaskSubmitForm } from "@/features/tasks/task-submit-form";
import { ExecutionTimeline } from "@/features/tasks/execution-timeline";

const STATUS_LABEL: Record<string, string> = {
  SUBMITTED: "已提交",
  EXECUTING: "执行中",
  COMPLETED: "已完成",
  FAILED: "失败",
};

const STATUS_COLOR: Record<string, string> = {
  SUBMITTED: "bg-yellow-100 text-yellow-800",
  EXECUTING: "bg-blue-100 text-blue-800",
  COMPLETED: "bg-green-100 text-green-800",
  FAILED: "bg-red-100 text-red-800",
};

export function TaskBoard() {
  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadTasks = useCallback(async () => {
    try {
      const list = await taskService.list();
      setTasks(list);
    } catch {
      // silently fail for now
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTasks();
  }, [loadTasks]);

  function handleTaskCreated(taskId: string) {
    setSelectedTaskId(taskId);
    // Immediately add the new task to the list optimistically, then reload
    loadTasks();
  }

  function handleExecutionDone() {
    // Refresh task list when execution completes to update status badges
    loadTasks();
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 w-full max-w-6xl">
      {/* Left: Task submission + list */}
      <div className="space-y-6">
        <section>
          <h2 className="text-lg font-semibold mb-3">新建任务</h2>
          <TaskSubmitForm onCreated={handleTaskCreated} />
        </section>

        <section>
          <h2 className="text-lg font-semibold mb-3">任务列表</h2>
          {loading ? (
            <p className="text-sm text-muted-foreground">加载中...</p>
          ) : tasks.length === 0 ? (
            <p className="text-sm text-muted-foreground">暂无任务</p>
          ) : (
            <div className="space-y-2">
              {tasks.map((task) => (
                <button
                  key={task.id}
                  onClick={() => setSelectedTaskId(task.id)}
                  className={`w-full text-left rounded-lg border p-3 transition-colors hover:bg-muted/50 ${
                    selectedTaskId === task.id
                      ? "border-ring ring-1 ring-ring"
                      : "border-border"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium truncate flex-1 mr-2">
                      {task.description}
                    </span>
                    <span
                      className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${
                        STATUS_COLOR[task.status] ?? ""
                      }`}
                    >
                      {STATUS_LABEL[task.status] ?? task.status}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">
                    {new Date(task.createdAt).toLocaleString("zh-CN")}
                  </p>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>

      {/* Right: Execution timeline */}
      <div>
        <h2 className="text-lg font-semibold mb-3">执行详情</h2>
        {selectedTaskId ? (
          <ExecutionTimeline taskId={selectedTaskId} onDone={handleExecutionDone} />
        ) : (
          <p className="text-sm text-muted-foreground">
            选择一个任务查看执行详情
          </p>
        )}
      </div>
    </div>
  );
}
