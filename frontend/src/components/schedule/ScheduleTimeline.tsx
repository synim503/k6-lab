import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  BarChart3,
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  Crosshair,
  Search,
  X,
} from 'lucide-react';
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
  historical?: boolean;
  durationSec?: number | null;
}

export interface TimelineData {
  nowMs: number;
  durationSec?: number;
  tailSec: number;
  generators: TimelineGenerator[];
  bars: TimelineBar[];
}

interface ScheduleTimelineProps {
  /** Переданные данные (например, из /api/v1/schedule) либо будут загружены с endpointUrl */
  initialData?: TimelineData | null;
  /** URL подгрузки (например, /api/v1/schedule/timeline или /scripts/{id}/schedule/timeline?profile=...) */
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
  { h: 1, label: '1 ч' },
  { h: 3, label: '3 ч' },
  { h: 6, label: '6 ч' },
  { h: 12, label: '12 ч' },
  { h: 24, label: '24 ч' },
  { h: 72, label: '3 дня' },
  { h: 168, label: '7 дней' },
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

function barVisualStyle(b: TimelineBar): { bg: string; badgeLabel: string } {
  if (b.kind === 'scheduled') {
    return {
      bg: 'repeating-linear-gradient(45deg, #c2410c, #c2410c 6px, #9a3412 6px, #9a3412 12px)',
      badgeLabel: 'Запланирован',
    };
  }
  if (b.historical) {
    if (b.status === 'FAILED') {
      return { bg: '#dc2626', badgeLabel: 'Завершён с ошибкой' };
    }
    if (b.status === 'STOPPED' || b.status === 'CANCELLED') {
      return { bg: '#64748b', badgeLabel: 'Остановлен' };
    }
    return { bg: '#059669', badgeLabel: 'Завершён' };
  }
  return { bg: '#4f46e5', badgeLabel: 'Идёт сейчас' };
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
  /** Смещение начала видимого окна относительно «сейчас» (мс) */
  const [offsetMs, setOffsetMs] = useState(0);
  const [shownKey, setShownKey] = useState<string | null>(null);
  const [hoverInfo, setHoverInfo] = useState<{ bar: TimelineBar; x: number; y: number } | null>(null);
  const [pickingTime, setPickingTime] = useState(false);
  const [panning, setPanning] = useState(false);
  const [extraWarn, setExtraWarn] = useState<string | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
  const trackAreaRef = useRef<HTMLDivElement>(null);
  const panStartRef = useRef<{ clientX: number; startOffsetMs: number; moved: boolean } | null>(null);

  const { data: fetchedData, isError } = useQuery<TimelineData>({
    queryKey: ['schedule-timeline', endpointUrl],
    queryFn: () => apiFetch(endpointUrl!),
    enabled: !!endpointUrl,
    refetchInterval: 20_000,
  });

  const baseData = endpointUrl ? fetchedData || initialData : initialData;
  const spanMs = hours * 3_600_000;

  // Небольшой отступ слева в режиме обзора (-5% окна), чтобы линия «сейчас» и текущие тесты были отлично видны
  const anchorNowMs = useMemo(() => {
    if (!baseData?.nowMs) return Math.floor(Date.now() / 60_000) * 60_000;
    return Math.floor(baseData.nowMs / 60_000) * 60_000;
  }, [baseData?.nowMs]);

  const defaultLeadBack = interactive ? 0 : Math.min(30 * 60_000, Math.round(spanMs * 0.06));
  const w0 = anchorNowMs - defaultLeadBack + offsetMs;
  const w1 = w0 + spanMs;

  // Если пользователь прокрутил шкалу далеко в прошлое (> 20 ч назад), подгружаем архивный диапазон с бэкенда
  const needRangeFetch = !endpointUrl && w0 < anchorNowMs - 20 * 3_600_000;
  const rangeFromBucket = Math.floor((w0 - 6 * 3_600_000) / (3 * 3_600_000)) * (3 * 3_600_000);
  const rangeToBucket = Math.ceil((w1 + 6 * 3_600_000) / (3 * 3_600_000)) * (3 * 3_600_000);

  const { data: rangeData } = useQuery<TimelineData>({
    queryKey: ['schedule-timeline-range', rangeFromBucket, rangeToBucket],
    queryFn: () => apiFetch(`/api/v1/schedule/timeline?from=${rangeFromBucket}&to=${rangeToBucket}`),
    enabled: needRangeFetch,
    staleTime: 30_000,
  });

  const data = useMemo<TimelineData | null | undefined>(() => {
    if (!baseData) return rangeData;
    if (!rangeData?.bars?.length) return baseData;
    const map = new Map<string, TimelineBar>();
    for (const b of rangeData.bars) {
      map.set(`${b.kind}-${b.runId ?? ''}-${b.scheduleId ?? ''}-${b.generatorId ?? 'pool'}`, b);
    }
    for (const b of baseData.bars) {
      map.set(`${b.kind}-${b.runId ?? ''}-${b.scheduleId ?? ''}-${b.generatorId ?? 'pool'}`, b);
    }
    return { ...baseData, bars: Array.from(map.values()) };
  }, [baseData, rangeData]);

  const ticks = useMemo(() => {
    let stepMs =
      hours <= 1
        ? 600_000
        : hours <= 3
        ? 1_800_000
        : hours <= 6
        ? 3_600_000
        : hours <= 12
        ? 7_200_000
        : hours <= 24
        ? 10_800_000
        : hours <= 72
        ? 43_200_000
        : 86_400_000;
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
        if (!b.historical && b.generatorId === g.id && overlap(b, startMs, endMs)) n++;
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

  const getTrackRect = () => {
    if (!trackAreaRef.current) return null;
    const full = trackAreaRef.current.getBoundingClientRect();
    // Вычитаем левую колонку с именами генераторов (w-40 = 160px / sm:w-48 = 192px)
    const labelCol = window.innerWidth >= 640 ? 192 : 160;
    return { left: full.left + labelCol, width: Math.max(1, full.width - labelCol) };
  };

  const pickTimeFromClientX = (clientX: number) => {
    if (!interactive || !onSelectLocalAt) return;
    const rect = getTrackRect();
    if (!rect || rect.width <= 0) return;
    const frac = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const rawMs = Math.round((w0 + frac * spanMs) / STEP_MS) * STEP_MS;
    const clamped = Math.max(rawMs, ceil5(Date.now() + LEAD_MS));
    setExtraWarn(null);
    onSelectLocalAt(toLocalDatetimeValue(clamped));
  };

  // Перетаскивание шкалы мышью / тачем (drag-to-pan) и выбор времени
  useEffect(() => {
    if (!pickingTime && !panning) return;
    const onMove = (e: PointerEvent) => {
      if (panning && panStartRef.current) {
        const rect = getTrackRect();
        if (!rect) return;
        const dx = e.clientX - panStartRef.current.clientX;
        if (Math.abs(dx) > 3) panStartRef.current.moved = true;
        const deltaMs = Math.round((-dx / rect.width) * spanMs);
        setOffsetMs(panStartRef.current.startOffsetMs + deltaMs);
      } else if (pickingTime) {
        pickTimeFromClientX(e.clientX);
      }
    };
    const onUp = () => {
      setPickingTime(false);
      setPanning(false);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [pickingTime, panning, spanMs, w0]);

  // Горизонтальная прокрутка тачпадом или Shift + колесо мыши / зум Ctrl + колесо
  const handleWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      const idx = SCALES.findIndex((s) => s.h === hours);
      if (idx >= 0) {
        const nextIdx = e.deltaY > 0 ? Math.min(SCALES.length - 1, idx + 1) : Math.max(0, idx - 1);
        if (nextIdx !== idx) setHours(SCALES[nextIdx].h);
      }
      return;
    }
    const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.shiftKey ? e.deltaY : 0;
    if (delta !== 0) {
      e.preventDefault();
      const step = Math.round((delta / 400) * spanMs);
      setOffsetMs((prev) => prev + step);
    }
  };

  const shiftWindow = (fraction: number) => {
    setOffsetMs((prev) => prev + Math.round(spanMs * fraction));
  };

  const handleFindNextFree = () => {
    if (!data || !onSelectLocalAt) return;
    let t = ceil5(Date.now() + LEAD_MS);
    const limit = t + 7 * 86_400_000;
    for (; t < limit; t += STEP_MS) {
      const f = freeAt(t);
      if (f.ok) {
        onSelectLocalAt(toLocalDatetimeValue(t));
        if (t < w0 || f.end > w1) {
          setOffsetMs(Math.max(0, t - anchorNowMs - Math.round(spanMs * 0.15)));
        }
        setExtraWarn(null);
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
    const widthPct = Math.max(0.45, r - l);
    const key = b.runId ? `r${b.runId}` : `s${b.scheduleId}`;
    const isSel = shownKey === key;
    const vis = barVisualStyle(b);

    return (
      <div
        key={`${key}-${b.generatorId ?? 'pool'}`}
        onPointerDown={(e) => e.stopPropagation()}
        onMouseEnter={(e) => {
          const box = containerRef.current?.getBoundingClientRect();
          if (box) {
            setHoverInfo({ bar: b, x: e.clientX - box.left, y: e.clientY - box.top });
          }
        }}
        onMouseMove={(e) => {
          const box = containerRef.current?.getBoundingClientRect();
          if (box) {
            setHoverInfo({ bar: b, x: e.clientX - box.left, y: e.clientY - box.top });
          }
        }}
        onMouseLeave={() => setHoverInfo(null)}
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
          width: `${Math.min(100 - l, widthPct)}%`,
          background: vis.bg,
        }}
        className={`absolute top-[3px] bottom-[3px] z-10 rounded px-1.5 text-[11px] leading-[22px] font-medium text-white truncate cursor-pointer select-none transition-shadow hover:brightness-110 ${
          b.historical ? 'opacity-80' : 'shadow-2xs'
        } ${isSel ? 'ring-2 ring-slate-900 z-20 opacity-100' : ''}`}
      >
        {customText || b.label}
      </div>
    );
  };

  const durationSecVal = data?.durationSec || 0;
  const isPanned = Math.abs(offsetMs) >= 60_000;

  return (
    <div ref={containerRef} className="relative space-y-2">
      {/* Шапка таймлайна с навигацией по времени и масштабом */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <div className="text-sm font-semibold text-slate-900 flex items-center gap-1.5">
            <BarChart3 className="h-4 w-4 text-indigo-600" />
            <span>Занятость генераторов</span>
          </div>
          <span className="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[11px] font-medium text-slate-700">
            {dm(w0)} — {dm(w1)}
          </span>
          <span className="hidden xl:inline text-[11px] text-slate-400">
            {interactive ? 'клик — старт · Shift+тянуть — прокрутка' : 'тяните мышью или Shift+колесо для прокрутки'}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          {/* Кнопки прокрутки по времени */}
          <div className="inline-flex items-center rounded-lg border border-slate-300 bg-white overflow-hidden">
            <button
              type="button"
              title="Назад на целый экран"
              onClick={() => shiftWindow(-1)}
              className="px-1.5 py-1 text-slate-600 hover:bg-slate-50 border-r border-slate-200 cursor-pointer"
            >
              <ChevronsLeft className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              title="Назад на четверть окна"
              onClick={() => shiftWindow(-0.25)}
              className="px-1.5 py-1 text-slate-600 hover:bg-slate-50 border-r border-slate-200 cursor-pointer"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={() => setOffsetMs(0)}
              className={`inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium border-r border-slate-200 transition-colors cursor-pointer ${
                isPanned ? 'bg-indigo-50 text-indigo-700 font-semibold hover:bg-indigo-100' : 'text-slate-600 hover:bg-slate-50'
              }`}
            >
              <Crosshair className="h-3 w-3" />
              Сейчас
            </button>
            <button
              type="button"
              title="Вперёд на четверть окна"
              onClick={() => shiftWindow(0.25)}
              className="px-1.5 py-1 text-slate-600 hover:bg-slate-50 border-r border-slate-200 cursor-pointer"
            >
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              title="Вперёд на целый экран"
              onClick={() => shiftWindow(1)}
              className="px-1.5 py-1 text-slate-600 hover:bg-slate-50 cursor-pointer"
            >
              <ChevronsRight className="h-3.5 w-3.5" />
            </button>
          </div>

          {/* Переключатель масштаба */}
          <div className="inline-flex rounded-lg border border-slate-300 bg-white overflow-hidden">
            {SCALES.map((s) => (
              <button
                key={s.h}
                type="button"
                onClick={() => setHours(s.h)}
                className={`px-2 py-1 text-xs font-medium transition-colors border-r last:border-r-0 border-slate-200 cursor-pointer ${
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
      <div
        onWheel={handleWheel}
        className="rounded-xl border border-slate-200 bg-white p-1 select-none overflow-hidden"
      >
        {!data ? (
          <div className="p-3 text-xs text-slate-400">
            {isError ? 'Не удалось загрузить занятость генераторов.' : 'Загрузка…'}
          </div>
        ) : (
          <div ref={trackAreaRef} className="relative max-h-[520px] overflow-y-auto text-xs">
            {/* Верхняя ось времени (также можно тянуть мышью для прокрутки) */}
            <div
              onPointerDown={(e) => {
                if (e.button !== 0 && e.pointerType === 'mouse') return;
                panStartRef.current = { clientX: e.clientX, startOffsetMs: offsetMs, moved: false };
                setPanning(true);
              }}
              className={`sticky top-0 z-20 flex h-[28px] items-stretch bg-slate-50/95 border-b border-slate-200 ${
                panning ? 'cursor-grabbing' : 'cursor-grab'
              }`}
            >
              <div className="w-40 sm:w-48 shrink-0 px-2 pt-1.5 text-[11px] font-semibold text-slate-500 border-r border-slate-200/80">
                Генератор
              </div>
              <div className="relative flex-1 min-w-0 overflow-hidden">
                {ticks.map((t) => (
                  <div
                    key={t}
                    style={{ left: `${pct(t)}%` }}
                    className="absolute top-1.5 -translate-x-1/2 text-[11px] font-medium text-slate-600 whitespace-nowrap"
                  >
                    {hours >= 24 ? dm(t) : hm(t)}
                  </div>
                ))}
                {data.nowMs >= w0 && data.nowMs <= w1 && (
                  <div
                    style={{ left: `${pct(data.nowMs)}%` }}
                    className="absolute top-0.5 -translate-x-1/2 rounded bg-rose-600 px-1 py-0 text-[9px] font-bold text-white shadow-2xs pointer-events-none"
                  >
                    сейчас
                  </div>
                )}
              </div>
            </div>

            {/* Область строк + интерактивный оверлей прокрутки / выбора старта */}
            <div
              onPointerDown={(e) => {
                if (e.button !== 0 && e.pointerType === 'mouse') return;
                // В режиме просмотра (или при зажатом Shift) перетаскивание двигает шкалу
                if (!interactive || e.shiftKey) {
                  panStartRef.current = { clientX: e.clientX, startOffsetMs: offsetMs, moved: false };
                  setPanning(true);
                } else {
                  setPickingTime(true);
                  pickTimeFromClientX(e.clientX);
                }
              }}
              className={`relative ${
                interactive ? 'cursor-crosshair' : panning ? 'cursor-grabbing' : 'cursor-grab'
              } touch-none`}
            >
              {/* Запланированные «любые свободные» */}
              {poolRows.map((b) => (
                <div key={`pool-${b.scheduleId}`} className="flex h-[28px] items-stretch">
                  <div
                    className="w-40 sm:w-48 shrink-0 truncate px-2 pt-1.5 font-semibold text-amber-700 border-r border-slate-200/70 bg-amber-50/30"
                    title="Запись расписания займёт любые свободные генераторы на момент старта"
                  >
                    любые свободные ×{b.count}
                  </div>
                  <div className="relative flex-1 min-w-0 bg-slate-50/90 border-b border-slate-200/70 overflow-hidden">
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
                        className="absolute inset-y-0 w-[2px] bg-rose-600/85 z-10 pointer-events-none"
                      />
                    )}
                    {renderBar(b, `${b.label} · ×${b.count}`)}
                  </div>
                </div>
              ))}

              {/* Строки конкретных генераторов */}
              {data.generators.length === 0 ? (
                <div className="p-3 text-xs text-slate-400">Серверов нет.</div>
              ) : (
                data.generators.map((g) => (
                  <div key={g.id} className="flex h-[28px] items-stretch">
                    <div
                      className={`w-40 sm:w-48 shrink-0 truncate px-2 pt-1.5 font-medium text-slate-700 border-r border-slate-200/70 flex items-center gap-1.5 ${
                        g.online ? '' : 'opacity-60 bg-slate-50'
                      }`}
                      title={`${g.name}${g.online ? '' : ' (не в сети / обслуживание)'}`}
                    >
                      <span
                        className={`h-1.5 w-1.5 rounded-full shrink-0 ${
                          g.online ? 'bg-emerald-500' : 'bg-slate-300'
                        }`}
                      />
                      <span className="truncate">{g.name}</span>
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
                      className="relative flex-1 min-w-0 bg-slate-50/70 border-b border-slate-200/70 overflow-hidden"
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
                          className="absolute inset-y-0 w-[2px] bg-rose-600/85 z-10 pointer-events-none"
                        />
                      )}
                      {data.bars.filter((b) => b.generatorId === g.id).map((b) => renderBar(b))}
                    </div>
                  </div>
                ))
              )}

              {/* Оверлей выбранного интервала в режиме планирования */}
              {interactive && selectionState && selectionState.r > 0 && selectionState.l < 100 && (
                <div className="pointer-events-none absolute inset-y-0 left-40 sm:left-48 right-0 z-[15] overflow-hidden">
                  <div
                    style={{
                      left: `${Math.max(0, selectionState.l)}%`,
                      width: `${Math.min(100, selectionState.r) - Math.max(0, selectionState.l)}%`,
                    }}
                    className={`absolute inset-y-0 rounded border-2 ${
                      selectionState.ok
                        ? 'border-emerald-600 bg-emerald-500/20'
                        : 'border-rose-600 bg-rose-500/20'
                    }`}
                  />
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Всплывающий тултип при наведении на полосу */}
      {hoverInfo && (
        <div
          style={{
            left: Math.min(Math.max(12, hoverInfo.x + 12), (containerRef.current?.clientWidth || 600) - 290),
            top: Math.max(8, hoverInfo.y - 84),
          }}
          className="pointer-events-none absolute z-40 w-72 rounded-xl bg-slate-900/95 px-3 py-2 text-[11px] text-slate-100 shadow-xl ring-1 ring-white/10 space-y-1"
        >
          <div className="flex items-center justify-between gap-2">
            <span className="font-bold text-white truncate">{hoverInfo.bar.script}</span>
            <span className="rounded bg-white/15 px-1.5 py-0.5 text-[10px] font-medium shrink-0">
              {barVisualStyle(hoverInfo.bar).badgeLabel}
            </span>
          </div>
          {hoverInfo.bar.profile && <div className="text-indigo-200 truncate">Профиль: {hoverInfo.bar.profile}</div>}
          <div className="font-mono text-slate-300">
            {dm(hoverInfo.bar.from)} — {dm(hoverInfo.bar.to)} (~
            {Math.max(1, Math.round((hoverInfo.bar.to - hoverInfo.bar.from) / 60_000))} мин)
          </div>
          {hoverInfo.bar.genNames && <div className="text-slate-300 truncate">Серверы: {hoverInfo.bar.genNames}</div>}
          {hoverInfo.bar.by && <div className="text-slate-400">Назначил: {hoverInfo.bar.by}</div>}
        </div>
      )}

      {/* Подсказка проверки доступности генераторов */}
      {interactive && data && (
        <div className="text-xs">
          {selectionState ? (
            selectionState.ok ? (
              <span className="text-emerald-700 font-medium">
                ✓ {dm(selectionState.startMs)} — {hm(selectionState.end)} (~{Math.round(durationSecVal / 60)} мин):
                свободно {selectionState.avail} из {selectionState.online} генераторов в сети, нужно {selectionState.need}
                {selectionState.pool > 0 ? ` (уже запланировано на это время: ${selectionState.pool})` : ''}.
                {(selectionState.r < 0 || selectionState.l > 100) && ' (выбранное время вне текущего экрана)'}
              </span>
            ) : (
              <span className="text-rose-600 font-medium">
                ✗ {dm(selectionState.startMs)} — {hm(selectionState.end)} (~{Math.round(durationSecVal / 60)} мин):{' '}
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
              Тест встанет в начало очереди сразу после текущего запуска (~{Math.round(durationSecVal / 60)} мин).
            </span>
          ) : queuePosition === 'back' ? (
            <span className="text-indigo-700 font-medium">
              Тест встанет в конец очереди после всех ожидающих (~{Math.round(durationSecVal / 60)} мин).
            </span>
          ) : (
            <span className="text-slate-500">
              Время не выбрано — подберётся ближайшее свободное окно. Длительность: ~{Math.round(durationSecVal / 60)} мин.
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
          <i className="inline-block w-3.5 h-2.5 rounded-[2px] bg-emerald-600 opacity-80" />
          завершён (история)
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
      </div>

      {/* Карточка подробностей выбранной полосы */}
      {selectedBar && (
        <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3.5 py-2.5 text-xs">
          <div className="flex items-center justify-between gap-2 mb-2">
            <div className="flex items-center gap-2">
              <Badge
                variant={
                  selectedBar.kind === 'scheduled'
                    ? 'warning'
                    : selectedBar.historical
                    ? selectedBar.status === 'FAILED'
                      ? 'danger'
                      : 'success'
                    : 'info'
                }
              >
                {barVisualStyle(selectedBar).badgeLabel}
              </Badge>
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

            <dt className="text-slate-500 font-medium">Интервал</dt>
            <dd className="text-slate-800">
              {dm(selectedBar.from)} — {dm(selectedBar.to)} (~
              {Math.max(1, Math.round((selectedBar.to - selectedBar.from) / 60_000))} мин)
            </dd>

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

            {(selectedBar.runId || selectedBar.scheduleId) && (
              <>
                <dt className="text-slate-500 font-medium">Переход</dt>
                <dd>
                  {selectedBar.runId ? (
                    <Link to={`/runs/${selectedBar.runId}`} className="text-indigo-600 hover:underline font-semibold">
                      Открыть отчёт запуска #{selectedBar.runId} →
                    </Link>
                  ) : selectedBar.scheduleId ? (
                    onJumpToScheduleRow ? (
                      <button
                        type="button"
                        onClick={() => onJumpToScheduleRow(selectedBar.scheduleId!)}
                        className="text-indigo-600 hover:underline font-semibold cursor-pointer"
                      >
                        Перейти к строке #{selectedBar.scheduleId} в очереди ↓
                      </button>
                    ) : (
                      <Link to="/schedule" className="text-indigo-600 hover:underline font-semibold">
                        Запись расписания #{selectedBar.scheduleId}
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
