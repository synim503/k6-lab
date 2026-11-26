import React from 'react';
import { Link, NavLink } from 'react-router-dom';
import { Button } from '@/components/ui/primitives';
import { cn } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import { BarChart3, Bug, FileCode2, Play, Sliders } from 'lucide-react';

export function ScriptHead({
  script,
  rightActions,
}: {
  script: { id: number; name: string; description?: string | null; gitBranch?: string | null; gitRepoName?: string | null };
  rightActions?: React.ReactNode;
}) {
  const { isAdmin } = useAuth();

  const tabs = [
    { to: `/scripts/${script.id}`, label: 'Обзор и версии', icon: <FileCode2 className="h-4 w-4" />, end: true },
    { to: `/scripts/${script.id}/load`, label: 'Нагрузка и профили', icon: <Sliders className="h-4 w-4" /> },
    { to: `/scripts/${script.id}/charts`, label: 'Графики отчёта', icon: <BarChart3 className="h-4 w-4" /> },
    { to: `/scripts/${script.id}/run`, label: 'Запуск и расписание', icon: <Play className="h-4 w-4" /> },
  ];

  return (
    <div className="mb-5 space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs text-slate-400 mb-0.5">
            <Link to="/scripts" className="hover:text-indigo-600">
              Скрипты
            </Link>
            <span>/</span>
            <span className="truncate text-slate-600">{script.name}</span>
          </div>
          <h1 className="text-xl font-bold text-slate-900 break-words">{script.name}</h1>
          {script.description && <p className="text-xs text-slate-500 mt-0.5">{script.description}</p>}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {isAdmin && (
            <Link to={`/sandbox?script=${script.id}`}>
              <Button variant="outline" size="sm">
                <Bug className="h-3.5 w-3.5" />
                В отладчик
              </Button>
            </Link>
          )}
          {rightActions || (
            <Link to={`/scripts/${script.id}/run`}>
              <Button variant="success" size="sm">
                <Play className="h-3.5 w-3.5 fill-current" />
                Запустить
              </Button>
            </Link>
          )}
        </div>
      </div>

      <div className="flex items-center gap-1 border-b border-slate-200 overflow-x-auto">
        {tabs.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            end={t.end}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-1.5 border-b-2 px-3.5 py-2 text-sm font-medium whitespace-nowrap transition-colors',
                isActive
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-800'
              )
            }
          >
            {t.icon}
            <span>{t.label}</span>
          </NavLink>
        ))}
      </div>
    </div>
  );
}
