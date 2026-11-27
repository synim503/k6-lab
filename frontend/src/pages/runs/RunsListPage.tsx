import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, EmptyState, Input, Select, Spinner } from '@/components/ui/primitives';
import {
  cn,
  conclusionVariant,
  fmtClockTime,
  fmtDayHeading,
  fmtDurationSec,
  runStatusMeta,
} from '@/lib/utils';
import {
  Activity,
  AlertTriangle,
  CalendarClock,
  ChevronLeft,
  ChevronRight,
  Clock,
  ListPlus,
  PlayCircle,
  RotateCcw,
  Search,
  Square,
  User,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

type RunGroupKey = '' | 'ACTIVE' | 'OK' | 'ISSUES' | 'STOPPED';

export function RunsListPage() {
  const qc = useQueryClient();
  const [scriptId, setScriptId] = useState('');
  const [group, setGroup] = useState<RunGroupKey>('');
  const [searchInput, setSearchInput] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedQuery(searchInput.trim());
      setPage(0);
    }, 240);
    return () => clearTimeout(t);
  }, [searchInput]);

  const { data, isLoading } = useQuery({
    queryKey: ['runs', scriptId, group, debouncedQuery, page],
    queryFn: () => {
      const sp = new URLSearchParams({ page: String(page), size: '30' });
      if (scriptId) sp.set('scriptId', scriptId);
      if (group) sp.set('group', group);
      if (debouncedQuery) sp.set('q', debouncedQuery);
      return apiFetch(`/api/v1/runs?${sp.toString()}`);
    },
    refetchInterval: 5000,
  });

  const requeueMut = useMutation({
    mutationFn: ({ id, position }: { id: number; position: 'front' | 'back' }) =>
      apiFetch(`/api/v1/runs/${id}/requeue`, { method: 'POST', body: { position } }),
    onSuccess: (res) => {
      toast.success(res.message || 'Прогон поставлен в очередь');
      qc.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const stopMut = useMutation({
    mutationFn: (runId: number) => apiFetch(`/api/v1/runs/${runId}/stop`, { method: 'POST' }),
    onSuccess: () => {
      toast.info('Команда остановки отправлена');
      qc.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const items: any[] = data?.items || [];
  const activeRuns: any[] = data?.activeRuns || [];
  const scripts: any[] = data?.scripts || [];
  const counts = data?.counts || { all: 0, active: 0, ok: 0, issues: 0, stopped: 0 };

  const dayGroups = useMemo(() => {
    const groups: { key: string; label: string; runs: any[] }[] = [];
    const byKey = new Map<string, { key: string; label: string; runs: any[] }>();
    for (const r of items) {
      const dh = fmtDayHeading(r.startedAt || r.queuedAt);
      let g = byKey.get(dh.key);
      if (!g) {
        g = { key: dh.key, label: dh.label, runs: [] };
        byKey.set(dh.key, g);
        groups.push(g);
      }
      g.runs.push(r);
    }
    return groups;
  }, [items]);

  if (isLoading && !data) return <Spinner label="Загрузка запусков…" />;

  const hasFilters = Boolean(scriptId || group || searchInput.trim());

  const filterPills: { key: RunGroupKey; label: string; count: number; dot?: string }[] = [
    { key: '', label: 'Все', count: counts.all ?? 0 },
    {
      key: 'ACTIVE',
      label: 'В работе',
      count: counts.active ?? 0,
      dot: (counts.active ?? 0) > 0 ? 'bg-indigo-500 animate-pulse' : 'bg-indigo-400',
    },
    { key: 'OK', label: 'Успешные', count: counts.ok ?? 0, dot: 'bg-emerald-500' },
    { key: 'ISSUES', label: 'Нарушения / Сбои', count: counts.issues ?? 0, dot: 'bg-rose-500' },
    { key: 'STOPPED', label: 'Остановлены', count: counts.stopped ?? 0, dot: 'bg-slate-400' },
  ];

  const rowAccentClass = (r: any) => {
    if (['RUNNING', 'PREPARING', 'QUEUED', 'STOPPING'].includes(r.status)) return 'border-l-indigo-500';
    if (r.status === 'FAILED') return 'border-l-rose-500';
    if (r.status === 'ABORTED' || r.status === 'STOPPED' || r.status === 'CANCELLED' || r.stopReason) return 'border-l-slate-400';
    if (r.thresholdsFailed || r.degraded) return 'border-l-amber-500';
    const cv = conclusionVariant(r.conclusion);
    if (cv === 'danger') return 'border-l-rose-500';
    if (cv === 'warning') return 'border-l-amber-500';
    return 'border-l-emerald-500';
  };

  return (
    <div className="space-y-4">
      {/* Шапка раздела */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Запуски тестов</h1>
          <p className="text-xs text-slate-500">
            Активные прогоны, история запусков по дням, статус SLA и отчёты
          </p>
        </div>
        <Link to="/schedule">
          <Button variant="outline" size="sm">
            <CalendarClock className="h-3.5 w-3.5 text-indigo-600" />
            Расписание и очередь
          </Button>
        </Link>
      </div>

      {/* Закреплённый блок активных и ожидающих запусков */}
      {activeRuns.length > 0 && group !== 'OK' && group !== 'ISSUES' && group !== 'STOPPED' && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-indigo-600 px-0.5">
            <Activity className="h-3.5 w-3.5 animate-pulse" />
            Сейчас в работе ({activeRuns.length})
          </div>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            {activeRuns.map((ar) => {
              const st = runStatusMeta(ar.status, ar.thresholdsFailed);
              const pct = Math.max(0, Math.min(100, Number(ar.progressPct) || 0));
              return (
                <Card
                  key={ar.id}
                  className="p-3.5 border-indigo-300 bg-indigo-50/50 flex flex-col justify-between gap-2.5"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <Link
                          to={`/runs/${ar.id}`}
                          className="font-mono text-sm font-bold text-indigo-700 hover:underline"
                        >
                          #{ar.id}
                        </Link>
                        <Link
                          to={`/runs/${ar.id}`}
                          className="font-semibold text-sm text-slate-900 hover:text-indigo-600 truncate"
                        >
                          {ar.scriptName}
                        </Link>
                        {ar.loadProfileName && <Badge variant="info">{ar.loadProfileName}</Badge>}
                        <Badge variant={st.variant}>{st.label}</Badge>
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-3 text-xs text-slate-600">
                        {ar.totalSteps > 0 && (
                          <span>
                            Ступень <b>{ar.currentStep ?? 1}</b> из {ar.totalSteps}
                          </span>
                        )}
                        {ar.totalSec > 0 && (
                          <span>
                            ⏱ {fmtDurationSec(ar.elapsedSec)} / {fmtDurationSec(ar.totalSec)}
                          </span>
                        )}
                        {ar.generatorsCount > 0 && <span>Генераторов: {ar.generatorsCount}</span>}
                      </div>
                    </div>

                    <div className="flex items-center gap-1.5 shrink-0">
                      {ar.canStop && (
                        <Button
                          variant="danger"
                          size="xs"
                          loading={stopMut.isPending}
                          onClick={() => stopMut.mutate(ar.id)}
                        >
                          <Square className="h-3 w-3 fill-current" />
                          Стоп
                        </Button>
                      )}
                      <Link to={`/runs/${ar.id}`}>
                        <Button size="xs">Открыть →</Button>
                      </Link>
                    </div>
                  </div>

                  {ar.status === 'RUNNING' && ar.totalSec > 0 && (
                    <div className="w-full h-1.5 rounded-full bg-slate-200 overflow-hidden">
                      <div
                        className="h-full rounded-full bg-indigo-600 transition-all duration-500"
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        </div>
      )}

      {/* Панель быстрых фильтров и поиска */}
      <Card className="p-3 space-y-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2.5">
          <div className="flex flex-wrap items-center gap-1.5">
            {filterPills.map((fp) => {
              const active = group === fp.key;
              return (
                <button
                  key={fp.label}
                  type="button"
                  onClick={() => {
                    setGroup(fp.key);
                    setPage(0);
                  }}
                  className={cn(
                    'inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors cursor-pointer',
                    active
                      ? 'border-indigo-500 bg-indigo-600 text-white shadow-2xs'
                      : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100'
                  )}
                >
                  {fp.dot && (
                    <span
                      className={cn(
                        'h-2 w-2 rounded-full',
                        active ? 'bg-white' : fp.dot
                      )}
                    />
                  )}
                  <span>{fp.label}</span>
                  <span
                    className={cn(
                      'rounded-md px-1.5 py-0.5 font-mono text-[10px]',
                      active ? 'bg-white/20 text-white' : 'bg-slate-200/80 text-slate-600'
                    )}
                  >
                    {fp.count}
                  </span>
                </button>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center gap-2 flex-1 sm:flex-initial justify-end">
            <div className="relative w-full sm:w-64">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Поиск: #ID, скрипт, автор, вердикт…"
                className="h-8 pl-8 pr-7 text-xs"
              />
              {searchInput && (
                <button
                  type="button"
                  onClick={() => setSearchInput('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-700 cursor-pointer"
                  aria-label="Очистить поиск"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              )}
            </div>

            <Select
              value={scriptId}
              onChange={(e) => {
                setScriptId(e.target.value);
                setPage(0);
              }}
              className="w-full sm:w-52 text-xs h-8"
            >
              <option value="">Все скрипты</option>
              {scripts.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>

            {hasFilters && (
              <Button
                variant="ghost"
                size="xs"
                onClick={() => {
                  setScriptId('');
                  setGroup('');
                  setSearchInput('');
                  setDebouncedQuery('');
                  setPage(0);
                }}
              >
                <X className="h-3.5 w-3.5" />
                Сбросить
              </Button>
            )}
          </div>
        </div>
      </Card>

      {/* Сгруппированный по датам список запусков */}
      {items.length === 0 ? (
        <Card>
          <EmptyState
            icon={<PlayCircle className="h-6 w-6" />}
            title={hasFilters ? 'По выбранным фильтрам запусков не найдено' : 'Запусков пока нет'}
            description={
              hasFilters
                ? 'Попробуйте сбросить фильтры или изменить поисковый запрос.'
                : 'Перейдите к нужному скрипту, чтобы запустить первый нагрузочный тест.'
            }
          />
        </Card>
      ) : (
        <div className="space-y-4">
          {dayGroups.map((dg) => (
            <div key={dg.key} className="space-y-1.5">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-semibold text-slate-600">{dg.label}</span>
                <span className="text-[11px] text-slate-400">запусков: {dg.runs.length}</span>
              </div>

              <Card className="overflow-hidden divide-y divide-slate-100">
                {dg.runs.map((r) => {
                  const st = runStatusMeta(r.status, r.thresholdsFailed);
                  const durSec =
                    r.startedAt && r.finishedAt
                      ? Math.max(
                          1,
                          Math.round(
                            (new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime()) / 1000
                          )
                        )
                      : 0;
                  const noteText = r.error || r.stopReason;

                  return (
                    <div
                      key={r.id}
                      className={cn(
                        'flex flex-col lg:flex-row lg:items-center justify-between gap-2.5 px-4 py-3 border-l-4 transition-colors hover:bg-slate-50/70',
                        rowAccentClass(r)
                      )}
                    >
                      {/* Левая часть: ID + Время + Скрипт + Причина */}
                      <div className="flex flex-wrap sm:flex-nowrap items-start sm:items-center gap-3 min-w-0 flex-1">
                        <div className="w-24 shrink-0">
                          <Link
                            to={`/runs/${r.id}`}
                            className="font-mono text-sm font-bold text-slate-900 hover:text-indigo-600"
                          >
                            #{r.id}
                          </Link>
                          <div className="flex items-center gap-1 text-[11px] text-slate-500 mt-0.5">
                            <Clock className="h-3 w-3 text-slate-400 shrink-0" />
                            <span>{fmtClockTime(r.startedAt || r.queuedAt)}</span>
                          </div>
                        </div>

                        <div className="min-w-0 flex-1 space-y-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <Link
                              to={`/runs/${r.id}`}
                              className="font-semibold text-sm text-slate-900 hover:text-indigo-600 truncate"
                            >
                              {r.scriptName}
                            </Link>
                            {r.loadProfileName && <Badge variant="info">{r.loadProfileName}</Badge>}
                          </div>

                          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
                            {durSec > 0 && (
                              <span className="font-mono text-[11px]">⏱ {fmtDurationSec(durSec)}</span>
                            )}
                            {r.createdBy && (
                              <span className="inline-flex items-center gap-1">
                                <User className="h-3 w-3 text-slate-400" />
                                {r.createdBy}
                              </span>
                            )}
                            {noteText && (
                              <span className="inline-flex items-center gap-1 text-amber-700 truncate max-w-md" title={noteText}>
                                <AlertTriangle className="h-3 w-3 shrink-0" />
                                <span className="truncate">{noteText}</span>
                              </span>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Правая часть: Статус, Вердикт и Действия */}
                      <div className="flex flex-wrap items-center justify-between lg:justify-end gap-2.5 shrink-0">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <Badge variant={st.variant}>{st.label}</Badge>
                          {r.degraded && <Badge variant="danger">Потеря ген.</Badge>}
                          {r.conclusion && (
                            <Badge variant={conclusionVariant(r.conclusion)}>{r.conclusion}</Badge>
                          )}
                        </div>

                        <div className="inline-flex items-center gap-1">
                          <Button
                            variant="ghost"
                            size="xs"
                            title="Повторить в конец очереди"
                            onClick={() => requeueMut.mutate({ id: r.id, position: 'back' })}
                          >
                            <RotateCcw className="h-3.5 w-3.5 text-slate-500" />
                            <span className="hidden sm:inline">Повторить</span>
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            title="Поставить первым в очередь (сразу после текущего)"
                            onClick={() => requeueMut.mutate({ id: r.id, position: 'front' })}
                          >
                            <ListPlus className="h-3.5 w-3.5 text-indigo-600" />
                          </Button>
                          <Link to={`/runs/${r.id}`}>
                            <Button variant="outline" size="xs">
                              Открыть
                            </Button>
                          </Link>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </Card>
            </div>
          ))}

          {/* Пагинация */}
          {data.totalPages > 1 && (
            <Card className="flex items-center justify-between px-4 py-2.5 text-xs text-slate-500">
              <span>
                Страница <b className="text-slate-800">{data.page + 1}</b> из {data.totalPages} (найдено запусков:{' '}
                {data.totalElements})
              </span>
              <div className="flex items-center gap-1.5">
                <Button variant="outline" size="xs" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
                  <ChevronLeft className="h-3.5 w-3.5" />
                  Назад
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={page + 1 >= data.totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Вперёд
                  <ChevronRight className="h-3.5 w-3.5" />
                </Button>
              </div>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
