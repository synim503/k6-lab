import React, { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
  Textarea,
} from '@/components/ui/primitives';
import { ScheduleTimeline } from '@/components/schedule/ScheduleTimeline';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { useAuth } from '@/context/AuthContext';
import { fmtDateTime } from '@/lib/utils';
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  CalendarClock,
  ChevronsDown,
  ChevronsUp,
  Clock,
  FastForward,
  Filter,
  Hourglass,
  Layers,
  Pause,
  Pencil,
  Pin,
  Play,
  PlayCircle,
  Plus,
  RotateCcw,
  Search,
  Server,
  ShieldAlert,
  Terminal,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

function fmtCountdown(runAt?: string | null): string {
  if (!runAt) return 'скоро';
  const diffSec = Math.floor((new Date(runAt).getTime() - Date.now()) / 1000);
  if (Number.isNaN(diffSec) || diffSec <= 5) return 'сейчас';
  if (diffSec < 60) return `через ${diffSec} с`;
  const m = Math.floor(diffSec / 60);
  if (m < 60) return `через ${m} мин`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return rm > 0 ? `через ${h} ч ${rm} мин` : `через ${h} ч`;
}

export function SchedulePage() {
  const { isAdmin } = useAuth();
  const confirm = useConfirm();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [filter, setFilter] = useState<'all' | 'pending' | 'started' | 'other'>('all');
  const [search, setSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [highlightId, setHighlightId] = useState<number | null>(null);

  // Quick add modal
  const [quickOpen, setQuickOpen] = useState(false);
  const [qmScriptId, setQmScriptId] = useState('');
  const [qmProfileId, setQmProfileId] = useState('');
  const [qmCount, setQmCount] = useState(1);
  const [qmPos, setQmPos] = useState<'auto' | 'front' | 'back'>('auto');

  // Import schedule tests modal
  const [impOpen, setImpOpen] = useState(false);
  const [impJson, setImpJson] = useState('');
  const [impGapMin, setImpGapMin] = useState(5);

  // Import profiles modal
  const [impProfOpen, setImpProfOpen] = useState(false);
  const [impProfJson, setImpProfJson] = useState('');
  const [impProfSeq, setImpProfSeq] = useState(false);
  const [impProfGap, setImpProfGap] = useState(5);

  // Guard modal
  const [guardModal, setGuardModal] = useState<any | null>(null);
  const [promTestRes, setPromTestRes] = useState<any | null>(null);
  const [sshTestRes, setSshTestRes] = useState<any | null>(null);
  const [testingGuard, setTestingGuard] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['schedule'],
    queryFn: () => apiFetch('/api/v1/schedule'),
    refetchInterval: 5000,
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['schedule'] });
    qc.invalidateQueries({ queryKey: ['schedule-timeline'] });
  };

  const actionMut = useMutation({
    mutationFn: ({ url, body }: { url: string; body?: any }) =>
      apiFetch(url, { method: 'POST', body }),
    onSuccess: (res: any) => {
      refresh();
      if (res?.message) toast.success(res.message);
      if (res?.startedRunId) {
        toast.success(`Запущен прогон #${res.startedRunId}`, {
          action: { label: 'Открыть', onClick: () => navigate(`/runs/${res.startedRunId}`) },
        });
      }
    },
    onError: (err: any) => toast.error(err.message),
  });

  const items: any[] = data?.items || [];
  const activeRuns: any[] = data?.activeRuns || [];
  const guards: any[] = data?.guards || [];
  const scripts: any[] = data?.scripts || [];
  const sshCredentials: any[] = data?.sshCredentials || [];
  const generators: any[] = data?.generators || [];

  const pendingSorted = useMemo(
    () =>
      items
        .filter((i) => i.pending)
        .slice()
        .sort((a, b) => new Date(a.runAt).getTime() - new Date(b.runAt).getTime()),
    [items]
  );

  const queueOrderById = useMemo(() => {
    const map = new Map<number, number>();
    pendingSorted.forEach((it, idx) => map.set(it.id, idx + 1));
    return map;
  }, [pendingSorted]);

  const startedItems = useMemo(
    () => items.filter((i) => i.status === 'STARTED' || i.status === 'STARTING'),
    [items]
  );
  const failedItems = useMemo(() => items.filter((i) => i.status === 'FAILED'), [items]);
  const otherItems = useMemo(
    () => items.filter((i) => i.status === 'FAILED' || i.status === 'CANCELLED'),
    [items]
  );
  const historyCount = startedItems.length + otherItems.length;

  const totalQueueMin = useMemo(() => {
    if (pendingSorted.length === 0) return 0;
    const sumMin = pendingSorted.reduce(
      (acc, it) => acc + Math.max(1, Math.round((it.durationSec || 300) / 60)),
      0
    );
    return sumMin + Math.max(0, pendingSorted.length - 1) * 5;
  }, [pendingSorted]);

  const queueFinishAt = useMemo(() => {
    if (pendingSorted.length === 0) return null;
    let maxMs = 0;
    for (const it of pendingSorted) {
      const durMin = Math.max(1, Math.round((it.durationSec || 300) / 60));
      const fin = new Date(it.runAt).getTime() + durMin * 60_000;
      if (fin > maxMs) maxMs = fin;
    }
    return maxMs > 0 ? new Date(maxMs).toISOString() : null;
  }, [pendingSorted]);

  const freeGenCount = generators.filter((g) => g.availableForRuns).length;
  const onlineGenCount = generators.filter((g) => g.status === 'ONLINE').length;

  const visibleItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    return items.filter((it) => {
      if (filter === 'pending' && !it.pending) return false;
      if (filter === 'started' && it.status !== 'STARTED' && it.status !== 'STARTING') return false;
      if (filter === 'other' && it.status !== 'FAILED' && it.status !== 'CANCELLED') return false;
      if (q) {
        const hay = `${it.id} ${it.scriptName} ${it.profileName || ''} ${(it.generatorNames || []).join(' ')} ${
          it.createdBy || ''
        }`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [items, filter, search]);

  const handleJumpToRow = (scheduleId: number) => {
    setFilter('all');
    setSearch('');
    setHighlightId(scheduleId);
    setTimeout(() => {
      const el = document.getElementById(`sch-row-${scheduleId}`);
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 60);
  };

  if (isLoading || !data) return <Spinner label="Загрузка расписания…" />;

  const { paused, pauseReason, timeline } = data;
  const selectedScriptObj = scripts.find((s: any) => String(s.id) === qmScriptId);

  return (
    <div className="space-y-4">
      {/* Шапка: как в schedule/index.html */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600 ring-1 ring-inset ring-indigo-100">
              <CalendarClock className="h-4.5 w-4.5" />
            </span>
            <h1 className="text-xl font-bold text-slate-900">Расписание запусков</h1>
            <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-white px-2.5 py-0.5 text-xs font-medium text-slate-700">
              <span className={`h-2 w-2 rounded-full ${paused ? 'bg-rose-500' : 'bg-emerald-500'}`} />
              {paused ? 'На паузе' : 'Активен'}
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-1">
            Автоматический запуск тестов по расписанию с бронированием генераторов и контролем очереди.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            disabled={scripts.length === 0}
            onClick={() => {
              const first = scripts[0];
              if (first) {
                setQmScriptId(String(first.id));
                setQmCount(first.generatorCount || 1);
              }
              setQuickOpen(true);
            }}
          >
            <Plus className="h-4 w-4" />
            Запланировать тест
          </Button>
          <Button variant="outline" size="sm" onClick={() => setImpOpen(true)}>
            <Upload className="h-3.5 w-3.5" />
            Импорт списка тестов
          </Button>
          <Button variant="outline" size="sm" onClick={() => setImpProfOpen(true)}>
            <Layers className="h-3.5 w-3.5" />
            Импорт профилей нагрузки
          </Button>
          {!paused && (
            <Button
              variant="warning"
              size="sm"
              onClick={() => actionMut.mutate({ url: '/api/v1/schedule/pause' })}
            >
              <Pause className="h-3.5 w-3.5" />
              Поставить на паузу
            </Button>
          )}
        </div>
      </div>

      {/* Баннер паузы */}
      {paused && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 flex flex-wrap items-center justify-between gap-3 text-sm text-amber-950">
          <div className="space-y-0.5">
            <div>
              <strong>Планировщик на паузе.</strong> Новые запуски по расписанию не стартуют, записи ждут.{' '}
              {activeRuns.length > 0 ? (
                <span>
                  Идёт тест (
                  {activeRuns.map((r: any, idx: number) => (
                    <React.Fragment key={r.id}>
                      {idx > 0 && ', '}
                      <Link to={`/runs/${r.id}`} className="underline font-semibold">
                        #{r.id}
                      </Link>
                    </React.Fragment>
                  ))}
                  ): он закончится сам.
                </span>
              ) : (
                <span>При снятии паузы первый тест из очереди запустится сразу, а остальные сдвинутся следом.</span>
              )}
            </div>
            {pauseReason && <div className="text-xs font-semibold text-rose-700">Причина: {pauseReason}</div>}
          </div>
          <Button
            variant="success"
            size="sm"
            onClick={() => actionMut.mutate({ url: '/api/v1/schedule/resume' })}
          >
            <Play className="h-3.5 w-3.5 fill-current" />
            Снять с паузы
          </Button>
        </div>
      )}

      {/* Сводка KPI (4 карточки, клик фильтрует таблицу ниже) */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <Card
          onClick={() => setFilter('pending')}
          className={`p-3.5 cursor-pointer transition-all hover:border-indigo-300 ${
            filter === 'pending' ? 'ring-2 ring-indigo-500/30 border-indigo-400' : ''
          }`}
        >
          <div className="flex items-center gap-1.5 text-xs text-slate-500">
            <Hourglass className="h-3.5 w-3.5 text-indigo-600" />
            <span>Ожидают в очереди</span>
            <Filter className="h-3 w-3 ml-auto opacity-50" />
          </div>
          <div className="mt-1.5 text-2xl font-bold text-slate-900">
            {pendingSorted.length}{' '}
            {pendingSorted.length > 0 && (
              <span className="text-sm font-normal text-slate-500">(~{totalQueueMin} мин)</span>
            )}
          </div>
          <div className="mt-1 text-xs text-slate-500 truncate">
            {pendingSorted.length > 0 ? (
              <>
                ближайший <span className="font-semibold text-slate-700">{fmtCountdown(pendingSorted[0].runAt)}</span>
                {queueFinishAt && <> · финиш {fmtDateTime(queueFinishAt)}</>}
              </>
            ) : (
              'очередь пуста'
            )}
          </div>
        </Card>

        <Card className="p-3.5">
          <div className="flex items-center gap-1.5 text-xs text-slate-500">
            <PlayCircle className="h-3.5 w-3.5 text-emerald-600" />
            <span>Идут сейчас</span>
          </div>
          <div className="mt-1.5 text-2xl font-bold text-slate-900">{activeRuns.length}</div>
          <div className="mt-1 text-xs text-slate-500 truncate">
            {activeRuns.length > 0
              ? activeRuns.map((r: any, idx: number) => (
                  <React.Fragment key={r.id}>
                    {idx > 0 && ', '}
                    <Link to={`/runs/${r.id}`} className="text-indigo-600 hover:underline font-medium">
                      #{r.id} {r.scriptName}
                    </Link>
                  </React.Fragment>
                ))
              : 'нет активных тестов'}
          </div>
        </Card>

        <Link to="/generators" className="block group">
          <Card className="h-full p-3.5 transition-all group-hover:border-indigo-300">
            <div className="flex items-center gap-1.5 text-xs text-slate-500">
              <Server className="h-3.5 w-3.5 text-indigo-600" />
              <span>Генераторы</span>
              <ArrowRight className="h-3.5 w-3.5 ml-auto opacity-50" />
            </div>
            <div className="mt-1.5 text-2xl font-bold text-slate-900">
              {freeGenCount} <span className="text-sm font-normal text-slate-500">свободно</span>
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {onlineGenCount} из {generators.length} в сети
            </div>
          </Card>
        </Link>

        <Card
          onClick={() => setFilter('started')}
          className={`p-3.5 cursor-pointer transition-all hover:border-indigo-300 ${
            filter === 'started' ? 'ring-2 ring-indigo-500/30 border-indigo-400' : ''
          }`}
        >
          <div className="flex items-center gap-1.5 text-xs text-slate-500">
            <Clock className="h-3.5 w-3.5 text-indigo-600" />
            <span>История расписания</span>
            <Filter className="h-3 w-3 ml-auto opacity-50" />
          </div>
          <div className="mt-1.5 text-2xl font-bold text-slate-900">{startedItems.length}</div>
          <div className="mt-1 text-xs text-slate-500">
            запущено ·{' '}
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setFilter('other');
              }}
              className={`hover:underline cursor-pointer ${
                failedItems.length > 0 ? 'text-rose-600 font-semibold' : ''
              }`}
            >
              {failedItems.length} с ошибкой
            </button>
          </div>
        </Card>
      </div>

      {/* Таймлайн занятости генераторов */}
      <Card className="p-4">
        <ScheduleTimeline
          initialData={timeline}
          defaultHours={12}
          onJumpToScheduleRow={handleJumpToRow}
        />
      </Card>

      {/* Очередь и история расписания */}
      {items.length === 0 ? (
        <Card>
          <EmptyState
            icon={<CalendarClock className="h-6 w-6" />}
            title="Запланированных запусков пока нет"
            description="Назначьте запуск любого скрипта с нужным профилем или загрузите пакетный список из JSON."
            action={
              <div className="flex flex-wrap justify-center gap-2">
                <Button
                  size="sm"
                  disabled={scripts.length === 0}
                  onClick={() => {
                    const first = scripts[0];
                    if (first) {
                      setQmScriptId(String(first.id));
                      setQmCount(first.generatorCount || 1);
                    }
                    setQuickOpen(true);
                  }}
                >
                  <Plus className="h-4 w-4" />
                  Запланировать тест
                </Button>
                <Button variant="outline" size="sm" onClick={() => setImpOpen(true)}>
                  <Upload className="h-3.5 w-3.5" />
                  Импорт списка тестов
                </Button>
              </div>
            }
          />
        </Card>
      ) : (
        <Card className="overflow-hidden">
          <CardHeader className="flex-wrap">
            <div className="flex flex-wrap items-center gap-2">
              <div className="inline-flex rounded-lg bg-slate-100 p-0.5 text-xs">
                {[
                  { id: 'all', label: `Все (${items.length})` },
                  { id: 'pending', label: `Ожидают (${pendingSorted.length})` },
                  { id: 'started', label: `Запущены (${startedItems.length})` },
                  { id: 'other', label: `Ошибки / отмены (${otherItems.length})` },
                ].map((t) => (
                  <button
                    key={t.id}
                    type="button"
                    onClick={() => setFilter(t.id as any)}
                    className={`rounded-md px-2.5 py-1 font-medium cursor-pointer ${
                      filter === t.id ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600'
                    }`}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <div className="relative w-56">
                <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Поиск по скрипту, профилю…"
                  className="pl-8 h-8 text-xs"
                />
              </div>
              {historyCount > 0 && (
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() => actionMut.mutate({ url: '/api/v1/schedule/clear-history' })}
                >
                  Очистить историю ({historyCount})
                </Button>
              )}
              {pendingSorted.length > 0 && (
                <Button
                  variant="danger"
                  size="xs"
                  onClick={async () => {
                    const ok = await confirm({
                      title: 'Отменить ВСЕ ожидающие запуски?',
                      message: `Все ожидающие записи в расписании (${pendingSorted.length}) будут отменены.`,
                      confirmText: 'Отменить все',
                    });
                    if (ok) actionMut.mutate({ url: '/api/v1/schedule/cancel-all-pending' });
                  }}
                >
                  Отменить все ({pendingSorted.length})
                </Button>
              )}
            </div>
          </CardHeader>

          {/* Панель массовых операций */}
          {selectedIds.size > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-2 bg-indigo-50/70 border-b border-indigo-100 px-4 py-2 text-xs">
              <span className="font-semibold text-indigo-950">Выбрано: {selectedIds.size}</span>
              <div className="flex flex-wrap items-center gap-1.5">
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() =>
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-requeue',
                      body: { ids: Array.from(selectedIds), position: 'front' },
                    })
                  }
                >
                  <ChevronsUp className="h-3.5 w-3.5" />
                  В начало (после текущего)
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() =>
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-requeue',
                      body: { ids: Array.from(selectedIds), position: 'back' },
                    })
                  }
                >
                  <ChevronsDown className="h-3.5 w-3.5" />
                  В конец очереди
                </Button>
                <Button
                  variant="danger"
                  size="xs"
                  onClick={() => {
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-cancel',
                      body: { ids: Array.from(selectedIds) },
                    });
                    setSelectedIds(new Set());
                  }}
                >
                  <X className="h-3.5 w-3.5" />
                  Отменить ожидающие
                </Button>
                <Button variant="ghost" size="xs" onClick={() => setSelectedIds(new Set())}>
                  Снять выбор
                </Button>
              </div>
            </div>
          )}

          {visibleItems.length === 0 ? (
            <EmptyState icon={<CalendarClock className="h-6 w-6" />} title="Записей по выбранному фильтру нет" />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-200">
                  <tr>
                    <th className="w-20 px-3.5 py-2.5 text-left font-medium">
                      <div className="flex items-center gap-1.5">
                        <input
                          type="checkbox"
                          checked={visibleItems.length > 0 && visibleItems.every((i: any) => selectedIds.has(i.id))}
                          onChange={(e) => {
                            const next = new Set(selectedIds);
                            visibleItems.forEach((i: any) => {
                              if (e.target.checked) next.add(i.id);
                              else next.delete(i.id);
                            });
                            setSelectedIds(next);
                          }}
                        />
                        <span>№</span>
                      </div>
                    </th>
                    <th className="text-left font-medium px-3 py-2.5">Когда и длительность</th>
                    <th className="text-left font-medium px-3 py-2.5">Скрипт и профиль</th>
                    <th className="text-left font-medium px-3 py-2.5">Генераторы и параметры</th>
                    <th className="text-left font-medium px-3 py-2.5">Статус</th>
                    <th className="text-right font-medium px-4 py-2.5">Очередь и действия</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {visibleItems.map((it: any) => {
                    const qPos = queueOrderById.get(it.id);
                    const durMin = Math.max(1, Math.round((it.durationSec || 300) / 60));
                    const isHighlighted = highlightId === it.id;
                    return (
                      <tr
                        key={it.id}
                        id={`sch-row-${it.id}`}
                        className={`transition-colors ${
                          isHighlighted ? 'bg-amber-50/90' : 'hover:bg-slate-50/70'
                        }`}
                      >
                        <td className="px-3.5 py-2.5 whitespace-nowrap">
                          <div className="flex items-center gap-1.5">
                            <input
                              type="checkbox"
                              checked={selectedIds.has(it.id)}
                              onChange={(e) => {
                                const next = new Set(selectedIds);
                                if (e.target.checked) next.add(it.id);
                                else next.delete(it.id);
                                setSelectedIds(next);
                              }}
                            />
                            {qPos != null ? (
                              <Badge variant="info" className="rounded-full">
                                #{qPos}
                              </Badge>
                            ) : (
                              <span className="text-xs text-slate-400">#{it.id}</span>
                            )}
                          </div>
                        </td>
                        <td className="px-3 py-2.5 whitespace-nowrap">
                          <div className="flex items-center gap-1.5">
                            <span className="text-xs font-semibold text-slate-900">{fmtDateTime(it.runAt)}</span>
                            <span className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[11px] text-slate-600">
                              ~{durMin} мин
                            </span>
                          </div>
                          {it.pending && (
                            <div className="mt-1 flex items-center gap-2 text-[11px]">
                              <span className="text-slate-500">{fmtCountdown(it.runAt)}</span>
                              <button
                                type="button"
                                onClick={() =>
                                  actionMut.mutate({
                                    url: `/api/v1/schedule/${it.id}/shift`,
                                    body: { shiftForward: !it.shiftForward },
                                  })
                                }
                                title={
                                  it.shiftForward
                                    ? 'Автосдвиг включён: если генераторы освободятся раньше, тест сдвинется вперёд'
                                    : 'Время закреплено (нажмите, чтобы разрешить автосдвиг вперёд)'
                                }
                                className={`inline-flex items-center gap-0.5 cursor-pointer ${
                                  it.shiftForward ? 'text-indigo-600 font-semibold' : 'text-slate-400 hover:text-slate-600'
                                }`}
                              >
                                {it.shiftForward ? <FastForward className="h-3 w-3" /> : <Pin className="h-3 w-3" />}
                                {it.shiftForward ? 'автосдвиг' : 'закреплён'}
                              </button>
                            </div>
                          )}
                        </td>
                        <td className="px-3 py-2.5">
                          <Link
                            to={`/scripts/${it.scriptId}`}
                            className="font-semibold text-slate-900 hover:text-indigo-600"
                          >
                            {it.scriptName}
                          </Link>
                          {it.profileName && (
                            <div className="mt-1">
                              <span className="inline-flex items-center gap-1 rounded border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs text-slate-700">
                                <Layers className="h-3 w-3 text-indigo-600" />
                                {it.profileName}
                              </span>
                            </div>
                          )}
                          {it.error && <div className="text-xs text-rose-600 mt-1">{it.error}</div>}
                        </td>
                        <td className="px-3 py-2.5 text-xs text-slate-600">
                          <div>
                            {it.generatorNames?.length > 0
                              ? it.generatorNames.join(', ')
                              : `${it.generatorCount || 1} (свободные)`}
                          </div>
                          {it.createdBy && (
                            <div className="text-[11px] text-slate-400 mt-0.5">назначил: {it.createdBy}</div>
                          )}
                        </td>
                        <td className="px-3 py-2.5">
                          {it.status === 'PENDING' && <Badge variant="info">Ожидает</Badge>}
                          {it.status === 'STARTING' && <Badge variant="warning">Запускается</Badge>}
                          {it.status === 'STARTED' && (
                            <Link to={`/runs/${it.runId}`}>
                              <Badge variant="success">Запущен #{it.runId}</Badge>
                            </Link>
                          )}
                          {it.status === 'FAILED' && <Badge variant="danger">Ошибка</Badge>}
                          {it.status === 'CANCELLED' && <Badge variant="default">Отменён</Badge>}
                        </td>
                        <td className="px-4 py-2.5 text-right whitespace-nowrap">
                          <div className="inline-flex items-center gap-1">
                            {it.pending && (
                              <>
                                <Button
                                  variant="outline"
                                  size="xs"
                                  title="Запустить прямо сейчас"
                                  onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/start-now` })}
                                >
                                  <Play className="h-3.5 w-3.5 text-emerald-600 fill-emerald-600" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  title="Сдвинуть выше"
                                  onClick={() =>
                                    actionMut.mutate({
                                      url: `/api/v1/schedule/${it.id}/move`,
                                      body: { position: 'up' },
                                    })
                                  }
                                >
                                  <ArrowUp className="h-3.5 w-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  title="Сдвинуть ниже"
                                  onClick={() =>
                                    actionMut.mutate({
                                      url: `/api/v1/schedule/${it.id}/move`,
                                      body: { position: 'down' },
                                    })
                                  }
                                >
                                  <ArrowDown className="h-3.5 w-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  title="В начало очереди"
                                  onClick={() =>
                                    actionMut.mutate({
                                      url: `/api/v1/schedule/${it.id}/move`,
                                      body: { position: 'front' },
                                    })
                                  }
                                >
                                  <ChevronsUp className="h-3.5 w-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  title="В конец очереди"
                                  onClick={() =>
                                    actionMut.mutate({
                                      url: `/api/v1/schedule/${it.id}/move`,
                                      body: { position: 'back' },
                                    })
                                  }
                                >
                                  <ChevronsDown className="h-3.5 w-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  title="Отменить"
                                  onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/cancel` })}
                                >
                                  <X className="h-4 w-4 text-rose-600" />
                                </Button>
                              </>
                            )}
                            {!it.pending && (
                              <Button
                                variant="outline"
                                size="xs"
                                title="Снова поставить в конец очереди"
                                onClick={() =>
                                  actionMut.mutate({
                                    url: `/api/v1/schedule/${it.id}/requeue`,
                                    body: { position: 'back' },
                                  })
                                }
                              >
                                <RotateCcw className="h-3.5 w-3.5" />
                                Снова в очередь
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {/* Автопроверки метрик Prometheus + SSH */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
            <ShieldAlert className="h-4 w-4 text-indigo-600" />
            Автопроверки метрик Prometheus и SSH-действия ({guards.length})
          </div>
          {isAdmin && (
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                setPromTestRes(null);
                setSshTestRes(null);
                setGuardModal({
                  id: null,
                  name: '',
                  enabled: true,
                  promql: '',
                  cmp: 'GT',
                  threshold: 80,
                  unit: '%',
                  runWhen: 'BEFORE_START',
                  sshHost: generators[0]?.host || '',
                  sshPort: 22,
                  sshCredentialId: sshCredentials[0]?.id || '',
                  useSudo: false,
                  script: '',
                  timeoutSec: 60,
                  cooldownSec: 60,
                  blockOnFail: true,
                });
              }}
            >
              <Plus className="h-3.5 w-3.5" />
              Добавить правило
            </Button>
          )}
        </CardHeader>
        <CardBody className="p-0">
          {guards.length === 0 ? (
            <div className="py-8 text-center text-xs text-slate-400">
              Правила автопроверки метрик перед/после запуска не настроены
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {guards.map((g: any) => (
                <div key={g.id} className="p-4 flex flex-wrap items-center justify-between gap-3">
                  <div className="space-y-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <Switch
                        checked={g.enabled}
                        disabled={!isAdmin}
                        onChange={(v) =>
                          actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/toggle`, body: { enabled: v } })
                        }
                      />
                      <span className="font-semibold text-sm text-slate-900">{g.name}</span>
                      <Badge variant="default">{g.runWhenTitle}</Badge>
                      <Badge variant="info">{g.thresholdText}</Badge>
                      {g.lastStatusTitle && (
                        <Badge variant={g.lastStatus === 'OK' ? 'success' : 'warning'}>{g.lastStatusTitle}</Badge>
                      )}
                    </div>
                    <div className="text-xs font-mono text-slate-500 truncate max-w-2xl">{g.promql}</div>
                    <div className="text-xs text-slate-400">SSH: {g.hostSummary}</div>
                  </div>

                  {isAdmin && (
                    <div className="flex items-center gap-1.5">
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={() => actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/check` })}
                      >
                        Проверить
                      </Button>
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={() => actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/run-ssh` })}
                      >
                        <Terminal className="h-3.5 w-3.5" />
                        SSH
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setPromTestRes(null);
                          setSshTestRes(null);
                          setGuardModal({ ...g });
                        }}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить автопроверку?',
                            message: `Удалить правило «${g.name}»?`,
                          });
                          if (ok) actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/delete` });
                        }}
                      >
                        <Trash2 className="h-4 w-4 text-rose-500" />
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </CardBody>
      </Card>

      {/* Быстрая постановка в очередь */}
      <Modal open={quickOpen} onClose={() => setQuickOpen(false)} title="Запланировать тест">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await apiFetch(`/api/v1/scripts/${qmScriptId}/schedule`, {
                method: 'POST',
                body: {
                  profileId: qmProfileId ? Number(qmProfileId) : null,
                  queuePosition: qmPos === 'auto' ? null : qmPos,
                  count: qmCount,
                  shift: true,
                },
              });
              setQuickOpen(false);
              refresh();
              toast.success('Добавлено в расписание');
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <div>
            <label className="block font-medium text-slate-700 mb-1">Скрипт *</label>
            <Select
              required
              value={qmScriptId}
              onChange={(e) => {
                setQmScriptId(e.target.value);
                setQmProfileId('');
              }}
            >
              {scripts.map((s: any) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>
          </div>
          {selectedScriptObj?.profiles?.length > 0 && (
            <div>
              <label className="block font-medium text-slate-700 mb-1">Профиль нагрузки</label>
              <Select value={qmProfileId} onChange={(e) => setQmProfileId(e.target.value)}>
                <option value="">Нагрузка скрипта (как сохранена)</option>
                {selectedScriptObj.profiles.map((p: any) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </Select>
            </div>
          )}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-medium text-slate-700 mb-1">Генераторов</label>
              <Input type="number" min={1} value={qmCount} onChange={(e) => setQmCount(Number(e.target.value))} />
            </div>
            <div>
              <label className="block font-medium text-slate-700 mb-1">Позиция</label>
              <Select value={qmPos} onChange={(e) => setQmPos(e.target.value as any)}>
                <option value="auto">Ближайшее авто</option>
                <option value="front">В начало (после текущего)</option>
                <option value="back">В конец очереди</option>
              </Select>
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setQuickOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Запланировать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Импорт списка тестов JSON */}
      <Modal open={impOpen} onClose={() => setImpOpen(false)} title="Импорт списка тестов (JSON)" maxWidth="max-w-xl">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              const fd = new FormData();
              fd.append('json', impJson);
              fd.append('gapMinutes', String(impGapMin));
              const res = await apiUpload('/api/v1/schedule/import', fd);
              toast.success(res.message);
              setImpOpen(false);
              setImpJson('');
              refresh();
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <Textarea
            rows={8}
            required
            value={impJson}
            onChange={(e) => setImpJson(e.target.value)}
            placeholder='[{"script": "my-script", "profile": "Max", "count": 2}]'
            className="font-mono text-xs"
          />
          <div>
            <label className="block font-medium text-slate-700 mb-1">Пауза между тестами (мин)</label>
            <Input
              type="number"
              min={1}
              value={impGapMin}
              onChange={(e) => setImpGapMin(Number(e.target.value) || 5)}
              className="w-32"
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setImpOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Импорт профилей нагрузки */}
      <Modal
        open={impProfOpen}
        onClose={() => setImpProfOpen(false)}
        title="Импорт профилей нагрузки (JSON)"
        maxWidth="max-w-xl"
      >
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              const fd = new FormData();
              fd.append('json', impProfJson);
              fd.append('scheduleSequential', String(impProfSeq));
              fd.append('gapMinutes', String(impProfGap));
              const res = await apiUpload('/api/v1/schedule/import-profiles', fd);
              toast.success(res.message);
              setImpProfOpen(false);
              setImpProfJson('');
              refresh();
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <Textarea
            rows={8}
            required
            value={impProfJson}
            onChange={(e) => setImpProfJson(e.target.value)}
            placeholder='[{"script": "my-script", "name": "100% Max", ...}]'
            className="font-mono text-xs"
          />
          <Switch
            checked={impProfSeq}
            onChange={setImpProfSeq}
            label={<span className="text-xs">Сразу поставить импортированные профили последовательно в расписание</span>}
          />
          {impProfSeq && (
            <div>
              <label className="block font-medium text-slate-700 mb-1">Пауза между прогонами (мин)</label>
              <Input
                type="number"
                min={5}
                value={impProfGap}
                onChange={(e) => setImpProfGap(Number(e.target.value) || 5)}
                className="w-32"
              />
            </div>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setImpProfOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Импортировать профили
            </Button>
          </div>
        </form>
      </Modal>

      {/* Модалка правила Guard */}
      <Modal
        open={!!guardModal}
        onClose={() => setGuardModal(null)}
        title={guardModal?.id ? 'Редактировать автопроверку' : 'Новая автопроверка Prometheus + SSH'}
        maxWidth="max-w-2xl"
      >
        {guardModal && (
          <form
            className="space-y-3 text-xs"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                await apiFetch('/api/v1/schedule/guards', {
                  method: 'POST',
                  body: {
                    ...guardModal,
                    sshCredentialId: Number(guardModal.sshCredentialId),
                  },
                });
                setGuardModal(null);
                refresh();
                toast.success('Правило сохранено');
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Название *</label>
                <Input
                  required
                  value={guardModal.name}
                  onChange={(e) => setGuardModal({ ...guardModal, name: e.target.value })}
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Когда проверять</label>
                <Select
                  value={guardModal.runWhen}
                  onChange={(e) => setGuardModal({ ...guardModal, runWhen: e.target.value })}
                >
                  <option value="BEFORE_START">Перед стартом теста</option>
                  <option value="AFTER_RUN">После завершения теста</option>
                  <option value="BOTH">До и после теста</option>
                </Select>
              </div>
            </div>

            <div>
              <div className="flex justify-between mb-1">
                <label className="font-medium text-slate-700">PromQL запрос *</label>
                <button
                  type="button"
                  onClick={async () => {
                    setTestingGuard(true);
                    try {
                      const sp = new URLSearchParams({
                        promql: guardModal.promql,
                        cmp: guardModal.cmp,
                        threshold: String(guardModal.threshold),
                        unit: guardModal.unit || '',
                      });
                      const r = await apiFetch(`/schedule/guards/test-promql?${sp.toString()}`, { method: 'POST' });
                      setPromTestRes(r);
                    } catch (err: any) {
                      toast.error(err.message);
                    } finally {
                      setTestingGuard(false);
                    }
                  }}
                  className="text-indigo-600 hover:underline cursor-pointer"
                >
                  Проверить PromQL
                </button>
              </div>
              <Input
                required
                value={guardModal.promql}
                onChange={(e) => setGuardModal({ ...guardModal, promql: e.target.value })}
                className="font-mono text-xs"
              />
              {promTestRes && (
                <div className="mt-1.5 rounded bg-slate-100 p-2 text-[11px]">
                  {promTestRes.ok ? promTestRes.message : <span className="text-rose-600">{promTestRes.error}</span>}
                </div>
              )}
            </div>

            <div className="grid grid-cols-3 gap-2">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Условие срабатывания</label>
                <Select value={guardModal.cmp} onChange={(e) => setGuardModal({ ...guardModal, cmp: e.target.value })}>
                  <option value="GT">&gt;</option>
                  <option value="GTE">≥</option>
                  <option value="LT">&lt;</option>
                  <option value="LTE">≤</option>
                </Select>
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Порог</label>
                <Input
                  type="number"
                  step="any"
                  value={guardModal.threshold}
                  onChange={(e) => setGuardModal({ ...guardModal, threshold: Number(e.target.value) })}
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Ед. изм.</label>
                <Input value={guardModal.unit} onChange={(e) => setGuardModal({ ...guardModal, unit: e.target.value })} />
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2">
              <div className="col-span-2">
                <label className="block font-medium text-slate-700 mb-1">SSH хосты (через запятую) *</label>
                <Input
                  required
                  value={guardModal.sshHost}
                  onChange={(e) => setGuardModal({ ...guardModal, sshHost: e.target.value })}
                  placeholder="10.0.0.10:22, 10.0.0.11:22"
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">SSH-учётка *</label>
                <Select
                  required
                  value={guardModal.sshCredentialId}
                  onChange={(e) => setGuardModal({ ...guardModal, sshCredentialId: e.target.value })}
                >
                  <option value="">— выберите —</option>
                  {sshCredentials.map((c: any) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </Select>
              </div>
            </div>

            <div>
              <div className="flex justify-between mb-1">
                <label className="font-medium text-slate-700">SSH-скрипт при превышении порога *</label>
                <button
                  type="button"
                  disabled={testingGuard}
                  onClick={async () => {
                    setTestingGuard(true);
                    try {
                      const sp = new URLSearchParams({
                        sshHost: guardModal.sshHost,
                        sshPort: String(guardModal.sshPort || 22),
                        sshCredentialId: String(guardModal.sshCredentialId),
                        useSudo: String(!!guardModal.useSudo),
                        script: guardModal.script,
                        timeoutSec: String(guardModal.timeoutSec || 60),
                      });
                      const r = await apiFetch(`/schedule/guards/test-ssh?${sp.toString()}`, { method: 'POST' });
                      setSshTestRes(r);
                    } catch (err: any) {
                      toast.error(err.message);
                    } finally {
                      setTestingGuard(false);
                    }
                  }}
                  className="text-indigo-600 hover:underline cursor-pointer"
                >
                  Тест SSH
                </button>
              </div>
              <Textarea
                rows={4}
                required
                value={guardModal.script}
                onChange={(e) => setGuardModal({ ...guardModal, script: e.target.value })}
                className="font-mono text-xs"
              />
              {sshTestRes && (
                <pre className="mt-1.5 rounded bg-slate-900 text-slate-100 p-2 text-[11px] max-h-32 overflow-auto">
                  {sshTestRes.stdout || sshTestRes.stderr || sshTestRes.message || sshTestRes.error}
                </pre>
              )}
            </div>

            <div className="flex items-center gap-4">
              <Switch
                checked={!!guardModal.useSudo}
                onChange={(v) => setGuardModal({ ...guardModal, useSudo: v })}
                label={<span className="text-xs">Выполнять через sudo</span>}
              />
              <Switch
                checked={!!guardModal.blockOnFail}
                onChange={(v) => setGuardModal({ ...guardModal, blockOnFail: v })}
                label={<span className="text-xs">Блокировать старт при ошибке</span>}
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={() => setGuardModal(null)}>
                Отмена
              </Button>
              <Button type="submit" size="sm">
                Сохранить правило
              </Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
