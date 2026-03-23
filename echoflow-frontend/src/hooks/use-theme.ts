"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";

type Theme = "light" | "dark";

const listeners = new Set<() => void>();

function getSnapshot(): Theme {
  if (typeof window === "undefined") return "light";
  return (localStorage.getItem("theme") as Theme) ?? "light";
}

function getServerSnapshot(): Theme {
  return "light";
}

function subscribe(callback: () => void) {
  listeners.add(callback);
  return () => listeners.delete(callback);
}

function setThemeValue(t: Theme) {
  localStorage.setItem("theme", t);
  document.documentElement.classList.toggle("dark", t === "dark");
  listeners.forEach((l) => l());
}

export function useTheme() {
  const theme = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  // Sync HTML class on mount
  useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
  }, [theme]);

  const setTheme = useCallback((t: Theme) => {
    setThemeValue(t);
  }, []);

  const toggle = useCallback(() => {
    setThemeValue(theme === "dark" ? "light" : "dark");
  }, [theme]);

  return { theme, setTheme, toggle };
}
