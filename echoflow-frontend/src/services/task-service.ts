import { api } from "./api";
import type { TaskDto, TaskDetailDto } from "@/types/task";

export const taskService = {
  create: (description: string) =>
    api.post<TaskDto>("/tasks", { description }),

  list: () => api.get<TaskDto[]>("/tasks"),

  detail: (taskId: string) => api.get<TaskDetailDto>(`/tasks/${taskId}`),

  streamExecution: (taskId: string) => {
    const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
    return new EventSource(`${API_BASE}/tasks/${taskId}/execution/stream`);
  },
};
