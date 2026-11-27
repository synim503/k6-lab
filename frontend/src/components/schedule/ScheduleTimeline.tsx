import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { BarChart3, Search, X } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { Badge, Button } from '@/components/ui/primitives';

export interface TimelineGenerator {
  id: number;
  name: string;
  online: boolean;
  slots: number;
}

export interface TimelineBar {
  kind: 'run' | 'scheduled';
  runId?: number | null;
  scheduleId?: number | null;
  generatorId?: number | null;
  count: number;
  from: number;
  to: number;
  label: string;
  script: string;
  profile?: string | null;
  status?: string | null;
  genCount?: number | null;
  genNames?: string | null;
  intensity?: string | null;
  env?: string | null;
  by?: string | null;
  scheduled?: boolean;
}

export interface TimelineData {
  nowMs: number;
  durationSec: number;
  tailSec: number;
  generators: TimelineGenerator[];
  bars: TimelineBar[];
}

interface ScheduleTimelineProps {
  /** Переданные данные (например, из /api/v1/schedule) либо будут загружены с endpointUrl */
  initialData?: TimelineData | null;
  /** URL подгрузки (например, /schedule/timeline или /scripts/{id}/schedule/timeline?profile=...) */
  endpointUrl?: string;
  /** Масштаб по умолчанию в часах (12 для /schedule, 6 для запуска скрипта) */
  defaultHours?: number;
  /** Режим выбора времени кликом/перетаскиванием по шкале */
  interactive?: boolean;
  /** Текущее значение datetime-local (YYYY-MM-DDTHH:mm) */
  selectedLocalAt?: string;
  /** Позиция в очереди ('front' | 'back' | '') для подсказки в интерактивном режиме */
  queuePosition?: string;
  /** Сколько генераторов требуется (если не выбраны конкретные) */
  neededCount?: number;
  /** Явно выбранные ID генераторов */
  pickedGeneratorIds?: number[];
  /** Коллбэк выбора времени на шкале */
  onSelectLocalAt?: (localVal: string) => void;
  /** Коллбэк клика по ссылке записи расписания (подсветка строки в таблице /schedule) */
  onJumpToScheduleRow?: (scheduleId: number) => void;
}

const STEP_MS = 5 * 60_000;
const LEAD_MS = 60_000;
const SCALES = [
  { h: 3, label: '3 ч' },
  { h: 6, label: '6 ч' },
  { h: 12, label: '12 ч' },
  { h: 24, label: '24 ч' },
  { h: 72, label: '3 дня' },
];

