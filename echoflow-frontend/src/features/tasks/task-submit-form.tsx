"use client";

import { useState } from "react";
import type { TaskDto } from "@/types/task";
import { taskService } from "@/services/task-service";
import { Button } from "@/components/ui/button";

export function TaskSubmitForm({
  onCreated,
}: {
  onCreated: (task: TaskDto) => void;
}) {
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!description.trim()) return;

    setSubmitting(true);
    setError(null);
    try {
      const task = await taskService.create(description.trim());
      setDescription("");
      onCreated(task);
    } catch (err) {
      setError(err instanceof Error ? err.message : "提交失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3">
      <textarea
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder="描述你的任务，例如：帮我调研 GitHub 上最近热门的 Java Agent 项目..."
        className="w-full rounded-lg border border-border bg-background px-4 py-3 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none min-h-[100px]"
        disabled={submitting}
      />
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button type="submit" disabled={submitting || !description.trim()}>
        {submitting ? "提交中..." : "提交任务"}
      </Button>
    </form>
  );
}
