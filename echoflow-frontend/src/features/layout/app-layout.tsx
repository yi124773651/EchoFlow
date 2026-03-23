"use client";

import { type ReactNode } from "react";
import { Sidebar } from "./sidebar";

export function AppLayout({
  sidebar,
  taskList,
  detail,
}: {
  sidebar: { onNewTask: () => void };
  taskList: ReactNode;
  detail: ReactNode;
}) {
  return (
    <div className="flex h-screen overflow-hidden bg-background">
      <Sidebar onNewTask={sidebar.onNewTask} />

      {/* Task List Panel */}
      <div className="flex h-screen w-[320px] flex-col border-r border-border">
        {taskList}
      </div>

      {/* Detail Panel */}
      <div className="flex h-screen flex-1 flex-col overflow-hidden">
        {detail}
      </div>
    </div>
  );
}
