import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Activity,
  AlertOctagon,
  AlertTriangle,
  Bug,
  ChevronDown,
  ChevronsDown,
  ChevronsUp,
  Clock,
  FileCode2,
  Home,
  Inbox,
  LayoutGrid,
  MoonStar,
  Package,
  Play,
  Plus,
  RotateCw,
  Server,
  Settings,
  Wrench,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, Spinner } from '@/components/ui/primitives';
import { useAuth } from '@/context/AuthContext';
import { runStatusMeta } from '@/lib/utils';

function pluralGenWarning(n: number): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return `${n} генератор требует внимания`;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return `${n} генератора требуют внимания`;
  return `${n} генераторов требуют внимания`;
}

function fmtClock(sec?: number | null): string {
  const s = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r < 10 ? '0' : ''}${r}`;
}

function fmtQueuedAt(val?: string | number | null): string {
  if (!val) return '—';
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function fmtRunDur(startedAt?: string | null, finishedAt?: string | null): string {
  if (!startedAt || !finishedAt) return '—';
  const durSec = Math.floor((new Date(finishedAt).getTime() - new Date(startedAt).getTime()) / 1000);
  if (Number.isNaN(durSec) || durSec < 0) return '—';
  if (durSec < 60) return `${durSec} с`;
  const m = Math.floor(durSec / 60);
  const rem = durSec % 60;
  return rem > 0 ? `${m} мин ${rem} с` : `${m} мин`;
}

function RequeueSplitButton({
  runId,
  onRequeue,
  pending,
}: {
  runId: number;
  onRequeue: (runId: number, pos: 'front' | 'back') => void;
  pending: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  return (
    <div ref={ref} className="relative inline-flex" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        disabled={pending}
        onClick={() => onRequeue(runId, 'back')}
        title="Снова добавить в конец очереди"
        className="inline-flex items-center justify-center h-7 px-2 rounded-l-md border border-indigo-300 bg-white text-indigo-600 hover:bg-indigo-50 text-xs cursor-pointer disabled:opacity-50"
      >
        <RotateCw className="h-3.5 w-3.5" />
      </button>
      <button
        type="button"
        disabled={pending}
        onClick={() => setOpen((v) => !v)}
        aria-label="Позиция в очереди"
        className="inline-flex items-center justify-center h-7 px-1.5 rounded-r-md border border-l-0 border-indigo-300 bg-white text-indigo-600 hover:bg-indigo-50 text-xs cursor-pointer disabled:opacity-50"
      >
        <ChevronDown className="h-3 w-3" />
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-1 z-30 w-64 rounded-lg border border-slate-200 bg-white py-1 shadow-lg text-left">
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              onRequeue(runId, 'front');
            }}
            className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 cursor-pointer"
          >
            <ChevronsUp className="h-3.5 w-3.5 text-indigo-600 shrink-0" />
            В начало очереди (после текущего)
          </button>
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              onRequeue(runId, 'back');
            }}
            className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 cursor-pointer"
          >
            <ChevronsDown className="h-3.5 w-3.5 text-indigo-600 shrink-0" />
            В конец очереди
          </button>
        </div>
      )}
    </div>
  );
}

export function DashboardPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [attnOpen, setAttnOpen] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => apiFetch('/api/v1/dashboard'),
    refetchInterval: 5000,
  });

  const requeueMut = useMutation({
    mutationFn: ({ runId, position }: { runId: number; position: 'front' | 'back' }) =>
      apiFetch(`/api/v1/runs/${runId}/requeue`, { method: 'POST', body: { position } }),
    onSuccess: (res: any) => {
      toast.success(res?.message || 'Прогон добавлен в очередь', {
        action: { label: 'Расписание', onClick: () => navigate('/schedule') },
      });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
      qc.invalidateQueries({ queryKey: ['schedule'] });
    },
    onError: (err: Error) => toast.error(err.message),
  });

  if (isLoading || !data) {
    return <Spinner label="Загрузка главной…" />;
  }

  const { summary, running = [], generators = [], problems = [], hot = [], lastRuns = [] } = data;
  const missingFiles: { id: number; name: string }[] = summary.scriptsWithoutFiles || [];

  return (
    <div className="space-y-4">
      {/* Шапка: как в оригинальном dashboard.html */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600 ring-1 ring-inset ring-indigo-100">
            <Home className="h-4.5 w-4.5" />
          </span>
          <h1 className="text-xl font-bold text-slate-900">Главная</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link to="/scripts">
            <Button variant="primary" size="md">
              <Play className="h-4 w-4 fill-current" />
              Запустить скрипт
            </Button>
          </Link>
          <Link to="/scripts/new">
            <Button variant="outline" size="md" className="border-indigo-300 text-indigo-700 hover:bg-indigo-50">
              <Plus className="h-4 w-4" />
              Добавить скрипт
            </Button>
          </Link>
          {isAdmin && (
            <Link to="/generators?add=1">
              <Button variant="outline" size="md">
                <Server className="h-4 w-4" />
                Добавить сервер
              </Button>
            </Link>
          )}
        </div>
      </div>

      {/* 4 карточки показателей */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Link to="/generators" className="block group">
          <Card className="h-full p-4 transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
            <div className="flex items-center gap-3.5">
              <span className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                <Server className="h-5 w-5" />
              </span>
              <div>
                <div className="text-2xl font-bold leading-tight">
                  <span className="text-emerald-600">{summary.onlineGenerators}</span>
                  <span className="text-slate-400 text-lg font-medium"> / {summary.totalGenerators}</span>
                </div>
                <div className="text-xs text-slate-500 mt-0.5">генераторов онлайн</div>
              </div>
            </div>
          </Card>
        </Link>

        <Link to="/scripts" className="block group">
          <Card className="h-full p-4 transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
            <div className="flex items-center gap-3.5">
              <span className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                <Activity className="h-5 w-5" />
              </span>
              <div>
                <div className={`text-2xl font-bold leading-tight ${running.length > 0 ? 'text-indigo-600' : 'text-slate-900'}`}>
                  {running.length}
                </div>
                <div className="text-xs text-slate-500 mt-0.5">скриптов выполняется</div>
              </div>
            </div>
          </Card>
        </Link>

        <Card className="h-full p-4">
          <div className="flex items-center gap-3.5">
            <span className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
              <Clock className="h-5 w-5" />
            </span>
            <div>
              <div className="text-2xl font-bold leading-tight text-slate-900">{summary.runsCount}</div>
              <div className="text-xs text-slate-500 mt-0.5">запусков проведено</div>
            </div>
          </div>
        </Card>

        <Link to="/scripts" className="block group">
          <Card className="h-full p-4 transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
            <div className="flex items-center gap-3.5">
              <span className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                <FileCode2 className="h-5 w-5" />
              </span>
              <div>
                <div className="text-2xl font-bold leading-tight text-slate-900">{summary.scriptsCount}</div>
                <div className="text-xs text-slate-500 mt-0.5">скриптов</div>
              </div>
            </div>
          </Card>
        </Link>
      </div>

      {/* Потерянное хранилище скриптов */}
      {missingFiles.length > 0 && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900 flex items-start gap-2.5">
          <AlertOctagon className="h-4.5 w-4.5 text-rose-600 shrink-0 mt-0.5" />
          <div className="text-xs leading-relaxed">
            <strong>У скриптов нет файлов в хранилище сервера:</strong>{' '}
            {missingFiles.slice(0, 8).map((s, idx) => (
              <React.Fragment key={s.id}>
                {idx > 0 && ', '}
                <Link to={`/scripts/${s.id}`} className="underline font-medium">
                  {s.name}
                </Link>
              </React.Fragment>
            ))}
            {missingFiles.length > 8 && ` и ещё ${missingFiles.length - 8}`}. Запись о скриптах в базе есть, а архивы версий
            пропали: обычно после переустановки админки без переноса каталогов{' '}
            <code className="font-mono bg-rose-100 px-1 rounded">config/</code> и{' '}
            <code className="font-mono bg-rose-100 px-1 rounded">data/</code>. Ищем в{' '}
            <code className="font-mono bg-rose-100 px-1 rounded">{summary.storagePath}</code>. Верните каталог на место или
            загрузите скрипты заново.
          </div>
        </div>
      )}

      {/* Внимание: проблемные или перегруженные генераторы */}
      {(problems.length > 0 || hot.length > 0) && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-2.5 text-sm text-amber-950">
          <div className="flex flex-wrap items-center gap-2">
            <AlertTriangle className="h-4 w-4 text-amber-600 shrink-0" />
            {problems.length > 0 && <strong>{pluralGenWarning(problems.length)}</strong>}
            {hot.length > 0 && (
              <span>
                {problems.length > 0 ? '· ' : ''}CPU выше 80%: {hot.length}
              </span>
            )}
            {problems.length > 0 && (
              <span className="hidden md:inline text-xs text-amber-800/80">
                {problems[0].name} — {problems[0].statusLabel}
                {problems.length > 1 ? ` и ещё ${problems.length - 1}` : ''}
              </span>
            )}
            <div className="ml-auto flex items-center gap-2">
              <Button variant="outline" size="xs" onClick={() => setAttnOpen((v) => !v)}>
                Подробнее
              </Button>
              <Link to="/generators">
                <Button variant="outline" size="xs">
                  Все генераторы
                </Button>
              </Link>
            </div>
          </div>
          {attnOpen && (
            <div className="mt-2.5 pt-2 border-t border-amber-200/80 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-1.5 text-xs">
              {problems.map((g: any) => (
                <div key={`p-${g.id}`}>
                  <Link to={`/generators/${g.id}`} className="font-semibold text-indigo-700 hover:underline">
                    {g.name}
                  </Link>{' '}
                  <span className="text-amber-800">— {g.statusLabel}</span>
                </div>
              ))}
              {hot.map((g: any) => (
                <div key={`h-${g.id}`}>
                  <Link to={`/generators/${g.id}`} className="font-semibold text-indigo-700 hover:underline">
                    {g.name}
                  </Link>{' '}
                  <span className="text-rose-700">— CPU {Math.round(g.cpu || 0)}%</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Основная сетка 8 / 4 как в dashboard.html */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5 items-start">
        {/* Левая колонка: Запущенные скрипты + Последние запуски */}
        <div className="xl:col-span-8 space-y-5">
          <div>
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-base font-semibold text-slate-900 flex items-center gap-2">
                <Activity className="h-4.5 w-4.5 text-indigo-600" />
                Запущенные скрипты
              </h2>
            </div>

            {running.length === 0 ? (
              <Card className="py-10 px-4 text-center">
                <MoonStar className="h-7 w-7 text-slate-300 mx-auto mb-2" />
                <div className="text-sm text-slate-500">Сейчас ничего не запущено</div>
                <div className="mt-3">
                  <Link to="/scripts">
                    <Button size="sm">
                      <Play className="h-3.5 w-3.5 fill-current" />
                      Запустить скрипт
                    </Button>
                  </Link>
                </div>
              </Card>
            ) : (
              <>
                <div className="text-xs text-slate-500 mb-1.5">Выполняется и ждёт: {running.length}</div>
                <Card className="overflow-hidden">
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                        <tr>
                          <th className="px-3.5 py-2 text-left font-medium">Скрипт</th>
                          <th className="px-3 py-2 text-left font-medium">Статус</th>
                          <th className="px-3 py-2 text-left font-medium min-w-[180px]">Ход</th>
                          <th className="px-3 py-2 text-right font-medium">Генераторы</th>
                          <th className="px-3.5 py-2 text-right font-medium">CPU</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {running.map((r: any) => {
                          const meta = runStatusMeta(r.status, r.thresholdsFailed);
                          return (
                            <tr
                              key={r.id}
                              onClick={() => navigate(`/runs/${r.id}`)}
                              className="hover:bg-slate-50/80 cursor-pointer transition-colors"
                            >
                              <td className="px-3.5 py-2.5">
                                <Link
                                  to={`/runs/${r.id}`}
                                  onClick={(e) => e.stopPropagation()}
                                  className="font-semibold text-slate-900 hover:text-indigo-600"
                                >
                                  {r.scriptName}
                                </Link>{' '}
                                <span className="text-xs text-slate-400">#{r.id}</span>
                              </td>
                              <td className="px-3 py-2.5">
                                <Badge
                                  variant={
                                    r.status === 'RUNNING'
                                      ? 'info'
                                      : r.status === 'QUEUED'
                                        ? 'default'
                                        : 'warning'
                                  }
                                >
                                  {meta.label}
                                </Badge>
                              </td>
                              <td className="px-3 py-2.5">
                                {r.status === 'QUEUED' ? (
                                  <span className="text-xs text-slate-500">ждёт свободные генераторы</span>
                                ) : (
                                  <div>
                                    <div className="h-1.5 rounded-full bg-slate-100 overflow-hidden">
                                      <div
                                        className="h-full rounded-full bg-indigo-600 transition-all"
                                        style={{ width: `${Math.min(100, Math.max(0, r.progressPct || 0))}%` }}
                                      />
                                    </div>
                                    <div className="text-[11px] text-slate-500 mt-1 whitespace-nowrap">
                                      {r.currentStep != null && r.currentPhase ? `${r.currentPhase} · ` : ''}
                                      {fmtClock(r.elapsedSec)} / {fmtClock(r.totalSec)}
                                    </div>
                                  </div>
                                )}
                              </td>
                              <td className="px-3 py-2.5 text-right whitespace-nowrap text-xs text-slate-700">
                                <span>{r.generatorsCount ?? 0}</span>
                                {r.offlineCount > 0 && (
                                  <Badge variant="warning" className="ml-1.5">
                                    нет связи {r.offlineCount}
                                  </Badge>
                                )}
                              </td>
                              <td className="px-3.5 py-2.5 text-right whitespace-nowrap text-xs">
                                {r.maxCpu != null ? (
                                  <span
                                    className={r.hotCount > 0 ? 'font-bold text-rose-600' : 'text-slate-700'}
                                    title={r.hotCount > 0 ? `CPU > 80% у генераторов: ${r.hotCount}` : undefined}
                                  >
                                    {Math.round(r.maxCpu)}%
                                  </span>
                                ) : (
                                  <span className="text-slate-400">—</span>
                                )}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </Card>
              </>
            )}
          </div>

          {/* Последние запуски */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-base font-semibold text-slate-900 flex items-center gap-2">
                <Clock className="h-4.5 w-4.5 text-indigo-600" />
                Последние запуски
              </h2>
            </div>

            <Card className="overflow-visible">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">#</th>
                      <th className="px-3 py-2 text-left font-medium">Скрипт</th>
                      <th className="px-3 py-2 text-left font-medium">Статус</th>
                      <th className="px-3 py-2 text-left font-medium">Поставлен</th>
                      <th className="px-3 py-2 text-left font-medium">Длительность</th>
                      <th className="px-3.5 py-2 text-right font-medium">В очередь</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {lastRuns.length === 0 ? (
                      <tr>
                        <td colSpan={6} className="py-8 text-center text-xs text-slate-400">
                          <Inbox className="h-6 w-6 mx-auto mb-1.5 text-slate-300" />
                          Запусков ещё не было
                        </td>
                      </tr>
                    ) : (
                      lastRuns.map((r: any) => {
                        const meta = runStatusMeta(r.status, r.thresholdsFailed);
                        return (
                          <tr
                            key={r.id}
                            onClick={() => navigate(`/runs/${r.id}`)}
                            className="hover:bg-slate-50/80 cursor-pointer transition-colors"
                          >
                            <td className="px-3.5 py-2.5 whitespace-nowrap">
                              <Link
                                to={`/runs/${r.id}`}
                                onClick={(e) => e.stopPropagation()}
                                className="font-semibold text-indigo-600 hover:underline"
                              >
                                #{r.id}
                              </Link>
                            </td>
                            <td className="px-3 py-2.5 font-medium text-slate-900">{r.scriptName}</td>
                            <td className="px-3 py-2.5">
                              <Badge variant={meta.variant}>{meta.label}</Badge>
                            </td>
                            <td className="px-3 py-2.5 text-xs text-slate-500 whitespace-nowrap">
                              {fmtQueuedAt(r.queuedAt)}
                            </td>
                            <td className="px-3 py-2.5 text-xs text-slate-500 whitespace-nowrap">
                              {fmtRunDur(r.startedAt, r.finishedAt)}
                            </td>
                            <td className="px-3.5 py-2.5 text-right whitespace-nowrap">
                              {r.scriptId != null && (
                                <RequeueSplitButton
                                  runId={r.id}
                                  pending={requeueMut.isPending}
                                  onRequeue={(runId, position) => requeueMut.mutate({ runId, position })}
                                />
                              )}
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        </div>

        {/* Правая колонка: Генераторы + Разделы */}
        <div className="xl:col-span-4 space-y-5">
          <div>
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-base font-semibold text-slate-900 flex items-center gap-2">
                <Server className="h-4.5 w-4.5 text-indigo-600" />
                Генераторы
              </h2>
              <Link to="/generators" className="text-xs font-medium text-indigo-600 hover:underline">
                {summary.totalGenerators > generators.length ? `все ${summary.totalGenerators} →` : 'все →'}
              </Link>
            </div>

            <Card className="overflow-hidden">
              {generators.length === 0 ? (
                <div className="py-8 px-4 text-center">
                  <Server className="h-6 w-6 text-slate-300 mx-auto mb-1.5" />
                  <div className="text-xs text-slate-500">Серверов пока нет</div>
                  {isAdmin && (
                    <div className="mt-3">
                      <Link to="/generators?add=1">
                        <Button size="sm">
                          <Plus className="h-3.5 w-3.5" />
                          Добавить сервер
                        </Button>
                      </Link>
                    </div>
                  )}
                </div>
              ) : (
                <ul className="divide-y divide-slate-100">
                  {generators.map((g: any) => {
                    const isOk = g.status === 'ONLINE' && !g.busy && !g.maintenance;
                    const isBusy = g.status === 'ONLINE' && g.busy;
                    return (
                      <li key={g.id} className="px-3.5 py-2.5 flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2.5 min-w-0">
                          <span
                            className={`h-2.5 w-2.5 rounded-full shrink-0 ${
                              isBusy
                                ? 'bg-indigo-600 animate-pulse'
                                : isOk
                                  ? 'bg-emerald-500'
                                  : g.maintenance
                                    ? 'bg-slate-400'
                                    : 'bg-rose-500'
                            }`}
                          />
                          <div className="min-w-0">
                            <Link
                              to={`/generators/${g.id}`}
                              className="text-sm font-semibold text-indigo-600 hover:underline truncate block"
                            >
                              {g.name}
                            </Link>
                            <div className="text-xs text-slate-500 truncate">
                              {g.host !== g.name ? g.host : ''}
                              {g.cpu != null
                                ? `${g.host !== g.name ? ' · ' : ''}CPU ${Math.round(g.cpu)}%`
                                : ''}
                            </div>
                          </div>
                        </div>
                        <Badge
                          variant={
                            isBusy
                              ? 'info'
                              : isOk
                                ? 'success'
                                : g.maintenance
                                  ? 'default'
                                  : g.status === 'OFFLINE'
                                    ? 'warning'
                                    : 'danger'
                          }
                        >
                          {g.busy ? 'занят' : g.statusLabel || g.status}
                        </Badge>
                      </li>
                    );
                  })}
                  {summary.totalGenerators > generators.length && (
                    <li className="px-3.5 py-2 text-center text-xs bg-slate-50/60">
                      <Link to="/generators" className="text-indigo-600 hover:underline">
                        Показано {generators.length} из {summary.totalGenerators} — все генераторы
                      </Link>
                    </li>
                  )}
                </ul>
              )}
            </Card>
          </div>

          {/* Плитки разделов */}
          <div>
            <h2 className="text-base font-semibold text-slate-900 flex items-center gap-2 mb-2">
              <LayoutGrid className="h-4.5 w-4.5 text-indigo-600" />
              Разделы
            </h2>
            <div className="grid grid-cols-2 gap-2.5">
              <Link to="/generators" className="group block">
                <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                  <Server className="h-5 w-5 text-indigo-600" />
                  <div className="font-semibold text-sm text-slate-900 mt-1.5">Генераторы</div>
                  <div className="text-xs text-slate-500">серверы и агенты</div>
                </Card>
              </Link>
              <Link to="/scripts" className="group block">
                <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                  <FileCode2 className="h-5 w-5 text-indigo-600" />
                  <div className="font-semibold text-sm text-slate-900 mt-1.5">Скрипты</div>
                  <div className="text-xs text-slate-500">нагрузка, версии, запуск</div>
                </Card>
              </Link>
              <Link to="/deployments" className="group block">
                <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                  <Wrench className="h-5 w-5 text-indigo-600" />
                  <div className="font-semibold text-sm text-slate-900 mt-1.5">Операции</div>
                  <div className="text-xs text-slate-500">установка агентов</div>
                </Card>
              </Link>
              {isAdmin && (
                <>
                  <Link to="/sandbox" className="group block">
                    <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                      <Bug className="h-5 w-5 text-indigo-600" />
                      <div className="font-semibold text-sm text-slate-900 mt-1.5">Отладка</div>
                      <div className="text-xs text-slate-500">запуск скрипта с логом</div>
                    </Card>
                  </Link>
                  <Link to="/settings" className="group block">
                    <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                      <Settings className="h-5 w-5 text-indigo-600" />
                      <div className="font-semibold text-sm text-slate-900 mt-1.5">Настройки</div>
                      <div className="text-xs text-slate-500">Prometheus, метрики</div>
                    </Card>
                  </Link>
                  <Link to="/distributions" className="group block">
                    <Card className="p-3.5 h-full transition-all group-hover:border-indigo-300 group-hover:shadow-sm">
                      <Package className="h-5 w-5 text-indigo-600" />
                      <div className="font-semibold text-sm text-slate-900 mt-1.5">Дистрибутивы</div>
                      <div className="text-xs text-slate-500">агент, k6, xk6</div>
                    </Card>
                  </Link>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
