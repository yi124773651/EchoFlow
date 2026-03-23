import { api } from "./api";
import type { TaskDto, TaskDetailDto } from "@/types/task";

export const taskService = {
  create: (description: string, webhookUrl?: string) =>
    api.post<TaskDto>("/tasks", { description, webhookUrl }),

  list: () => api.get<TaskDto[]>("/tasks"),

  detail: (taskId: string) => api.get<TaskDetailDto>(`/tasks/${taskId}`),

  streamExecution: (taskId: string) => {
    // SSE connects directly to the backend, bypassing Next.js proxy entirely.
    // Next.js rewrites and Route Handlers both buffer responses, breaking real-time SSE.
    // Backend has CORS configured for the SSE endpoint.
    const SSE_BASE = process.env.NEXT_PUBLIC_SSE_BASE
      ?? "http://localhost:8080/api";
    return new EventSource(`${SSE_BASE}/tasks/${taskId}/execution/stream`);
  },

  approveStep: (taskId: string) =>
    api.post<void>(`/tasks/${taskId}/execution/approve`),

  rejectStep: (taskId: string, reason?: string) =>
    api.post<void>(`/tasks/${taskId}/execution/reject`, reason ? { reason } : undefined),
};
