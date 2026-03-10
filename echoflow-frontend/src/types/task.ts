/** Task status from backend */
export type TaskStatus = "SUBMITTED" | "EXECUTING" | "COMPLETED" | "FAILED";

/** Task list item */
export interface TaskDto {
  id: string;
  description: string;
  status: TaskStatus;
  createdAt: string;
  completedAt: string | null;
}

/** Execution status */
export type ExecutionStatus = "PLANNING" | "RUNNING" | "COMPLETED" | "FAILED";
export type StepStatus = "PENDING" | "RUNNING" | "COMPLETED" | "SKIPPED" | "FAILED";
export type StepType = "THINK" | "RESEARCH" | "WRITE" | "NOTIFY";
export type LogType = "THOUGHT" | "ACTION" | "OBSERVATION" | "ERROR";

/** Step log entry */
export interface LogSnapshot {
  type: LogType;
  content: string;
  loggedAt: string;
}

/** Step in execution */
export interface StepSnapshot {
  stepId: { value: string };
  order: number;
  name: string;
  type: StepType;
  status: StepStatus;
  output: string | null;
  logs: LogSnapshot[];
}

/** Execution snapshot */
export interface ExecutionSnapshot {
  executionId: { value: string };
  status: ExecutionStatus;
  startedAt: string;
  completedAt: string | null;
  steps: StepSnapshot[];
}

/** Task detail with execution */
export interface TaskDetailDto {
  taskId: { value: string };
  description: string;
  taskStatus: string;
  createdAt: string;
  completedAt: string | null;
  execution: ExecutionSnapshot | null;
}

// --- SSE Event types ---

export interface SseStepInfo {
  stepId: { value: string };
  order: number;
  name: string;
  type: string;
}

export interface ExecutionStartedEvent {
  executionId: { value: string };
  taskId: { value: string };
  steps: SseStepInfo[];
  timestamp: string;
}

export interface StepStartedEvent {
  executionId: { value: string };
  stepId: { value: string };
  name: string;
  timestamp: string;
}

export interface StepLogAppendedEvent {
  executionId: { value: string };
  stepId: { value: string };
  logType: LogType;
  content: string;
  timestamp: string;
}

export interface StepCompletedEvent {
  executionId: { value: string };
  stepId: { value: string };
  output: string;
  timestamp: string;
}

export interface StepSkippedEvent {
  executionId: { value: string };
  stepId: { value: string };
  reason: string;
  timestamp: string;
}

export interface ExecutionCompletedEvent {
  executionId: { value: string };
  timestamp: string;
}

export interface ExecutionFailedEvent {
  executionId: { value: string };
  reason: string;
  timestamp: string;
}
