"use client";

import { useState, useEffect, useCallback } from "react";
import type { TaskDto, TaskStatus } from "@/types/task";
import type { ExecutionState } from "@/hooks/use-execution-stream";
import { taskService } from "@/services/task-service";
import { AppLayout } from "@/features/layout/app-layout";
import { TaskList } from "@/features/tasks/task-list";
import { ExecutionDetail } from "@/features/tasks/execution-detail";
import { TaskSubmitDialog } from "@/features/tasks/task-submit-form";

const EXEC_TO_TASK_STATUS: Partial<Record<ExecutionState["status"], TaskStatus>> = {
  RUNNING: "EXECUTING",
  COMPLETED: "COMPLETED",
  FAILED: "FAILED",
};

export default function Home() {
  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [mobileShowDetail, setMobileShowDetail] = useState(false);

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

  function handleSelect(taskId: string) {
    setSelectedTaskId(taskId);
    setMobileShowDetail(true);
  }

  function handleTaskCreated(task: TaskDto) {
    setSelectedTaskId(task.id);
    setMobileShowDetail(true);
    setTasks((prev) => [task, ...prev.filter((t) => t.id !== task.id)]);
    loadTasks();
  }

  const handleStatusChange = useCallback(
    (taskId: string, execStatus: ExecutionState["status"]) => {
      const newTaskStatus = EXEC_TO_TASK_STATUS[execStatus];
      if (newTaskStatus) {
        setTasks((prev) =>
          prev.map((t) => (t.id === taskId ? { ...t, status: newTaskStatus } : t)),
        );
      }
      if (execStatus === "COMPLETED" || execStatus === "FAILED") {
        loadTasks();
      }
    },
    [loadTasks],
  );

  const selectedTask = tasks.find((t) => t.id === selectedTaskId);

  return (
    <>
      <AppLayout
        sidebar={{ onNewTask: () => setDialogOpen(true) }}
        mobileShowDetail={mobileShowDetail}
        onMobileBack={() => setMobileShowDetail(false)}
        taskList={
          <TaskList
            tasks={tasks}
            loading={loading}
            selectedTaskId={selectedTaskId}
            onSelect={handleSelect}
          />
        }
        detail={
          selectedTask ? (
            <ExecutionDetail
              taskId={selectedTask.id}
              taskDescription={selectedTask.description}
              onStatusChange={handleStatusChange}
            />
          ) : (
            <div className="flex h-full items-center justify-center">
              <p className="text-sm text-muted-foreground">
                选择一个任务查看执行详情
              </p>
            </div>
          )
        }
      />
      <TaskSubmitDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={handleTaskCreated}
      />
    </>
  );
}
