import React, { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Braces,
  Bug,
  Calendar,
  CalendarPlus,
  ClipboardList,
  Info,
  Layers,
  Monitor,
  Pencil,
  Play,
  Server,
  Sliders,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { ScheduleTimeline, toLocalDatetimeValue } from '@/components/schedule/ScheduleTimeline';
import { ScriptHead } from '../scripts/ScriptHead';

type TimePreset = 'front' | 'back' | 'auto' | '+15m' | '+1h' | '22:00' | 'custom';

export function ScriptRunPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const initialProf = searchParams.get('profile') || '';
  const [mode, setMode] = useState<'now' | 'schedule'>(location.hash === '#schedule' ? 'schedule' : 'now');
  const [profileId, setProfileId] = useState<string>(initialProf);
  const [count, setCount] = useState<number>(1);
  const [nowHosts, setNowHosts] = useState<number[]>([]);
  const [schHosts, setSchHosts] = useState<number[]>([]);
  const [httpDebug, setHttpDebug] = useState('');
  const [selectedFlags, setSelectedFlags] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  // Schedule fields
  const [timePreset, setTimePreset] = useState<TimePreset>('auto');
  const [localRunAt, setLocalRunAt] = useState('');
  const [queuePos, setQueuePos] = useState<'' | 'front' | 'back'>('');
  const [shift, setShift] = useState(false);
  const [pctAllChip, setPctAllChip] = useState<number | null>(100);
  const [pctByOp, setPctByOp] = useState<Record<string, number>>({});
  const [envMode, setEnvMode] = useState<'script' | 'custom'>('script');
  const [envText, setEnvText] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['script-run-init', id, initialProf],
    queryFn: () =>
      apiFetch(`/api/v1/scripts/${id}/run-init${initialProf ? `?profile=${encodeURIComponent(initialProf)}` : ''}`),
  });

  useEffect(() => {
    if (!data) return;
    const pid = data.selectedProfileId != null ? String(data.selectedProfileId) : '';
    setProfileId(pid);
    setCount(data.defaultCount || 1);
    const det = data.profileDetails?.[pid] || data.profileDetails?.[''] || {};
    setEnvText(det.envText || '');
    const nextPct: Record<string, number> = {};
    (det.opNames || []).forEach((n: string) => {
      nextPct[n] = 100;
    });
    setPctByOp(nextPct);
    setPctAllChip(100);
  }, [data]);

  const switchMode = (nextMode: 'now' | 'schedule') => {
    setMode(nextMode);
    const nextUrl = `${location.pathname}${location.search}${nextMode === 'schedule' ? '#schedule' : ''}`;
    window.history.replaceState(null, '', nextUrl);
  };

  const handleProfileChange = (nextPid: string) => {
    setProfileId(nextPid);
    const det = data?.profileDetails?.[nextPid] || data?.profileDetails?.[''] || {};
    if (envMode === 'script') {
      setEnvText(det.envText || '');
    }
    const nextPct: Record<string, number> = {};
    (det.opNames || []).forEach((n: string) => {
      nextPct[n] = 100;
    });
    setPctByOp(nextPct);
    setPctAllChip(100);
  };

  const applyTimePreset = (p: TimePreset) => {
    setTimePreset(p);
    if (p === 'front' || p === 'back') {
      setLocalRunAt('');
      setQueuePos(p);
      return;
    }
    setQueuePos('');
    if (p === 'auto') {
      setLocalRunAt('');
      return;
    }
    const d = new Date();
    if (p === '+15m') {
      d.setMinutes(d.getMinutes() + 15);
    } else if (p === '+1h') {
      d.setHours(d.getHours() + 1);
    } else if (p === '22:00') {
      d.setHours(22, 0, 0, 0);
      if (d <= new Date()) d.setDate(d.getDate() + 1);
    }
    setLocalRunAt(toLocalDatetimeValue(d.getTime()));
  };

  const activeHosts = mode === 'schedule' ? schHosts : nowHosts;
  const effectiveGenCount = activeHosts.length > 0 ? activeHosts.length : Math.max(1, count);

  const timelineUrl = useMemo(() => {
    if (!id || mode !== 'schedule') return undefined;
    const sp = new URLSearchParams();
    if (profileId) sp.set('profile', profileId);
    sp.set('count', String(effectiveGenCount));
    return `/scripts/${id}/schedule/timeline?${sp.toString()}`;
  }, [id, mode, profileId, effectiveGenCount]);

  if (isLoading || !data) return <Spinner label="Подготовка параметров запуска…" />;

  const {
    script,
    profiles = [],
    profileDetails = {},
    generators = [],
    k6Flags = [],
    freeCount = 0,
    blockReason,
    autoSelectedProfile,
    pendingScheduleCount = 0,
  } = data;

  const activeDetail = profileDetails[profileId] || profileDetails[''] || {
    title: 'Нагрузка скрипта',
    ops: [],
    errors: [],
    durationSec: 0,
    env: {},
  };

  const envCount = Object.keys(activeDetail.env || {}).length;
  const changedOpsCount = Object.values(pctByOp).filter((v) => Number(v) !== 100).length;
  const activeFlagsCount = selectedFlags.length + (httpDebug ? 1 : 0);
  const loadEditUrl = `/scripts/${script.id}/load${profileId ? `?profile=${encodeURIComponent(profileId)}` : ''}`;

  const parseCustomEnv = () => {
    if (envMode !== 'custom') return {};
    const out: Record<string, string> = {};
    envText.split(/\r?\n/).forEach((l) => {
      const t = l.trim();
      if (!t || t.startsWith('#')) return;
      const eq = t.indexOf('=');
      if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1);
    });
    return out;
  };

  const handleRunNow = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/run`, {
        method: 'POST',
        body: {
          profileId: profileId ? Number(profileId) : null,
          count: nowHosts.length > 0 ? nowHosts.length : count,
          generatorIds: nowHosts,
          httpDebug,
          flags: selectedFlags,
        },
      });
      toast.success(`Запуск #${res.runId} стартовал`);
      navigate(`/runs/${res.runId}`);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const parsedDate = localRunAt ? new Date(localRunAt) : null;
      const runAtIso = parsedDate && !Number.isNaN(parsedDate.getTime()) ? parsedDate.toISOString() : null;
      const res = await apiFetch(`/api/v1/scripts/${id}/schedule`, {
        method: 'POST',
        body: {
          profileId: profileId ? Number(profileId) : null,
          runAt: queuePos ? null : runAtIso,
          queuePosition: queuePos || null,
          count: schHosts.length > 0 ? schHosts.length : count,
          generatorIds: schHosts,
          shift,
          intensityPct: pctByOp,
          env: parseCustomEnv(),
        },
      });
      qc.invalidateQueries({ queryKey: ['script-run-init', id] });
      qc.invalidateQueries({ queryKey: ['schedule-timeline'] });
      toast.success(res.message || 'Запуск запланирован', {
        action: {
          label: 'Открыть расписание',
          onClick: () => navigate('/schedule'),
        },
      });
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const blocked = generators.length === 0 || (activeDetail.errors?.length || 0) > 0;

  return (
    <div className="space-y-3.5">
      <ScriptHead script={script} />

      {blockReason && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900">{blockReason}</div>
      )}

      {autoSelectedProfile && (
        <div className="rounded-xl border border-sky-200 bg-sky-50 px-4 py-2.5 text-xs text-sky-900 flex items-center gap-2">
          <Info className="h-4 w-4 text-sky-600 shrink-0" />
          <span>
            Базовая нагрузка скрипта не задана, поэтому автоматически выбран готовый профиль нагрузки — можно сразу запускать или планировать.
          </span>
        </div>
      )}

      {activeDetail.errors?.length > 0 && (
        <div className="space-y-2">
          {activeDetail.errors.map((err: string, idx: number) => (
            <div
              key={idx}
              className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-sm text-rose-900"
            >
              <span>{err}</span>{' '}
              <Link to={loadEditUrl} className="font-semibold underline ml-1">
                Открыть нагрузку
              </Link>
            </div>
          ))}
        </div>
      )}

      {/* Переключатель режима: Запустить сейчас / Запланировать запуск (как в scripts/run.html) */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="inline-flex rounded-xl border border-slate-200 bg-slate-100 p-1 gap-1">
          <button
            type="button"
            onClick={() => switchMode('now')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors cursor-pointer ${
              mode === 'now' ? 'bg-white text-indigo-700 shadow-2xs' : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            <Play className="h-4 w-4 fill-current" />
            Запустить сейчас
          </button>
          <button
            type="button"
            onClick={() => switchMode('schedule')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors cursor-pointer ${
              mode === 'schedule' ? 'bg-white text-indigo-700 shadow-2xs' : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            <Calendar className="h-4 w-4" />
            Запланировать запуск
            {pendingScheduleCount > 0 && <Badge variant="info">{pendingScheduleCount}</Badge>}
          </button>
        </div>

        <Link to="/schedule">
          <Button variant="outline" size="sm">
            <Calendar className="h-3.5 w-3.5" />
            Всё расписание
          </Button>
        </Link>
      </div>

      {/* Сетка 8 / 4 с липкой правой сводкой «Что будет запущено» */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 items-start">
        <div className="lg:col-span-8">
          {mode === 'now' ? (
            /* Режим 1: Запустить сейчас */
            <Card>
              <CardBody>
                <form onSubmit={handleRunNow} className="space-y-4">
                  {generators.length === 0 ? (
                    <div className="text-sm text-slate-500">
                      Серверов нет —{' '}
                      <Link to="/generators?add=1" className="text-indigo-600 hover:underline font-medium">
                        добавьте сервер
                      </Link>
                      .
                    </div>
                  ) : (
                    <>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        {profiles.length > 0 && (
                          <div>
                            <label className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 mb-1">
                              <Layers className="h-4 w-4 text-indigo-600" />
                              Профиль нагрузки
                            </label>
                            <Select value={profileId} onChange={(e) => handleProfileChange(e.target.value)}>
                              <option value="">
                                {data.baseOk ? 'Нагрузка скрипта (как сохранена)' : 'Нагрузка скрипта (не описана)'}
                              </option>
                              {profiles.map((p: any) => (
                                <option key={p.id} value={p.id}>
                                  {p.name}
                                </option>
                              ))}
                            </Select>
                            <div className="text-xs text-slate-500 mt-1">
                              Запуск пойдёт с операциями и переменными профиля; нагрузка скрипта не меняется.
                            </div>
                          </div>
                        )}

                        <div>
                          <label className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 mb-1">
                            <Server className="h-4 w-4 text-indigo-600" />
                            Количество генераторов
                          </label>
                          <div className="flex items-center gap-2">
                            <Input
                              type="number"
                              min={1}
                              max={Math.max(1, freeCount)}
                              value={nowHosts.length > 0 ? nowHosts.length : count}
                              readOnly={nowHosts.length > 0}
                              onChange={(e) => setCount(Math.max(1, Number(e.target.value) || 1))}
                              className="w-28"
                            />
                            <Badge variant={freeCount > 0 ? 'success' : 'danger'}>
                              свободно {freeCount} из {generators.length}
                            </Badge>
                          </div>
                          <div className="text-xs text-slate-500 mt-1">Нагрузка делится поровну.</div>
                        </div>
                      </div>

                      {/* Конкретные серверы */}
                      <details className="group pt-1">
                        <summary className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 cursor-pointer select-none">
                          <Monitor className="h-3.5 w-3.5 text-slate-500" />
                          <span>Выбрать конкретные серверы (необязательно)</span>
                          {nowHosts.length > 0 && <Badge variant="info">выбрано: {nowHosts.length}</Badge>}
                        </summary>
                        <div className="mt-2.5 flex flex-wrap gap-2">
                          {generators.map((g: any) => {
                            const checked = nowHosts.includes(g.id);
                            return (
                              <label
                                key={g.id}
                                className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition-colors ${
                                  !g.availableForRuns
                                    ? 'opacity-60 bg-slate-50 border-slate-200 cursor-not-allowed'
                                    : checked
                                      ? 'border-indigo-500 bg-indigo-50 text-indigo-900 cursor-pointer'
                                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50 cursor-pointer'
                                }`}
                              >
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  disabled={!g.availableForRuns}
                                  onChange={(e) =>
                                    setNowHosts((prev) =>
                                      e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)
                                    )
                                  }
                                  className="rounded border-slate-300"
                                />
                                <span
                                  className={`h-2 w-2 rounded-full ${
                                    g.availableForRuns ? 'bg-emerald-500' : 'bg-rose-500'
                                  }`}
                                />
                                <span>{g.name}</span>
                                {!g.availableForRuns && (
                                  <span className="text-slate-400">({g.busy ? 'занят' : g.statusTitle})</span>
                                )}
                              </label>
                            );
                          })}
                        </div>
                        <div className="text-xs text-slate-500 mt-1.5">
                          Если отмечены серверы, количество берётся по ним.
                        </div>
                      </details>

                      {/* Параметры k6 и отладка */}
                      <details className="group pt-1">
                        <summary className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 cursor-pointer select-none">
                          <Bug className="h-3.5 w-3.5 text-slate-500" />
                          <span>Параметры k6 и отладка (необязательно)</span>
                          {activeFlagsCount > 0 && <Badge variant="warning">активно: {activeFlagsCount}</Badge>}
                        </summary>
                        <div className="mt-2.5 pt-2.5 border-t border-slate-100 space-y-3 text-xs">
                          <div>
                            <label className="block font-semibold text-slate-700 mb-1">HTTP-отладка</label>
                            <Select
                              value={httpDebug}
                              onChange={(e) => setHttpDebug(e.target.value)}
                              className="max-w-md text-xs h-8"
                            >
                              <option value="">выключена</option>
                              <option value="headers">заголовки запросов и ответов (--http-debug)</option>
                              <option value="full">заголовки и тела (--http-debug=full)</option>
                            </Select>
                            <div className="text-slate-500 mt-1">
                              Включайте на малой нагрузке: строк лога очень много. В телах могут быть секреты.
                            </div>
                          </div>

                          <div className="space-y-2">
                            {k6Flags.map((fl: any) => (
                              <label key={fl.name} className="flex items-start gap-2 cursor-pointer">
                                <input
                                  type="checkbox"
                                  checked={selectedFlags.includes(fl.name)}
                                  onChange={(e) =>
                                    setSelectedFlags((prev) =>
                                      e.target.checked ? [...prev, fl.name] : prev.filter((x) => x !== fl.name)
                                    )
                                  }
                                  className="mt-0.5 rounded border-slate-300"
                                />
                                <div>
                                  <span className="font-semibold text-slate-800">{fl.title}</span>{' '}
                                  <code className="text-slate-600 bg-slate-100 px-1 rounded">{fl.arg}</code>
                                  {fl.changesLoad && (
                                    <Badge variant="warning" className="ml-1.5">
                                      меняет нагрузку
                                    </Badge>
                                  )}
                                  <div className="text-slate-500">{fl.description}</div>
                                </div>
                              </label>
                            ))}
                          </div>
                        </div>
                      </details>
                    </>
                  )}

                  <div className="pt-3 border-t border-slate-100 flex items-center gap-2">
                    <Button type="submit" variant="success" loading={submitting} disabled={blocked}>
                      <Play className="h-4 w-4 fill-current" />
                      Запустить сейчас
                    </Button>
                    <Link to={`/scripts/${script.id}`}>
                      <Button variant="outline">Отмена</Button>
                    </Link>
                  </div>
                </form>
              </CardBody>
            </Card>
          ) : (
            /* Режим 2: Запланировать запуск (с интерактивным таймлайном!) */
            <Card>
              <CardBody>
                <form onSubmit={handleSchedule} className="space-y-4">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h2 className="text-sm font-semibold text-slate-900 flex items-center gap-1.5">
                      <Calendar className="h-4 w-4 text-indigo-600" />
                      Запланировать запуск
                    </h2>
                    {pendingScheduleCount > 0 && (
                      <span className="text-xs text-slate-500">
                        Уже в расписании: <strong className="text-slate-800">{pendingScheduleCount}</strong> ·{' '}
                        <Link to="/schedule" className="text-indigo-600 hover:underline">
                          открыть список
                        </Link>
                      </span>
                    )}
                  </div>

                  {/* Интерактивный таймлайн занятости генераторов */}
                  <ScheduleTimeline
                    endpointUrl={timelineUrl}
                    defaultHours={6}
                    interactive
                    selectedLocalAt={localRunAt}
                    queuePosition={queuePos}
                    neededCount={count}
                    pickedGeneratorIds={schHosts}
                    onSelectLocalAt={(val) => {
                      setLocalRunAt(val);
                      setQueuePos('');
                      setTimePreset('custom');
                    }}
                  />

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-semibold text-slate-800 mb-1">Время старта</label>
                      <Input
                        type="datetime-local"
                        value={localRunAt}
                        onChange={(e) => {
                          setLocalRunAt(e.target.value);
                          setQueuePos('');
                          setTimePreset('custom');
                        }}
                        className="h-8 text-xs"
                      />
                      <div className="flex flex-wrap gap-1.5 mt-2">
                        {(
                          [
                            ['front', 'В начало (после текущего)'],
                            ['back', 'В конец очереди'],
                            ['auto', 'Ближайшее авто'],
                            ['+15m', '+15 мин'],
                            ['+1h', '+1 час'],
                            ['22:00', '22:00'],
                          ] as const
                        ).map(([key, label]) => (
                          <button
                            key={key}
                            type="button"
                            onClick={() => applyTimePreset(key)}
                            className={`rounded-full px-2.5 py-0.5 text-xs font-medium border transition-colors cursor-pointer ${
                              timePreset === key
                                ? 'border-indigo-600 bg-indigo-600 text-white'
                                : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100'
                            }`}
                          >
                            {label}
                          </button>
                        ))}
                      </div>
                      <div className="text-xs text-indigo-700 font-semibold mt-1.5">
                        {queuePos === 'front'
                          ? 'Позиция: в начало очереди — сразу после текущего теста.'
                          : queuePos === 'back'
                            ? 'Позиция: в конец очереди — после всех ожидающих тестов.'
                            : !localRunAt
                              ? 'Ближайшее свободное окно подберётся автоматически.'
                              : ''}
                      </div>
                    </div>

                    <div>
                      <label className="block text-sm font-semibold text-slate-800 mb-1">Генераторов</label>
                      <Input
                        type="number"
                        min={1}
                        value={schHosts.length > 0 ? schHosts.length : count}
                        readOnly={schHosts.length > 0}
                        onChange={(e) => setCount(Math.max(1, Number(e.target.value) || 1))}
                        className="h-8 text-xs"
                      />
                      <div className="mt-2.5">
                        <Switch
                          checked={shift}
                          onChange={setShift}
                          label={
                            <span className="text-xs text-slate-700">
                              Сдвигать вперёд, если генераторы освободятся раньше
                            </span>
                          }
                        />
                      </div>
                    </div>
                  </div>

                  {profiles.length > 0 && (
                    <div>
                      <label className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 mb-1">
                        <Layers className="h-4 w-4 text-indigo-600" />
                        Профиль нагрузки
                      </label>
                      <Select
                        value={profileId}
                        onChange={(e) => handleProfileChange(e.target.value)}
                        className="max-w-md text-xs h-8"
                      >
                        <option value="">
                          {data.baseOk
                            ? 'Нагрузка скрипта (как сохранена на момент запуска)'
                            : 'Нагрузка скрипта (не описана)'}
                        </option>
                        {profiles.map((p: any) => (
                          <option key={p.id} value={p.id}>
                            {p.name}
                          </option>
                        ))}
                      </Select>
                    </div>
                  )}

                  {/* Конкретные серверы для расписания */}
                  {generators.length > 0 && (
                    <details className="pt-1">
                      <summary className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 cursor-pointer select-none">
                        <Monitor className="h-3.5 w-3.5 text-slate-500" />
                        <span>Выбрать конкретные серверы (необязательно)</span>
                        {schHosts.length > 0 && <Badge variant="info">выбрано: {schHosts.length}</Badge>}
                      </summary>
                      <div className="mt-2.5 flex flex-wrap gap-2">
                        {generators.map((g: any) => {
                          const checked = schHosts.includes(g.id);
                          return (
                            <label
                              key={g.id}
                              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs cursor-pointer transition-colors ${
                                checked
                                  ? 'border-indigo-500 bg-indigo-50 text-indigo-900'
                                  : g.status !== 'ONLINE'
                                    ? 'border-slate-200 bg-slate-50 text-slate-500'
                                    : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
                              }`}
                            >
                              <input
                                type="checkbox"
                                checked={checked}
                                onChange={(e) =>
                                  setSchHosts((prev) =>
                                    e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)
                                  )
                                }
                                className="rounded border-slate-300"
                              />
                              <span
                                className={`h-2 w-2 rounded-full ${
                                  g.status === 'ONLINE'
                                    ? g.busy
                                      ? 'bg-indigo-600'
                                      : 'bg-emerald-500'
                                    : 'bg-rose-500'
                                }`}
                              />
                              <span>{g.name}</span>
                              {g.status !== 'ONLINE' && <span className="text-slate-400">({g.statusTitle})</span>}
                            </label>
                          );
                        })}
                      </div>
                    </details>
                  )}

                  {/* Масштаб интенсивности по операциям */}
                  <details className="pt-2 border-t border-slate-100">
                    <summary className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 cursor-pointer select-none">
                      <Sliders className="h-3.5 w-3.5 text-indigo-600" />
                      <span>Масштаб интенсивности по операциям (необязательно)</span>
                      {changedOpsCount > 0 && <Badge variant="warning">изменено: {changedOpsCount}</Badge>}
                    </summary>
                    <div className="mt-2.5 space-y-2.5 text-xs">
                      <div className="flex flex-wrap gap-1.5">
                        {[50, 80, 100, 120, 150, 200].map((v) => (
                          <button
                            key={v}
                            type="button"
                            onClick={() => {
                              const next: Record<string, number> = {};
                              Object.keys(pctByOp).forEach((k) => {
                                next[k] = v;
                              });
                              setPctByOp(next);
                              setPctAllChip(v);
                            }}
                            className={`rounded-full px-2.5 py-0.5 border font-medium cursor-pointer ${
                              pctAllChip === v
                                ? 'border-indigo-600 bg-indigo-600 text-white'
                                : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100'
                            }`}
                          >
                            {v}%
                          </button>
                        ))}
                      </div>
                      {Object.keys(pctByOp).length === 0 ? (
                        <div className="text-slate-400">Нагрузка не описана.</div>
                      ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                          {Object.entries(pctByOp).map(([opName, val]) => (
                            <div
                              key={opName}
                              className="flex items-center rounded-lg border border-slate-300 overflow-hidden bg-slate-50"
                            >
                              <span className="px-2.5 py-1 flex-1 truncate text-slate-700">{opName}</span>
                              <input
                                type="number"
                                min={1}
                                max={10000}
                                step="any"
                                value={val}
                                onChange={(e) => {
                                  setPctAllChip(null);
                                  setPctByOp((p) => ({ ...p, [opName]: Number(e.target.value) }));
                                }}
                                className="w-20 h-7 bg-white border-x border-slate-300 px-2 text-right font-mono text-slate-900 focus:outline-none"
                              />
                              <span className="px-2 text-slate-500">%</span>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </details>

                  {/* Переменные запуска */}
                  <details className="pt-1">
                    <summary className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 cursor-pointer select-none">
                      <Braces className="h-3.5 w-3.5 text-slate-500" />
                      <span>Переменные запуска (необязательно)</span>
                    </summary>
                    <div className="mt-2 space-y-2 text-xs">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="envMode"
                          checked={envMode === 'script'}
                          onChange={() => {
                            setEnvMode('script');
                            setEnvText(activeDetail.envText || '');
                          }}
                        />
                        <span>Как в скрипте (или в выбранном профиле)</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="envMode"
                          checked={envMode === 'custom'}
                          onChange={() => setEnvMode('custom')}
                        />
                        <span>Свои для этого запуска (полный набор)</span>
                      </label>
                      <Textarea
                        rows={4}
                        disabled={envMode !== 'custom'}
                        value={envText}
                        onChange={(e) => setEnvText(e.target.value)}
                        placeholder="KEY=VALUE, по одной на строку"
                        className="font-mono text-xs"
                      />
                      <div className="text-slate-500">
                        Можно ссылаться на секреты: <code className="font-mono">{'${secret:ИМЯ}'}</code>.
                      </div>
                    </div>
                  </details>

                  <div className="pt-3 border-t border-slate-100">
                    <Button type="submit" variant="primary" loading={submitting} disabled={blocked}>
                      <CalendarPlus className="h-4 w-4" />
                      Запланировать запуск
                    </Button>
                  </div>
                </form>
              </CardBody>
            </Card>
          )}
        </div>

        {/* Правая липкая сводка «Что будет запущено» (как в scripts/run.html) */}
        <div className="lg:col-span-4 lg:sticky lg:top-4">
          <Card className="overflow-hidden">
            <CardHeader>
              <span className="flex items-center gap-1.5 text-sm font-semibold text-slate-900">
                <ClipboardList className="h-4 w-4 text-indigo-600" />
                Что будет запущено
              </span>
              <Badge variant={profileId ? 'info' : 'default'}>
                {activeDetail.title || 'Нагрузка скрипта'}
              </Badge>
            </CardHeader>

            <CardBody className="pb-3">
              <div className="flex flex-wrap gap-6 text-xs">
                <div>
                  <div className="text-slate-500">Длительность</div>
                  <strong className="text-sm text-slate-900">
                    {activeDetail.durationSec > 0
                      ? `${Math.round(activeDetail.durationSec / 6.0) / 10} мин`
                      : '—'}
                  </strong>
                </div>
                <div>
                  <div className="text-slate-500">Генераторов</div>
                  <strong className="text-sm text-slate-900">{effectiveGenCount}</strong>
                </div>
                <div>
                  <div className="text-slate-500">Переменных</div>
                  <strong className="text-sm text-slate-900">{envCount}</strong>
                </div>
              </div>
            </CardBody>

            <ul className="divide-y divide-slate-100 border-y border-slate-100 max-h-[46vh] overflow-y-auto text-xs">
              {!activeDetail.ops || activeDetail.ops.length === 0 ? (
                <li className="px-4 py-3 text-slate-400">Нагрузка не описана</li>
              ) : (
                activeDetail.ops.map((o: any) => (
                  <li key={o.name} className="px-4 py-2.5 flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-semibold text-slate-900 truncate">{o.name}</div>
                      <code className="text-[11px] text-slate-500">{o.execFn}</code>
                    </div>
                    <div className="text-right shrink-0">
                      <div className="text-slate-800">
                        {o.ratePerSec > 0
                          ? `${Math.round(o.ratePerSec * 100) / 100} оп/с`
                          : o.executor || '—'}
                      </div>
                      <div className="text-slate-500">
                        p{o.slaPercentile} ≤ {Math.round(o.slaThresholdMs)} мс
                      </div>
                    </div>
                  </li>
                ))
              )}
            </ul>

            <CardBody className="py-2.5">
              <Link to={loadEditUrl} className="inline-flex items-center gap-1 text-xs font-medium text-indigo-600 hover:underline">
                <Pencil className="h-3 w-3" />
                Изменить нагрузку
              </Link>
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
}
