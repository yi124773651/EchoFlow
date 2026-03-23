"use client";

import { type ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { cn } from "@/lib/utils";
import { SidebarWrapper } from "./sidebar-wrapper";

export function AppLayout({
  sidebar,
  taskList,
  detail,
  mobileShowDetail,
  onMobileBack,
}: {
  sidebar: { onNewTask: () => void };
  taskList: ReactNode;
  detail: ReactNode;
  mobileShowDetail: boolean;
  onMobileBack: () => void;
}) {
  return (
    <div className="flex h-screen overflow-hidden bg-background">
      {/* Sidebar - hidden on mobile */}
      <div className="hidden md:flex">
        <SidebarWrapper onNewTask={sidebar.onNewTask} />
      </div>

      {/* Task List Panel - full width on mobile, fixed on desktop */}
      <div
        className={cn(
          "flex h-screen flex-col border-r border-border",
          "w-full md:w-[320px] shrink-0",
          mobileShowDetail && "hidden md:flex"
        )}
      >
        {/* Mobile header */}
        <div className="flex h-14 items-center gap-2 border-b border-border px-4 md:hidden">
          <span className="text-sm font-semibold">EchoFlow</span>
        </div>
        {taskList}
      </div>

      {/* Detail Panel - overlay on mobile, inline on desktop */}
      <div
        className={cn(
          "flex h-screen flex-1 flex-col overflow-hidden",
          "hidden md:flex",
          mobileShowDetail && "!flex absolute inset-0 z-40 bg-background md:relative md:inset-auto md:z-auto"
        )}
      >
        {/* Mobile back button */}
        {mobileShowDetail && (
          <button
            onClick={onMobileBack}
            className="flex h-12 items-center gap-2 border-b border-border px-4 text-sm md:hidden"
          >
            <ArrowLeft className="h-4 w-4" />
            返回列表
          </button>
        )}
        {detail}
      </div>
    </div>
  );
}
