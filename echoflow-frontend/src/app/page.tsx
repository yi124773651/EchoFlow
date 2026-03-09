import { TaskBoard } from "@/features/tasks/task-board";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center p-8">
      <h1 className="text-3xl font-bold tracking-tight">EchoFlow</h1>
      <p className="mt-2 mb-8 text-muted-foreground">
        AI 异步任务执行平台
      </p>
      <TaskBoard />
    </main>
  );
}
