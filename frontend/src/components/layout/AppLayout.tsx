import React, { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/context/ThemeContext';
import { RouteErrorBoundary } from '@/components/ui/RouteErrorBoundary';
import { cn } from '@/lib/utils';
import {
  Activity,
  Box,
  Bug,
  CalendarClock,
  ChevronDown,
  FileCode2,
  GitBranch,
  Home,
  KeyRound,
  LogOut,
  Menu,
  Monitor,
  Moon,
  PlayCircle,
  ScrollText,
  Search,
  Server,
  Settings,
  Sliders,
  Sun,
  Terminal,
  Users,
  Wrench,
  X,
  Zap,
} from 'lucide-react';

interface NavEntry {
  to: string;
  label: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
  group?: string;
}

const PRIMARY_NAV: NavEntry[] = [
  { to: '/', label: 'Главная', icon: <Home className="h-4 w-4" /> },
  { to: '/scripts', label: 'Скрипты', icon: <FileCode2 className="h-4 w-4" /> },
  { to: '/runs', label: 'Запуски', icon: <PlayCircle className="h-4 w-4" /> },
  { to: '/schedule', label: 'Расписание', icon: <CalendarClock className="h-4 w-4" /> },
  { to: '/generators', label: 'Генераторы', icon: <Server className="h-4 w-4" /> },
  { to: '/deployments', label: 'Операции', icon: <Wrench className="h-4 w-4" /> },
  { to: '/sandbox', label: 'Отладка', icon: <Bug className="h-4 w-4" />, adminOnly: true },
];

const MORE_NAV: NavEntry[] = [
  { to: '/git-repos', label: 'Git-репозитории', icon: <GitBranch className="h-4 w-4" />, group: 'Интеграции' },
  { to: '/secrets', label: 'Секреты', icon: <KeyRound className="h-4 w-4" />, group: 'Интеграции' },
  { to: '/tuning', label: 'Тюнинг ОС', icon: <Sliders className="h-4 w-4" />, adminOnly: true, group: 'Инфраструктура' },
  { to: '/distributions', label: 'Дистрибутивы', icon: <Box className="h-4 w-4" />, adminOnly: true, group: 'Инфраструктура' },
  { to: '/ssh-credentials', label: 'SSH-учётки', icon: <Terminal className="h-4 w-4" />, adminOnly: true, group: 'Инфраструктура' },
  { to: '/users', label: 'Пользователи', icon: <Users className="h-4 w-4" />, adminOnly: true, group: 'Система' },
  { to: '/settings', label: 'Настройки', icon: <Settings className="h-4 w-4" />, adminOnly: true, group: 'Система' },
  { to: '/audit', label: 'Аудит', icon: <ScrollText className="h-4 w-4" />, adminOnly: true, group: 'Система' },
];

export function AppLayout() {
  const { username, isAdmin, buildLabel, logout } = useAuth();
  const { mode: themeMode, setMode: setThemeMode } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const [moreOpen, setMoreOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [cmdkOpen, setCmdkOpen] = useState(false);
  const [cmdkQuery, setCmdkQuery] = useState('');
  const moreRef = useRef<HTMLDivElement>(null);

  const visiblePrimary = PRIMARY_NAV.filter((n) => !n.adminOnly || isAdmin);
  const visibleMore = MORE_NAV.filter((n) => !n.adminOnly || isAdmin);
  const allEntries = [...visiblePrimary, ...visibleMore];

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (moreRef.current && !moreRef.current.contains(e.target as Node)) {
        setMoreOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setCmdkOpen((v) => !v);
      } else if (e.key === 'Escape') {
        setCmdkOpen(false);
        setMoreOpen(false);
      }
    };
    document.addEventListener('mousedown', onDocClick);
    window.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      window.removeEventListener('keydown', onKey);
    };
  }, []);

  const filteredCmd = allEntries.filter((e) => e.label.toLowerCase().includes(cmdkQuery.trim().toLowerCase()));

  return (
    <div className="min-h-screen flex flex-col bg-slate-50 dark:bg-[#0b1120] text-slate-900 dark:text-slate-100 transition-colors">
      <header className="sticky top-0 z-40 bg-slate-900 dark:bg-[#070b14] text-slate-100 border-b border-slate-800 shadow-xs">
        <div className="mx-auto flex h-14 max-w-[1600px] items-center justify-between px-4 gap-3">
          <div className="flex items-center gap-5 min-w-0">
            <Link to="/" className="flex items-center gap-2 font-bold text-white tracking-tight shrink-0">
              <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-500 text-white shadow-xs">
                <Zap className="h-4 w-4 fill-current" />
              </span>
              <span>k6-lab</span>
            </Link>

            <nav className="hidden lg:flex items-center gap-1">
              {visiblePrimary.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors',
                      isActive ? 'bg-slate-800 text-white' : 'text-slate-300 hover:bg-slate-800/70 hover:text-white'
                    )
                  }
                >
                  {item.icon}
                  <span>{item.label}</span>
                </NavLink>
              ))}

              <div className="relative" ref={moreRef}>
                <button
                  type="button"
                  onClick={() => setMoreOpen((v) => !v)}
                  className={cn(
                    'flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors cursor-pointer',
                    moreOpen ? 'bg-slate-800 text-white' : 'text-slate-300 hover:bg-slate-800/70 hover:text-white'
                  )}
                >
                  <Activity className="h-4 w-4" />
                  <span>Ещё</span>
                  <ChevronDown className="h-3.5 w-3.5 opacity-70" />
                </button>
                {moreOpen && (
                  <div className="absolute left-0 mt-1.5 w-56 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 py-1.5 text-slate-800 dark:text-slate-100 shadow-lg z-50">
                    {['Интеграции', 'Инфраструктура', 'Система'].map((grp) => {
                      const items = visibleMore.filter((i) => i.group === grp);
                      if (items.length === 0) return null;
                      return (
                        <div key={grp} className="py-1">
                          <div className="px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                            {grp}
                          </div>
                          {items.map((it) => (
                            <NavLink
                              key={it.to}
                              to={it.to}
                              onClick={() => setMoreOpen(false)}
                              className={({ isActive }) =>
                                cn(
                                  'flex items-center gap-2.5 px-3 py-1.5 text-sm transition-colors',
                                  isActive
                                    ? 'bg-indigo-50 text-indigo-700 dark:bg-indigo-500/20 dark:text-indigo-300 font-medium'
                                    : 'text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-800'
                                )
                              }
                            >
                              <span className="text-slate-400">{it.icon}</span>
                              <span>{it.label}</span>
                            </NavLink>
                          ))}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </nav>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setCmdkQuery('');
                setCmdkOpen(true);
              }}
              className="hidden sm:flex items-center gap-2 rounded-lg border border-slate-700 bg-slate-800/80 px-2.5 py-1 text-xs text-slate-300 hover:border-slate-600 hover:text-white cursor-pointer"
              title="Быстрый переход (Ctrl+K)"
            >
              <Search className="h-3.5 w-3.5 text-slate-400" />
              <span className="hidden xl:inline">Перейти…</span>
              <kbd className="rounded bg-slate-700 px-1.5 py-0.5 text-[10px] font-mono text-slate-200">Ctrl+K</kbd>
            </button>

            {/* Переключатель темы оформления (Светлая / Тёмная / Системная) */}
            <div
              className="inline-flex items-center rounded-lg border border-slate-700 bg-slate-800/80 p-0.5"
              role="group"
              aria-label="Тема оформления"
            >
              {(
                [
                  ['light', 'Светлая тема', Sun],
                  ['dark', 'Тёмная тема', Moon],
                  ['system', 'Как в системе', Monitor],
                ] as const
              ).map(([mKey, mTitle, Icon]) => (
                <button
                  key={mKey}
                  type="button"
                  onClick={() => setThemeMode(mKey)}
                  title={mTitle}
                  className={cn(
                    'rounded-md p-1 transition-colors cursor-pointer',
                    themeMode === mKey
                      ? 'bg-indigo-600 text-white shadow-2xs'
                      : 'text-slate-400 hover:text-slate-100'
                  )}
                >
                  <Icon className="h-3.5 w-3.5" />
                </button>
              ))}
            </div>

            <div className="hidden sm:flex items-center gap-2 rounded-full bg-slate-800 px-2.5 py-1 text-xs text-slate-200">
              <span className="font-medium">{username}</span>
              {isAdmin && (
                <span className="rounded bg-indigo-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-indigo-300">
                  ADMIN
                </span>
              )}
            </div>

            <button
              type="button"
              onClick={() => logout()}
              className="flex items-center gap-1.5 rounded-lg border border-slate-700 px-2.5 py-1.5 text-xs font-medium text-slate-200 hover:bg-slate-800 hover:text-white cursor-pointer"
              title="Выйти"
            >
              <LogOut className="h-3.5 w-3.5" />
              <span className="hidden md:inline">Выйти</span>
            </button>

            <button
              type="button"
              onClick={() => setMobileOpen((v) => !v)}
              className="lg:hidden rounded-lg p-1.5 text-slate-300 hover:bg-slate-800"
              aria-label="Меню"
            >
              {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
            </button>
          </div>
        </div>

        {mobileOpen && (
          <div className="lg:hidden border-t border-slate-800 bg-slate-900 px-4 py-3 grid grid-cols-2 gap-1.5">
            {allEntries.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                onClick={() => setMobileOpen(false)}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-2 rounded-lg px-3 py-2 text-sm',
                    isActive ? 'bg-slate-800 text-white font-medium' : 'text-slate-300 hover:bg-slate-800/60'
                  )
                }
              >
                {item.icon}
                <span>{item.label}</span>
              </NavLink>
            ))}
          </div>
        )}
      </header>

      <main className="mx-auto w-full max-w-[1600px] flex-1 px-4 py-5">
        <RouteErrorBoundary resetKey={location.pathname}>
          <Outlet />
        </RouteErrorBoundary>
      </main>

      <footer className="border-t border-slate-200/80 dark:border-slate-800/80 py-3 text-center text-xs text-slate-400 dark:text-slate-500">
        k6-lab{buildLabel ? ` · версия ${buildLabel}` : ''}
      </footer>

      {cmdkOpen && (
        <div
          className="fixed inset-0 z-50 flex items-start justify-center pt-20 px-4 bg-slate-900/50 dark:bg-black/70 backdrop-blur-[1px]"
          onMouseDown={() => setCmdkOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-2xl overflow-hidden"
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2.5 border-b border-slate-100 dark:border-slate-800 px-4 py-3">
              <Search className="h-4 w-4 text-slate-400" />
              <input
                autoFocus
                type="text"
                value={cmdkQuery}
                onChange={(e) => setCmdkQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && filteredCmd[0]) {
                    navigate(filteredCmd[0].to);
                    setCmdkOpen(false);
                  }
                }}
                placeholder="Быстрый переход: скрипты, расписание, настройки…"
                className="w-full bg-transparent text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 focus:outline-none"
              />
            </div>
            <div className="max-h-72 overflow-y-auto p-1.5">
              {filteredCmd.map((item) => (
                <button
                  key={item.to}
                  type="button"
                  onClick={() => {
                    navigate(item.to);
                    setCmdkOpen(false);
                  }}
                  className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm text-slate-700 dark:text-slate-200 hover:bg-indigo-50 hover:text-indigo-700 dark:hover:bg-indigo-500/20 dark:hover:text-indigo-300 cursor-pointer"
                >
                  <span className="text-slate-400">{item.icon}</span>
                  <span className="font-medium">{item.label}</span>
                  {item.group && <span className="ml-auto text-xs text-slate-400">{item.group}</span>}
                </button>
              ))}
              {filteredCmd.length === 0 && (
                <div className="py-6 text-center text-xs text-slate-400">Ничего не найдено</div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
