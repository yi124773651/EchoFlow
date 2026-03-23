"use client";

import { LayoutList, Plus } from "lucide-react";
import { cn } from "@/lib/utils";

export function Sidebar({ onNewTask }: { onNewTask: () => void }) {
  return (
    <aside className="flex h-screen w-[200px] flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground">
      {/* Brand */}
      <div className="flex h-14 items-center px-4">
        <span className="text-sm font-semibold tracking-tight">EchoFlow</span>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-2 py-2">
        <button
          className={cn(
            "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm font-medium",
            "bg-sidebar-accent text-sidebar-accent-foreground"
          )}
        >
          <LayoutList className="h-4 w-4" />
          任务
        </button>
      </nav>

      {/* Bottom: New Task */}
      <div className="border-t border-sidebar-border p-3">
        <button
          onClick={onNewTask}
          className={cn(
            "flex w-full items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium",
            "bg-sidebar-primary text-sidebar-primary-foreground",
            "hover:opacity-90 transition-opacity"
          )}
        >
          <Plus className="h-4 w-4" />
          提交任务
        </button>
      </div>
    </aside>
  );
}
