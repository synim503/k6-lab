import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { Chart as ChartJS } from 'chart.js';

export type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeContextValue {
  mode: ThemeMode;
  resolvedTheme: 'light' | 'dark';
  setMode: (next: ThemeMode) => void;
  cycleTheme: () => void;
}

const STORAGE_KEY = 'k6lab.theme';

function readStoredMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw;
  } catch {
    // ignore
  }
  return 'system';
}

function getSystemDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function applyChartDefaults(isDark: boolean) {
  ChartJS.defaults.color = isDark ? '#94a3b8' : '#64748b';
  ChartJS.defaults.borderColor = isDark ? 'rgba(148, 163, 184, 0.14)' : 'rgba(226, 232, 240, 0.85)';
}

const ThemeContext = createContext<ThemeContextValue>({
  mode: 'system',
  resolvedTheme: 'light',
  setMode: () => {},
  cycleTheme: () => {},
});

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(readStoredMode);
  const [sysDark, setSysDark] = useState<boolean>(getSystemDark);

  useEffect(() => {
    if (!window.matchMedia) return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (e: MediaQueryListEvent) => setSysDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const resolvedTheme: 'light' | 'dark' = mode === 'system' ? (sysDark ? 'dark' : 'light') : mode;

  useEffect(() => {
    const isDark = resolvedTheme === 'dark';
    const root = document.documentElement;
    root.classList.toggle('dark', isDark);
    root.style.colorScheme = isDark ? 'dark' : 'light';
    applyChartDefaults(isDark);
  }, [resolvedTheme]);

  const setMode = (next: ThemeMode) => {
    setModeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // ignore
    }
  };

  const cycleTheme = () => {
    const next: ThemeMode = mode === 'light' ? 'dark' : mode === 'dark' ? 'system' : 'light';
    setMode(next);
  };

  const value = useMemo(
    () => ({ mode, resolvedTheme, setMode, cycleTheme }),
    [mode, resolvedTheme]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  return useContext(ThemeContext);
}