function pad(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function hm(ms: number): string {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function dm(ms: number): string {
  const d = new Date(ms);
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${hm(ms)}`;
}

export function toLocalDatetimeValue(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${hm(ms)}`;
}

function ceil5(ms: number): number {
  return Math.ceil(ms / STEP_MS) * STEP_MS;
}

function overlap(b: TimelineBar, s: number, e: number): boolean {
  return b.from < e && b.to > s;
}

export function ScheduleTimeline({
  initialData,
  endpointUrl,
  defaultHours = 12,
  interactive = false,
  selectedLocalAt = '',
  queuePosition = '',
  neededCount = 1,
  pickedGeneratorIds = [],
  onSelectLocalAt,
  onJumpToScheduleRow,
}: ScheduleTimelineProps) {
  const [hours, setHours] = useState(defaultHours);
  const [shownKey, setShownKey] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [extraWarn, setExtraWarn] = useState<string | null>(null);
  const trackOverlayRef = useRef<HTMLDivElement>(null);

  const { data: fetchedData, isError } = useQuery<TimelineData>({
    queryKey: ['schedule-timeline', endpointUrl],
    queryFn: () => apiFetch(endpointUrl!),
    enabled: !!endpointUrl,
    refetchInterval: 20_000,
  });

  const data = endpointUrl ? fetchedData || initialData : initialData;

  const spanMs = hours * 3_600_000;
  const w0 = useMemo(() => {
    if (!data?.nowMs) return Math.floor(Date.now() / 60_000) * 60_000;
    return Math.floor(data.nowMs / 60_000) * 60_000;
  }, [data?.nowMs]);
  const w1 = w0 + spanMs;

  const ticks = useMemo(() => {
    let stepMs = hours <= 3 ? 1_800_000 : hours <= 12 ? 3_600_000 : hours <= 24 ? 10_800_000 : 43_200_000;
    if (hours === 12) stepMs = 7_200_000; // каждые 2 часа, чтобы подписи не слипались
    const mid = new Date(w0);
    mid.setHours(0, 0, 0, 0);
    let t = mid.getTime() + Math.ceil((w0 - mid.getTime()) / stepMs) * stepMs;
    const out: number[] = [];
    for (; t < w1; t += stepMs) out.push(t);
    return out;
  }, [w0, w1, hours]);

  const pct = (ms: number) => ((ms - w0) / spanMs) * 100;

  const poolRows = useMemo(() => {
    if (!data?.bars) return [];
    const seen = new Set<number>();
    const out: TimelineBar[] = [];
    for (const b of data.bars) {
      if (b.generatorId == null && b.scheduleId != null && !seen.has(b.scheduleId)) {
        seen.add(b.scheduleId);
        out.push(b);
      }
    }
    return out;
  }, [data?.bars]);

  const freeAt = (startMs: number) => {
    if (!data) {
      return { ok: true, avail: 0, need: 1, online: 0, bad: [] as number[], pool: 0, end: startMs };
    }
    const endMs = startMs + ((data.durationSec || 0) + (data.tailSec || 0)) * 1000;
    const needN = pickedGeneratorIds.length > 0 ? pickedGeneratorIds.length : Math.max(1, neededCount || 1);
    let free = 0;
    const freeIds = new Set<number>();
    let online = 0;

    for (const g of data.generators) {
      if (!g.online) continue;
      online++;
      let n = 0;
      for (const b of data.bars) {
        if (b.generatorId === g.id && overlap(b, startMs, endMs)) n++;
      }
      if (n < g.slots) {
        free++;
        freeIds.add(g.id);
      }
    }

    let pool = 0;
    const seen = new Set<number>();
    for (const b of data.bars) {
      if (b.generatorId == null && b.scheduleId != null && overlap(b, startMs, endMs) && !seen.has(b.scheduleId)) {
        seen.add(b.scheduleId);
        pool += b.count || 1;
      }
    }

    const avail = Math.max(0, free - pool);
    const bad: number[] = [];
    let ok: boolean;
    if (pickedGeneratorIds.length > 0) {
      for (const id of pickedGeneratorIds) {
        if (!freeIds.has(id)) bad.push(id);
      }
      ok = bad.length === 0;
    } else {
      ok = avail >= needN;
    }
    return { ok, avail, need: needN, online, bad, pool, end: endMs };
  };

  const chosenMs = useMemo(() => {
    if (!interactive || !selectedLocalAt) return null;
    const d = new Date(selectedLocalAt);
    return Number.isNaN(d.getTime()) ? null : d.getTime();
  }, [interactive, selectedLocalAt]);

  const selectionState = useMemo(() => {
    if (!interactive || !data || chosenMs === null) return null;
    const f = freeAt(chosenMs);
    const l = pct(chosenMs);
    const r = pct(f.end);
    return { ...f, startMs: chosenMs, l, r };
  }, [interactive, data, chosenMs, hours, w0, neededCount, pickedGeneratorIds]);

  const pickTimeFromClientX = (clientX: number) => {
    if (!interactive || !onSelectLocalAt || !trackOverlayRef.current) return;
    const rect = trackOverlayRef.current.getBoundingClientRect();
    if (rect.width <= 0) return;
    const frac = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const rawMs = Math.round((w0 + frac * spanMs) / STEP_MS) * STEP_MS;
    const clamped = Math.max(rawMs, ceil5(Date.now() + LEAD_MS));
    setExtraWarn(null);
    onSelectLocalAt(toLocalDatetimeValue(clamped));
  };

  useEffect(() => {
    if (!dragging) return;
    const onUp = () => setDragging(false);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [dragging]);

  const handleFindNextFree = () => {
    if (!data || !onSelectLocalAt) return;
    let t = ceil5(Date.now() + LEAD_MS);
    const limit = t + 7 * 86_400_000;
    for (; t < limit; t += STEP_MS) {
      const f = freeAt(t);
      if (f.ok) {
        onSelectLocalAt(toLocalDatetimeValue(t));
        if (f.end > w1) {
          setExtraWarn('Время за пределами текущей шкалы: выберите больший масштаб (24 ч / 3 дня).');
        } else {
          setExtraWarn(null);
        }
        return;
      }
    }
    const needN = pickedGeneratorIds.length > 0 ? pickedGeneratorIds.length : Math.max(1, neededCount || 1);
    setExtraWarn(`В ближайшие 7 дней не нашлось окна, когда свободно ${needN} генераторов.`);
  };

  const selectedBar = useMemo(() => {
    if (!shownKey || !data?.bars) return null;
    return data.bars.find((b) => (b.runId ? `r${b.runId}` : `s${b.scheduleId}`) === shownKey) || null;
  }, [shownKey, data?.bars]);

  const renderBar = (b: TimelineBar, customText?: string) => {
    const l = Math.max(0, pct(b.from));
    const r = Math.min(100, pct(b.to));
    if (r <= 0 || l >= 100 || r <= l) return null;
    const key = b.runId ? `r${b.runId}` : `s${b.scheduleId}`;
    const isSel = shownKey === key;
    const title = `${b.kind === 'run' ? 'Идёт сейчас' : 'Запланирован'}: ${b.label}\n${dm(b.from)} — ${dm(b.to)}${
      b.by ? `\nНазначил: ${b.by}` : ''
    }\nКлик — подробности`;

    return (
      <div
        key={`${key}-${b.generatorId ?? 'pool'}`}
        title={title}
        onPointerDown={(e) => e.stopPropagation()}
        onClick={(e) => {
          e.stopPropagation();
          const next = shownKey === key ? null : key;
          setShownKey(next);
          if (next && b.scheduleId && onJumpToScheduleRow && !interactive) {
            onJumpToScheduleRow(b.scheduleId);
          }
        }}
        style={{
          left: `${l}%`,
          width: `${r - l}%`,
          background:
            b.kind === 'run'
              ? '#4f46e5'
              : 'repeating-linear-gradient(45deg, #c2410c, #c2410c 6px, #9a3412 6px, #9a3412 12px)',
        }}
        className={`absolute top-[3px] bottom-[3px] z-10 rounded px-1.5 text-[11px] leading-[20px] font-medium text-white truncate cursor-pointer select-none ${
          isSel ? 'ring-2 ring-slate-900' : ''
        }`}
      >
        {customText || b.label}
      </div>
    );
  };

  return (
    <div className="space-y-1.5">
      {/* Шапка таймлайна */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm font-semibold text-slate-900 flex items-center gap-1.5">
          <BarChart3 className="h-4 w-4 text-indigo-600" />
          <span>Занятость генераторов</span>
          {interactive && (
            <span className="text-xs font-normal text-slate-500">(кликните по шкале, чтобы выбрать старт)</span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-lg border border-slate-300 bg-white overflow-hidden">
            {SCALES.map((s) => (
              <button
                key={s.h}
                type="button"
                onClick={() => setHours(s.h)}
                className={`px-2.5 py-1 text-xs font-medium transition-colors border-r last:border-r-0 border-slate-200 cursor-pointer ${
                  hours === s.h ? 'bg-slate-800 text-white' : 'text-slate-600 hover:bg-slate-50'
                }`}
              >
                {s.label}
              </button>
            ))}
          </div>
          {interactive && (
            <Button variant="outline" size="xs" onClick={handleFindNextFree} className="border-indigo-300 text-indigo-700">
              <Search className="h-3 w-3" />
              Ближайшее свободное
            </Button>
          )}
        </div>
      </div>

      {/* Полотно таймлайна */}
      <div className="rounded-xl border border-slate-200 bg-white p-1 select-none">
        {!data ? (
          <div className="p-3 text-xs text-slate-400">
            {isError ? 'Не удалось загрузить занятость генераторов.' : 'Загрузка…'}
          </div>
        ) : (
          <div className="relative max-h-[520px] overflow-y-auto text-xs">
            {/* Верхняя ось времени */}
            <div className="sticky top-0 z-20 flex h-[26px] items-stretch bg-white">
              <div className="w-40 sm:w-48 shrink-0 px-1.5 pt-1 text-slate-500" />
              <div className="relative flex-1 min-w-0">
                {ticks.map((t) => (
                  <div
                    key={t}
                    style={{ left: `${pct(t)}%` }}
                    className="absolute top-1 -translate-x-1/2 text-[11px] text-slate-500 whitespace-nowrap"
                  >
                    {hours > 24 ? dm(t) : hm(t)}
                  </div>
                ))}
              </div>
            </div>

            {/* Область строк + интерактивный оверлей */}
            <div className="relative">
              {/* Запланированные «любые свободные» */}
              {poolRows.map((b) => (
                <div key={`pool-${b.scheduleId}`} className="flex h-[26px] items-stretch">
                  <div
                    className="w-40 sm:w-48 shrink-0 truncate px-1.5 pt-1 font-semibold text-amber-700"
                    title="Запись расписания займёт любые свободные генераторы на момент старта"
                  >
                    любые свободные ×{b.count}
                  </div>
                  <div className="relative flex-1 min-w-0 bg-slate-100/80 border-b-2 border-white">
                    {ticks.map((t) => (
                      <div
                        key={t}
                        style={{ left: `${pct(t)}%` }}
                        className="absolute inset-y-0 w-px bg-slate-200/80 pointer-events-none"
                      />
                    ))}
                    {data.nowMs >= w0 && data.nowMs <= w1 && (
                      <div
                        style={{ left: `${pct(data.nowMs)}%` }}
                        title="сейчас"
                        className="absolute inset-y-0 w-[2px] bg-rose-600/85 z-10 pointer-events-none"
                      />
                    )}
                    {renderBar(b, `${b.label} · ×${b.count}`)}
                  </div>
                </div>
              ))}

              {/* Строки конкретных генераторов */}
              {data.generators.length === 0 ? (
                <div className="p-2 text-xs text-slate-400">Серверов нет.</div>
              ) : (
                data.generators.map((g) => (
                  <div key={g.id} className="flex h-[26px] items-stretch">
                    <div
                      className={`w-40 sm:w-48 shrink-0 truncate px-1.5 pt-1 text-slate-600 ${
                        g.online ? '' : 'opacity-60'
                      }`}
                      title={`${g.name}${g.online ? '' : ' (не в сети / обслуживание)'}`}
                    >
                      {g.name}
                    </div>
                    <div
                      style={
                        g.online
                          ? undefined
                          : {
                              background:
                                'repeating-linear-gradient(45deg, #f1f3f9, #f1f3f9 6px, #e5e8f2 6px, #e5e8f2 12px)',
                            }
                      }
                      className="relative flex-1 min-w-0 bg-slate-100/80 border-b-2 border-white"
                    >
                      {ticks.map((t) => (
                        <div
                          key={t}
                          style={{ left: `${pct(t)}%` }}
                          className="absolute inset-y-0 w-px bg-slate-200/80 pointer-events-none"
                        />
                      ))}
                      {data.nowMs >= w0 && data.nowMs <= w1 && (
                        <div
                          style={{ left: `${pct(data.nowMs)}%` }}
                          title="сейчас"
                          className="absolute inset-y-0 w-[2px] bg-rose-600/85 z-10 pointer-events-none"
                        />
                      )}
                      {data.bars.filter((b) => b.generatorId === g.id).map((b) => renderBar(b))}
                    </div>
                  </div>
                ))
              )}

              {/* Оверлей выбора интервала для интерактивного режима */}
              {interactive && (
                <div
                  ref={trackOverlayRef}
                  onPointerDown={(e) => {
                    if (e.button !== 0 && e.pointerType === 'mouse') return;
                    setDragging(true);
                    pickTimeFromClientX(e.clientX);
                  }}
                  onPointerMove={(e) => {
                    if (dragging) pickTimeFromClientX(e.clientX);
                  }}
                  className="absolute inset-y-0 left-40 sm:left-48 right-0 z-[5] cursor-crosshair touch-none"
                >
                  {selectionState && selectionState.r > 0 && selectionState.l < 100 && (
                    <div
                      style={{
                        left: `${Math.max(0, selectionState.l)}%`,
                        width: `${Math.min(100, selectionState.r) - Math.max(0, selectionState.l)}%`,
                      }}
                      className={`absolute inset-y-0 rounded border-2 pointer-events-none ${
                        selectionState.ok
                          ? 'border-emerald-600 bg-emerald-500/20'
                          : 'border-rose-600 bg-rose-500/20'
                      }`}
                    />
                  )}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Подсказка проверки доступности генераторов */}
      {interactive && data && (
        <div className="text-xs">
          {selectionState ? (
            selectionState.ok ? (
              <span className="text-emerald-700 font-medium">
                ✓ {dm(selectionState.startMs)} — {hm(selectionState.end)} (~{Math.round(data.durationSec / 60)} мин):
                свободно {selectionState.avail} из {selectionState.online} генераторов в сети, нужно {selectionState.need}
                {selectionState.pool > 0 ? ` (уже запланировано на это время: ${selectionState.pool})` : ''}.
                {(selectionState.r < 0 || selectionState.l > 100) && ' (выбранное время вне шкалы)'}
              </span>
            ) : (
              <span className="text-rose-600 font-medium">
                ✗ {dm(selectionState.startMs)} — {hm(selectionState.end)} (~{Math.round(data.durationSec / 60)} мин):{' '}
                {selectionState.bad.length > 0
                  ? `заняты или не в сети: ${selectionState.bad
                      .map((gid) => data.generators.find((g) => g.id === gid)?.name || `#${gid}`)
                      .join(', ')}`
                  : `свободно ${selectionState.avail}, нужно ${selectionState.need}${
                      selectionState.pool > 0 ? ` (запланировано другими запусками: ${selectionState.pool})` : ''
                    }`}
                . Запуск будет пропущен — выберите другое время или нажмите «Ближайшее свободное».
              </span>
            )
          ) : queuePosition === 'front' ? (
            <span className="text-indigo-700 font-medium">
              Тест встанет в начало очереди сразу после текущего запуска (~{Math.round(data.durationSec / 60)} мин).
            </span>
          ) : queuePosition === 'back' ? (
            <span className="text-indigo-700 font-medium">
              Тест встанет в конец очереди после всех ожидающих (~{Math.round(data.durationSec / 60)} мин).
            </span>
          ) : (
            <span className="text-slate-500">
              Время не выбрано — подберётся ближайшее свободное окно. Длительность: ~{Math.round(data.durationSec / 60)} мин.
            </span>
          )}
          {extraWarn && <div className="text-amber-700 mt-0.5">{extraWarn}</div>}
        </div>
      )}

      {/* Легенда */}
      <div className="flex flex-wrap items-center gap-3.5 text-[11px] text-slate-500">
        <span className="inline-flex items-center gap-1">
          <i className="inline-block w-3.5 h-2.5 rounded-[2px] bg-indigo-600" />
          идёт сейчас
        </span>
        <span className="inline-flex items-center gap-1">
          <i
            className="inline-block w-3.5 h-2.5 rounded-[2px]"
            style={{
              background: 'repeating-linear-gradient(45deg, #c2410c, #c2410c 4px, #9a3412 4px, #9a3412 8px)',
            }}
          />
          запланирован
        </span>
        <span className="inline-flex items-center gap-1">
          <i
            className="inline-block w-3.5 h-2.5 rounded-[2px] border border-slate-300"
            style={{
              background: 'repeating-linear-gradient(45deg, #f1f3f9, #f1f3f9 3px, #e5e8f2 3px, #e5e8f2 6px)',
            }}
          />
          не в сети / обслуживание
        </span>
        {interactive && (
          <span className="inline-flex items-center gap-1">
            <i className="inline-block w-3.5 h-2.5 rounded-[2px] border-2 border-emerald-600 bg-emerald-500/25" />
            ваш запуск
          </span>
        )}
        <span className="inline-flex items-center gap-1">
          <i className="inline-block w-[3px] h-2.5 rounded-[1px] bg-rose-600" />
          сейчас
        </span>
        {!interactive && (
          <span>Клик по полосе — подробности (скрипт, профиль, переменные, кто назначил).</span>
        )}
      </div>

      {/* Карточка подробностей выбранной полосы */}
      {selectedBar && (
        <div className="rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2.5 text-xs">
          <div className="flex items-center justify-between gap-2 mb-2">
            <div className="flex items-center gap-2">
              {selectedBar.kind === 'run' ? (
                <Badge variant="info">идёт сейчас</Badge>
              ) : (
                <span className="inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium bg-orange-700 text-white">
                  запланирован
                </span>
              )}
              <strong className="text-slate-900">{selectedBar.script}</strong>
            </div>
            <button
              type="button"
              onClick={() => setShownKey(null)}
              className="rounded p-1 text-slate-400 hover:bg-slate-200 hover:text-slate-700 cursor-pointer"
              aria-label="Закрыть"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
          <dl className="grid grid-cols-[135px_1fr] gap-x-3 gap-y-1">
            <dt className="text-slate-500 font-medium">Профиль нагрузки</dt>
            <dd className="text-slate-800">{selectedBar.profile || 'нагрузка скрипта'}</dd>

            <dt className="text-slate-500 font-medium">{selectedBar.kind === 'run' ? 'Идёт' : 'Старт'}</dt>
            <dd className="text-slate-800">
              {dm(selectedBar.from)} — {dm(selectedBar.to)} (занимает генераторы, ~
              {Math.round((selectedBar.to - selectedBar.from) / 60_000)} мин с запасом)
            </dd>

            {selectedBar.status && (
              <>
                <dt className="text-slate-500 font-medium">Статус</dt>
                <dd className="text-slate-800">
                  {selectedBar.status === 'STARTING'
                    ? 'запускается'
                    : selectedBar.status === 'PENDING'
                      ? 'ожидает назначенного времени'
                      : selectedBar.status}
                </dd>
              </>
            )}

            {!!selectedBar.genCount && (
              <>
                <dt className="text-slate-500 font-medium">Генераторов</dt>
                <dd className="text-slate-800">
                  {selectedBar.genCount}
                  {selectedBar.genNames ? `: ${selectedBar.genNames}` : ' (любые свободные на момент старта)'}
                </dd>
              </>
            )}

            {selectedBar.intensity && (
              <>
                <dt className="text-slate-500 font-medium">Интенсивность</dt>
                <dd className="text-slate-800 font-mono">{selectedBar.intensity}</dd>
              </>
            )}

            {selectedBar.env && (
              <>
                <dt className="text-slate-500 font-medium">Переменные</dt>
                <dd className="text-slate-800 font-mono whitespace-pre-wrap">{selectedBar.env}</dd>
              </>
            )}

            {selectedBar.by && (
              <>
                <dt className="text-slate-500 font-medium">Назначил</dt>
                <dd className="text-slate-800">{selectedBar.by}</dd>
              </>
            )}

            {selectedBar.kind === 'run' && selectedBar.scheduled && (
              <>
                <dt className="text-slate-500 font-medium">Источник</dt>
                <dd className="text-slate-800">запуск по расписанию</dd>
              </>
            )}

            {(selectedBar.runId || selectedBar.scheduleId) && (
              <>
                <dt className="text-slate-500 font-medium">Ссылка</dt>
                <dd>
                  {selectedBar.runId ? (
                    <Link to={`/runs/${selectedBar.runId}`} className="text-indigo-600 hover:underline font-medium">
                      запуск #{selectedBar.runId}
                    </Link>
                  ) : selectedBar.scheduleId ? (
                    onJumpToScheduleRow ? (
                      <button
                        type="button"
                        onClick={() => onJumpToScheduleRow(selectedBar.scheduleId!)}
                        className="text-indigo-600 hover:underline font-medium cursor-pointer"
                      >
                        запись расписания #{selectedBar.scheduleId} (показать в таблице)
                      </button>
                    ) : (
                      <Link to="/schedule" className="text-indigo-600 hover:underline font-medium">
                        запись расписания #{selectedBar.scheduleId}
                      </Link>
                    )
                  ) : null}
                </dd>
              </>
            )}
          </dl>
        </div>
      )}
    </div>
  );
}
