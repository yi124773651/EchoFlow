"use client";

import { useState } from "react";
import { LayoutList, Plus, PanelLeftClose, PanelLeft, Sun, Moon } from "lucide-react";
import { cn } from "@/lib/utils";
import { useTheme } from "@/hooks/use-theme";

export function SidebarWrapper({ onNewTask }: { onNewTask: () => void }) {
  const [collapsed, setCollapsed] = useState(false);
  const { theme, toggle } = useTheme();

  return (
    <aside
      className={cn(
        "flex h-screen flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-[width] duration-200",
        collapsed ? "w-[60px]" : "w-[200px]"
      )}
    >
      {/* Brand + Collapse toggle */}
      <div className="flex h-14 items-center justify-between px-3">
        {!collapsed && (
          <span className="text-sm font-semibold tracking-tight">EchoFlow</span>
        )}
        <button
          onClick={() => setCollapsed(!collapsed)}
          className="rounded-md p-1 text-sidebar-foreground hover:bg-sidebar-accent transition-colors"
        >
          {collapsed ? (
            <PanelLeft className="h-4 w-4" />
          ) : (
            <PanelLeftClose className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-2 py-2">
        <button
          className={cn(
            "flex w-full items-center rounded-md text-sm font-medium",
            "bg-sidebar-accent text-sidebar-accent-foreground",
            collapsed ? "justify-center p-2" : "gap-2 px-2 py-1.5"
          )}
          title="任务"
        >
          <LayoutList className="h-4 w-4 shrink-0" />
          {!collapsed && <span>任务</span>}
        </button>
      </nav>

      {/* Bottom */}
      <div className="space-y-1 border-t border-sidebar-border p-2">
        {/* Theme toggle */}
        <button
          onClick={toggle}
          className={cn(
            "flex w-full items-center rounded-md text-sm text-sidebar-foreground hover:bg-sidebar-accent transition-colors",
            collapsed ? "justify-center p-2" : "gap-2 px-2 py-1.5"
          )}
          title={theme === "dark" ? "浅色模式" : "深色模式"}
        >
          {theme === "dark" ? (
            <Sun className="h-4 w-4 shrink-0" />
          ) : (
            <Moon className="h-4 w-4 shrink-0" />
          )}
          {!collapsed && (
            <span>{theme === "dark" ? "浅色模式" : "深色模式"}</span>
          )}
        </button>

        {/* New task */}
        <button
          onClick={onNewTask}
          className={cn(
            "flex w-full items-center rounded-md text-sm font-medium",
            "bg-sidebar-primary text-sidebar-primary-foreground",
            "hover:opacity-90 transition-opacity",
            collapsed ? "justify-center p-2" : "justify-center gap-2 px-3 py-2"
          )}
          title="提交任务"
        >
          <Plus className="h-4 w-4 shrink-0" />
          {!collapsed && <span>提交任务</span>}
        </button>
      </div>
    </aside>
  );
}
