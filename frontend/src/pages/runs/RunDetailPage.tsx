import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CategoryScale,
  Chart as ChartJS,
  Filler,
  Legend,
  LinearScale,
  LineElement,
  PointElement,
  Title,
  Tooltip,
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import { toast } from 'sonner';
import {
  Activity,
  AlertTriangle,
  ArrowDown,
  ArrowLeft,
  Calendar,
  Camera,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronsDown,
  ChevronsUp,
  Clipboard,
  ClipboardList,
  Cpu,
  Download,
  Eraser,
  ExternalLink,
  FileCode,
  FileText,
  Gauge,
  OctagonX,
  Pause,
  Play,
  RefreshCw,
  RotateCw,
  Square,
  Terminal,
  TrendingUp,
  WrapText,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Select, Spinner, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { runStatusMeta } from '@/lib/utils';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend, Filler);

const PALETTE = ['#4f46e5', '#16a34a', '#dc2626', '#ea580c', '#9333ea', '#0891b2', '#64748b', '#db2777'];

const PHASES_META: Record<string, { fill: string; text: string; title: string }> = {
  init: { fill: 'rgba(100, 116, 139, .14)', text: '#475569', title: 'Инициализация' },
  warmup: { fill: 'rgba(245, 158, 11, .14)', text: '#b45309', title: 'Прогрев' },
};

function makePhasePlugin(phases: string[]) {
  return {
    id: 'k6Phases',
    beforeDatasetsDraw(chart: any) {
      const xs = chart.scales?.x;
      const area = chart.chartArea;
      if (!xs || !area || !phases.length) return;
      const ctx = chart.ctx;
      let i = 0;
      while (i < phases.length) {
        const pKey = phases[i];
        if (!pKey || !PHASES_META[pKey]) {
          i++;
          continue;
        }
        let j = i;
        while (j + 1 < phases.length && phases[j + 1] === pKey) j++;
        const x0 = xs.getPixelForValue(i);
        const x1 = xs.getPixelForValue(Math.min(j + 1, Math.max(0, phases.length - 1)));
        ctx.save();
        ctx.fillStyle = PHASES_META[pKey].fill;
        ctx.fillRect(x0, area.top, Math.max(2, x1 - x0), area.bottom - area.top);
        ctx.fillStyle = PHASES_META[pKey].text;
        ctx.font = '600 11px sans-serif';
        if (x1 - x0 >= ctx.measureText(PHASES_META[pKey].title).width + 12) {
          ctx.fillText(PHASES_META[pKey].title, x0 + 6, area.top + 14);
        }
        ctx.restore();
        i = j + 1;
      }
    },
  };
}

function fmtClock(sec?: number | null): string {
  const s = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r < 10 ? '0' : ''}${r}`;
}

function fmtSpan(sec?: number | null): string {
  const s = Math.max(0, Math.round(sec || 0));
  if (s < 120) return `${s} с`;
  if (s < 7200) return `${Math.round(s / 60)} мин`;
  return `${(s / 3600).toFixed(1)} ч`;
}

function fmtNum(v: number | null | undefined, decimals = 1): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return Number(v).toFixed(decimals);
}

function fmtTimeOnly(iso?: string | number | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function stampMs(ms?: number | null): string {
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n: number) => (n < 10 ? `0${n}` : `${n}`);
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${d.toTimeString().slice(0, 8)}`;
}

function genRunStatusLabel(st?: string) {
  switch (st) {
    case 'PREPARING':
      return { label: 'подготовка', variant: 'default' as const };
    case 'READY':
      return { label: 'готов', variant: 'info' as const };
    case 'RUNNING':
      return { label: 'выполняется', variant: 'info' as const };
    case 'STOPPING':
      return { label: 'останавливается', variant: 'warning' as const };
    case 'EXITED':
      return { label: 'завершён', variant: 'success' as const };
    case 'FAILED':
    case 'LOST':
      return { label: st === 'LOST' ? 'потерян' : 'сбой', variant: 'danger' as const };
    default:
      return { label: st || '—', variant: 'default' as const };
  }
}

function stepStatusMeta(st?: string) {
  switch (st) {
    case 'PASSED':
      return { label: 'выдержана', cls: 'bg-emerald-50 text-emerald-700 ring-emerald-200' };
    case 'FAILED':
      return { label: 'не выдержана', cls: 'bg-rose-50 text-rose-700 ring-rose-200' };
    case 'UNRELIABLE':
      return { label: 'недостоверно', cls: 'bg-amber-50 text-amber-800 ring-amber-200' };
    case 'NOT_RUN':
      return { label: 'не проводилась', cls: 'bg-slate-100 text-slate-600 ring-slate-200' };
    default:
      return { label: st || '—', cls: 'bg-slate-100 text-slate-700 ring-slate-200' };
  }
}

interface GrafanaChartSeries {
  group: string;
  label: string;
  color: string;
  dashed?: boolean;
  inLegend?: boolean;
  data: (number | null)[];
}

function GrafanaMetricCard({
  title,
  unit,
  decimals = 1,
  labels,
  phases,
  series,
  firstColTitle = 'Операция',
  selectable = true,
  selectedOps,
  onToggleOp,
  onResetOps,
}: {
  title: string;
  unit: string;
  decimals?: number;
  labels: string[];
  phases: string[];
  series: GrafanaChartSeries[];
  firstColTitle?: string;
  selectable?: boolean;
  selectedOps: Set<string> | null;
  onToggleOp?: (op: string, multi: boolean) => void;
  onResetOps?: () => void;
}) {
  const plugin = useMemo(() => makePhasePlugin(phases), [phases]);

  const calcStats = (arr: (number | null)[]) => {
    let n = 0;
    let sum = 0;
    let max: number | null = null;
    let last: number | null = null;
    for (const v of arr) {
      if (v === null || v === undefined || !Number.isFinite(v)) continue;
      n++;
      sum += v;
      last = v;
      if (max === null || v > max) max = v;
    }
    return { last, mean: n > 0 ? sum / n : null, max };
  };

  const datasets = series.map((s) => ({
    label: s.label,
    data: s.data,
    borderColor: s.color,
    backgroundColor: s.color,
    borderDash: s.dashed ? [6, 4] : [],
    borderWidth: s.dashed ? 1.5 : 2,
    hidden: selectable && !!selectedOps && !selectedOps.has(s.group),
  }));

  const legendSeries = series.filter((s) => (s.inLegend === undefined ? !s.dashed : s.inLegend));

  return (
    <Card className="overflow-hidden">
      <CardHeader className="py-2.5">
        <span className="text-xs font-semibold text-slate-800">{title}</span>
        {selectable && selectedOps && onResetOps && (
          <Button variant="outline" size="xs" onClick={onResetOps}>
            Показать все
          </Button>
        )}
      </CardHeader>
      <div className="grid grid-cols-1 lg:grid-cols-12">
        <div className="lg:col-span-8 h-64 p-3">
          <Line
            key={`${title}-${phases.length}`}
            plugins={[plugin]}
            data={{ labels, datasets }}
            options={{
              animation: false,
              responsive: true,
              maintainAspectRatio: false,
              spanGaps: true,
              interaction: { mode: 'index', intersect: false },
              plugins: { legend: { display: false } },
              scales: {
                y: { beginAtZero: true, title: { display: !!unit, text: unit } },
                x: { ticks: { maxTicksLimit: 10, font: { size: 10 } } },
              },
              elements: { point: { radius: 0, hoverRadius: 3 } },
            }}
          />
        </div>
        <div className="lg:col-span-4 border-t lg:border-t-0 lg:border-l border-slate-100 p-2.5 flex flex-col justify-between max-h-64 overflow-y-auto">
          <table className="w-full text-xs">
            <thead className="text-[11px] text-slate-400 border-b border-slate-100">
              <tr>
                <th className="w-4 py-1" />
                <th className="text-left py-1 font-medium">{firstColTitle}</th>
                <th className="text-right py-1 font-medium">Last</th>
                <th className="text-right py-1 font-medium">Mean</th>
                <th className="text-right py-1 font-medium">Max</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {legendSeries.map((s) => {
                const st = calcStats(s.data);
                const off = selectable && !!selectedOps && !selectedOps.has(s.group);
                return (
                  <tr
                    key={s.label}
                    onClick={(e) => selectable && onToggleOp?.(s.group, e.ctrlKey || e.metaKey || e.shiftKey)}
                    className={`${selectable ? 'cursor-pointer hover:bg-slate-50' : ''} ${
                      off ? 'opacity-40' : selectedOps ? 'font-semibold bg-indigo-50/40' : ''
                    }`}
                  >
                    <td className="py-1 pr-1.5">
                      <span className="inline-block h-2.5 w-2.5 rounded-xs" style={{ background: s.color }} />
                    </td>
                    <td className="py-1 pr-2 truncate max-w-[140px] text-slate-800" title={s.label}>
                      {s.group}
                    </td>
                    <td className="py-1 px-1 text-right font-mono text-slate-700">{fmtNum(st.last, decimals)}</td>
                    <td className="py-1 px-1 text-right font-mono text-slate-700">{fmtNum(st.mean, decimals)}</td>
                    <td className="py-1 pl-1 text-right font-mono text-slate-700">{fmtNum(st.max, decimals)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {selectable && (
            <div className="text-[10px] text-slate-400 pt-2">
              Клик — только эта операция. Ctrl/Cmd+клик — добавить к выбору.
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}

type RunTab = 'metrics' | 'system' | 'results' | 'logs' | 'snapshot';

function parseTabHash(hash: string): RunTab {
  const h = hash.replace(/^#/, '') as RunTab;
  return ['metrics', 'system', 'results', 'logs', 'snapshot'].includes(h) ? h : 'metrics';
}

export function RunDetailPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [tab, setTab] = useState<RunTab>(() => parseTabHash(location.hash));
  const [requeueMenuOpen, setRequeueMenuOpen] = useState(false);
  const requeueRef = useRef<HTMLDivElement>(null);

  // Metrics state
  const [metricRange, setMetricRange] = useState<'window' | 'all'>(() => {
    try {
      return localStorage.getItem('k6lab.metricRange') === 'all' ? 'all' : 'window';
    } catch {
      return 'window';
    }
  });
  const [selectedOps, setSelectedOps] = useState<Set<string> | null>(null);

  // Results state (manual refresh only — zero jitter!)
  const [repTimeAxis, setRepTimeAxis] = useState<'' | 'rel' | 'abs'>('');
  const [conclusion, setConclusion] = useState('');
  const [comment, setComment] = useState('');
  const [refreshingResults, setRefreshingResults] = useState(false);

  // Logs state
  const [logLevel, setLogLevel] = useState<'' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'>('');
  const [logGenFilter, setLogGenFilter] = useState<string>('');
  const [logSearch, setLogSearch] = useState('');
  const [logPaused, setLogPaused] = useState(false);
  const [logWrap, setLogWrap] = useState(true);
  const [clearedBeforeIdx, setClearedBeforeIdx] = useState(0);
  const logBoxRef = useRef<HTMLPreElement>(null);

  useEffect(() => {
    setTab(parseTabHash(location.hash));
  }, [location.hash]);

  useEffect(() => {
    if (!requeueMenuOpen) return;
    const onDoc = (e: MouseEvent) => {
      if (requeueRef.current && !requeueRef.current.contains(e.target as Node)) setRequeueMenuOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [requeueMenuOpen]);

  const switchTab = (next: RunTab) => {
    setTab(next);
    const suffix = next === 'metrics' ? '' : `#${next}`;
    window.history.replaceState(null, '', `${location.pathname}${location.search}${suffix}`);
  };

  const { data: detail, isLoading } = useQuery({
    queryKey: ['run', id],
    queryFn: () => apiFetch(`/api/v1/runs/${id}`),
    refetchInterval: (q) => {
      const st = q.state.data?.run?.status;
      return st && ['QUEUED', 'PREPARING', 'RUNNING', 'STOPPING'].includes(st) ? 3000 : false;
    },
  });

  // Вкладка «Результаты» загружается один раз без автообновления (обновление только вручную по кнопке)
  const { data: resData } = useQuery({
    queryKey: ['run-results', id],
    queryFn: () => apiFetch(`/api/v1/runs/${id}/results`),
    enabled: !!id,
  });

  useEffect(() => {
    if (resData) {
      setConclusion(resData.conclusion || '');
      setComment(resData.conclusionComment || '');
    }
  }, [resData]);

  // История и живые точки метрик Prometheus
  const { data: metricsPoints = [] } = useQuery<any[]>({
    queryKey: ['run-metrics-history', id],
    queryFn: async () => {
      const res = await fetch(`/runs/${id}/metrics/history`, { credentials: 'same-origin' });
      if (!res.ok) return [];
      const arr = await res.json();
      return (arr || []).map((item: any) => (typeof item === 'string' ? JSON.parse(item) : item));
    },
    enabled: !!id && (tab === 'metrics' || tab === 'system'),
    refetchInterval: detail?.run?.live ? 5000 : false,
  });

  // Логи запуска (подгружаем также фоново при живом тесте для бейджа ошибок)
  const { data: logsData } = useQuery({
    queryKey: ['run-logs', id, logGenFilter],
    queryFn: () =>
      apiFetch(
        `/api/v1/runs/${id}/logs?limit=800${logGenFilter ? `&generatorId=${encodeURIComponent(logGenFilter)}` : ''}`
      ),
    enabled: !!id,
    refetchInterval: detail?.run?.live && !logPaused ? 3000 : false,
  });

  useEffect(() => {
    if (tab === 'logs' && !logPaused && logBoxRef.current) {
      logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
    }
  }, [logsData?.lines?.length, tab, logPaused]);

  const stopMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/runs/${id}/stop`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Команда мягкой остановки отправлена');
      qc.invalidateQueries({ queryKey: ['run', id] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const abortMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/runs/${id}/abort`, { method: 'POST' }),
    onSuccess: () => {
      toast.warning('Принудительное прерывание отправлено');
      qc.invalidateQueries({ queryKey: ['run', id] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const requeueMut = useMutation({
    mutationFn: (position: 'front' | 'back') =>
      apiFetch(`/api/v1/runs/${id}/requeue`, { method: 'POST', body: { position } }),
    onSuccess: (res: any) => {
      toast.success(res?.message || 'Прогон снова добавлен в очередь', {
        action: { label: 'Расписание', onClick: () => navigate('/schedule') },
      });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const saveConclusionMut = useMutation({
    mutationFn: () =>
      apiFetch(`/api/v1/runs/${id}/conclusion`, {
        method: 'POST',
        body: { conclusion, comment },
      }),
    onSuccess: () => toast.success('Вывод инженера сохранён'),
    onError: (err: any) => toast.error(err.message),
  });

  const handleManualRefreshResults = async () => {
    setRefreshingResults(true);
    try {
      const fresh = await apiFetch(`/api/v1/runs/${id}/results?refresh=true`);
      qc.setQueryData(['run-results', id], fresh);
      toast.success('Итоги пересчитаны');
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setRefreshingResults(false);
    }
  };

  const handleToggleOp = (op: string, multi: boolean) => {
    setSelectedOps((prev) => {
      if (multi) {
        if (!prev) return new Set([op]);
        const next = new Set(prev);
        if (next.has(op)) {
          next.delete(op);
          return next.size > 0 ? next : null;
        }
        next.add(op);
        return next;
      }
      if (prev && prev.size === 1 && prev.has(op)) return null;
      return new Set([op]);
    });
  };

  // Построение временных рядов из истории метрик точно как в run-metrics.js
  const parsedMetrics = useMemo(() => {
    const labels: string[] = [];
    const phases: string[] = [];
    const vusActual: (number | null)[] = [];
    const vusMax: (number | null)[] = [];
    const opOrder: string[] = [];
    const opSet = new Set<string>();

    const deduped: any[] = [];
    let lastTs = 0;
    for (const m of metricsPoints) {
      if (!m) continue;
      if (m.ts && m.ts <= lastTs) continue;
      if (m.ts) lastTs = m.ts;
      deduped.push(m);
      if (Array.isArray(m.rows)) {
        for (const r of m.rows) {
          if (r?.op && !opSet.has(r.op)) {
            opSet.add(r.op);
            opOrder.push(r.op);
          }
        }
      }
    }

    const rateActual = new Map<string, (number | null)[]>();
    const rateTarget = new Map<string, (number | null)[]>();
    const pctVal = new Map<string, (number | null)[]>();
    const pctSla = new Map<string, (number | null)[]>();
    const pctNByOp = new Map<string, number>();
    const iterVal = new Map<string, (number | null)[]>();
    const errVal = new Map<string, (number | null)[]>();
    const errMax = new Map<string, (number | null)[]>();
    const dropRate = new Map<string, (number | null)[]>();

    for (const op of opOrder) {
      rateActual.set(op, []);
      rateTarget.set(op, []);
      pctVal.set(op, []);
      pctSla.set(op, []);
      iterVal.set(op, []);
      errVal.set(op, []);
      errMax.set(op, []);
      dropRate.set(op, []);
    }

    deduped.forEach((m, idx) => {
      if (m.phase === 'init') {
        labels.push(stampMs(m.ts));
        phases.push('init');
        vusActual.push(null);
        vusMax.push(null);
        for (const op of opOrder) {
          rateActual.get(op)!.push(null);
          rateTarget.get(op)!.push(null);
          pctVal.get(op)!.push(null);
          pctSla.get(op)!.push(null);
          iterVal.get(op)!.push(null);
          errVal.get(op)!.push(null);
          errMax.get(op)!.push(null);
          dropRate.get(op)!.push(null);
        }
        return;
      }

      labels.push(stampMs(m.dataTs || m.ts));
      phases.push(m.phase === 'warmup' ? 'warmup' : '');
      vusActual.push(m.vus ?? null);
      vusMax.push(m.vusMax ?? null);

      const byOp = new Map<string, any>();
      (m.rows || []).forEach((r: any) => {
        if (r?.op) {
          byOp.set(r.op, r);
          if (r.pctN) pctNByOp.set(r.op, r.pctN);
        }
      });

      for (const op of opOrder) {
        const r = byOp.get(op);
        rateActual.get(op)!.push(r?.actual ?? null);
        rateTarget.get(op)!.push(r?.target ?? null);
        pctVal.get(op)!.push(r?.pct ?? null);
        pctSla.get(op)!.push(r?.sla ?? null);
        iterVal.get(op)!.push(r?.iterPct ?? null);
        errVal.get(op)!.push(r?.err ?? null);
        errMax.get(op)!.push(r?.maxErr ?? null);
        dropRate
          .get(op)!
          .push(r?.dropped != null ? r.dropped / Math.max(1, m.windowSec || 1) : null);
      }
      void idx;
    });

    const lastPoint = deduped.length > 0 ? deduped[deduped.length - 1] : null;

    const rateSeries: GrafanaChartSeries[] = [];
    const pctSeries: GrafanaChartSeries[] = [];
    const iterSeries: GrafanaChartSeries[] = [];
    const errSeries: GrafanaChartSeries[] = [];
    const dropSeries: GrafanaChartSeries[] = [];

    opOrder.forEach((op, i) => {
      const col = PALETTE[i % PALETTE.length];
      const pN = pctNByOp.get(op) || 95;
      rateSeries.push({ group: op, label: `${op} факт`, color: col, data: rateActual.get(op)! });
      rateSeries.push({ group: op, label: `${op} план`, color: col, dashed: true, data: rateTarget.get(op)! });

      pctSeries.push({ group: op, label: `${op} p${pN}`, color: col, data: pctVal.get(op)! });
      pctSeries.push({ group: op, label: `${op} SLA`, color: col, dashed: true, data: pctSla.get(op)! });

      iterSeries.push({ group: op, label: `${op} p${pN}`, color: col, data: iterVal.get(op)! });

      errSeries.push({ group: op, label: op, color: col, data: errVal.get(op)! });
      errSeries.push({ group: op, label: `${op} порог`, color: col, dashed: true, data: errMax.get(op)! });

      dropSeries.push({ group: op, label: op, color: col, data: dropRate.get(op)! });
    });

    const vusSeries: GrafanaChartSeries[] = [
      { group: 'Задействовано', label: 'Задействовано', color: '#4f46e5', inLegend: true, data: vusActual },
      { group: 'Максимум', label: 'Максимум', color: '#16a34a', dashed: true, inLegend: true, data: vusMax },
    ];

    return {
      hasData: deduped.some((m) => m.phase !== 'init'),
      lastPoint,
      labels,
      phases,
      rateSeries,
      pctSeries,
      iterSeries,
      errSeries,
      vusSeries,
      dropSeries,
    };
  }, [metricsPoints]);

  const allLogLines: any[] = (logsData?.lines || []).slice(clearedBeforeIdx);
  const logCounts = useMemo(() => {
    const c = { all: allLogLines.length, ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0 };
    for (const l of allLogLines) {
      const lv = String(l.level || 'INFO').toUpperCase();
      if (lv === 'ERROR') c.ERROR++;
      else if (lv === 'WARN') c.WARN++;
      else if (lv === 'DEBUG') c.DEBUG++;
      else c.INFO++;
    }
    return c;
  }, [allLogLines]);

  const filteredLogs = useMemo(() => {
    const q = logSearch.trim().toLowerCase();
    return allLogLines.filter((l) => {
      if (logLevel && String(l.level || 'INFO').toUpperCase() !== logLevel) return false;
      if (q && !String(l.line || '').toLowerCase().includes(q)) return false;
      return true;
    });
  }, [allLogLines, logLevel, logSearch]);

  if (isLoading || !detail) return <Spinner label="Загрузка запуска…" />;

  const {
    run,
    snapshot,
    generators = [],
    failurePolicyTitle,
    grafanaUrl,
    envRows = [],
    customChecksConfig = [],
    k6Config,
  } = detail;

  const st = runStatusMeta(run.status, run.thresholdsFailed);
  const isTerminal = !run.live && !['QUEUED', 'PREPARING', 'RUNNING', 'STOPPING'].includes(run.status);

  const runningGensCount = generators.filter((g: any) => g.status === 'RUNNING').length;
  const exitedGensCount = generators.filter((g: any) => g.status === 'EXITED').length;
  const offlineGensCount = generators.filter((g: any) => g.offline).length;
  const hotGensCount = generators.filter((g: any) => g.cpuHot).length;
  const failedGensCount = generators.filter((g: any) => g.status === 'FAILED' || g.status === 'LOST').length;

  const timeQuery = repTimeAxis ? `time=${repTimeAxis}` : '';
  const buildReportHref = (base: string, extra?: string) => {
    const parts = [extra, timeQuery].filter(Boolean);
    return parts.length > 0 ? `${base}?${parts.join('&')}` : base;
  };

  // Текст фазы на вкладке «Метрики»
  const lastM = parsedMetrics.lastPoint;
  const useAllRange = metricRange === 'all' && lastM?.rowsAll;
  const currentTableRows: any[] = lastM ? (useAllRange ? lastM.rowsAll : lastM.rows || []) : [];
  const currentCustomChecks: any[] = Array.isArray(lastM?.customChecks) ? lastM.customChecks : [];

  let metricPhaseText = 'Ожидание данных из Prometheus…';
  if (lastM) {
    if (!isTerminal && lastM.phase === 'init') {
      metricPhaseText = `Инициализация скрипта k6: ${lastM.initSec || 0} с`;
    } else {
      const stampStr = stampMs(lastM.dataTs || lastM.ts);
      const lag =
        isTerminal || lastM.lagSec === undefined ? '' : `. Данные на ${stampStr}, задержка метрик ${lastM.lagSec} с`;
      if (isTerminal) {
        metricPhaseText =
          'Тест завершён, ' +
          (useAllRange
            ? `показатели за весь тест (${fmtSpan(lastM.wholeSec)})`
            : `окно расчёта ${lastM.windowSec || 60} с${metricRange === 'all' ? ' (за весь тест данных пока нет)' : ''}`);
      } else {
        metricPhaseText =
          (lastM.phase === 'warmup'
            ? 'Идёт прогрев: ошибки и время ответа пока не оцениваются'
            : lastM.phase
              ? 'Идёт нагрузка, ' +
                (useAllRange
                  ? `показатели за весь тест (${fmtSpan(lastM.wholeSec)})`
                  : `окно расчёта ${lastM.windowSec || 60} с`)
              : 'Ожидание данных') + lag;
      }
    }
  }

  return (
    <div className="space-y-3.5">
      {/* Хлебные крошки как в runs/view.html */}
      <nav className="flex items-center gap-1.5 text-xs text-slate-500">
        <Link to="/scripts" className="hover:text-indigo-600">
          Скрипты
        </Link>
        <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
        {run.scriptId ? (
          <Link to={`/scripts/${run.scriptId}`} className="hover:text-indigo-600">
            {run.scriptName}
          </Link>
        ) : (
          <span>{run.scriptName}</span>
        )}
        <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
        <span className="text-slate-700 font-medium">Запуск #{run.id}</span>
      </nav>

      {/* Заголовок и кнопки очереди / возврата к скрипту */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600 ring-1 ring-inset ring-indigo-100">
            <Activity className="h-4.5 w-4.5" />
          </span>
          <h1 className="text-xl font-bold text-slate-900">Запуск #{run.id}</h1>
        </div>

        {run.scriptId != null && (
          <div className="flex flex-wrap items-center gap-2">
            <div ref={requeueRef} className="relative inline-flex">
              <button
                type="button"
                disabled={requeueMut.isPending}
                onClick={() => requeueMut.mutate('back')}
                title="Повторно добавить этот прогон в конец очереди расписания"
                className="inline-flex items-center gap-1.5 h-8 px-3 rounded-l-lg border border-indigo-300 bg-white text-indigo-700 hover:bg-indigo-50 text-xs font-medium cursor-pointer disabled:opacity-50"
              >
                <RotateCw className="h-3.5 w-3.5" />
                Снова в очередь
              </button>
              <button
                type="button"
                disabled={requeueMut.isPending}
                onClick={() => setRequeueMenuOpen((v) => !v)}
                aria-label="Позиция в очереди"
                className="inline-flex items-center justify-center h-8 px-2 rounded-r-lg border border-l-0 border-indigo-300 bg-white text-indigo-700 hover:bg-indigo-50 text-xs cursor-pointer disabled:opacity-50"
              >
                <ChevronDown className="h-3.5 w-3.5" />
              </button>
              {requeueMenuOpen && (
                <div className="absolute right-0 top-full mt-1 z-30 w-64 rounded-lg border border-slate-200 bg-white py-1 shadow-lg">
                  <button
                    type="button"
                    onClick={() => {
                      setRequeueMenuOpen(false);
                      requeueMut.mutate('front');
                    }}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 cursor-pointer"
                  >
                    <ChevronsUp className="h-3.5 w-3.5 text-indigo-600" />
                    В начало очереди (после текущего)
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setRequeueMenuOpen(false);
                      requeueMut.mutate('back');
                    }}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 cursor-pointer"
                  >
                    <ChevronsDown className="h-3.5 w-3.5 text-indigo-600" />
                    В конец очереди
                  </button>
                </div>
              )}
            </div>

            <Link to={`/scripts/${run.scriptId}`}>
              <Button variant="outline" size="sm">
                <ArrowLeft className="h-3.5 w-3.5" />
                К скрипту
              </Button>
            </Link>
          </div>
        )}
      </div>

      {/* Строка метаданных запуска */}
      <div className="text-xs text-slate-500 flex flex-wrap items-center gap-1.5">
        <strong className="text-slate-800">{snapshot?.script?.name || run.scriptName}</strong>
        {snapshot?.script?.versionNumber != null && <span>v{snapshot.script.versionNumber}</span>}
        {snapshot?.script?.commit && <span className="font-mono">({String(snapshot.script.commit).slice(0, 8)})</span>}
        {failurePolicyTitle && (
          <>
            <span>·</span>
            <span>при потере генератора: {failurePolicyTitle}</span>
          </>
        )}
        {run.loadProfileId != null && (
          <>
            <span>· профиль нагрузки:</span>
            <Badge variant="info">{run.loadProfileName || `#${run.loadProfileId}`}</Badge>
          </>
        )}
        {run.scheduleId != null && (
          <>
            <span>·</span>
            <span className="inline-flex items-center gap-1">
              <Calendar className="h-3 w-3" />
              по расписанию
            </span>
          </>
        )}
        {Array.isArray(run.k6Flags) && run.k6Flags.length > 0 && (
          <>
            <span>· флаги k6:</span>
            {run.k6Flags.map((fl: string) => (
              <Badge key={fl} variant="warning">
                {fl}
              </Badge>
            ))}
          </>
        )}
      </div>

      {/* Блок статуса запуска + прогресс + раскрываемый блок Генераторов (как в runs/_status.html) */}
      <div className="space-y-2.5">
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3">
          <div className="flex flex-wrap items-center gap-2">
            {run.live && <span className="h-2.5 w-2.5 rounded-full bg-indigo-600 animate-pulse" />}
            <Badge variant={st.variant} className="text-sm px-2.5 py-0.5">
              {st.label}
            </Badge>
            {run.degraded && (
              <Badge variant="warning" className="text-sm px-2.5 py-0.5">
                Были потери связи
              </Badge>
            )}
            {run.thresholdsFailed && (
              <Badge variant="warning" className="text-sm px-2.5 py-0.5">
                Пороги k6 не выполнены
              </Badge>
            )}
            {run.live && (
              <span className="text-xs text-slate-500 ml-1">
                {run.initializing
                  ? 'Инициализация скрипта'
                  : run.currentStep != null
                    ? run.currentPhase
                    : 'Подготовка к старту'}
              </span>
            )}
          </div>

          {(run.live || run.status === 'FINISHED') && !run.initializing && (
            <div className="text-sm font-mono">
              <span className="font-semibold text-slate-900">{fmtClock(run.elapsedSec)}</span>
              {run.live && run.totalSec > 0 && (
                <span className="text-slate-500">
                  {' '}
                  из {fmtClock(run.totalSec)} ({run.progressPct || 0}%)
                </span>
              )}
            </div>
          )}

          {run.canStop && (
            <div className="ml-auto flex flex-wrap items-center gap-2">
              <Button
                variant="warning"
                size="sm"
                disabled={run.status === 'STOPPING'}
                loading={stopMut.isPending}
                onClick={() => stopMut.mutate()}
                title="Мягкая остановка: текущие итерации дорабатывают"
              >
                <Square className="h-3.5 w-3.5 fill-current" />
                Остановить
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="border-rose-300 text-rose-700 hover:bg-rose-50"
                loading={abortMut.isPending}
                onClick={async () => {
                  const ok = await confirm({
                    title: 'Принудительно прервать запуск?',
                    message:
                      'Принудительное прерывание убьёт k6 на всех генераторах, метрики последних секунд могут потеряться. Продолжить?',
                    confirmText: 'Прервать',
                    variant: 'danger',
                  });
                  if (ok) abortMut.mutate();
                }}
              >
                <OctagonX className="h-3.5 w-3.5" />
                Прервать принудительно
              </Button>
            </div>
          )}
        </div>

        {(run.status === 'RUNNING' || run.status === 'STOPPING' || run.status === 'FINISHED') && (
          <div className="h-2 rounded-full bg-slate-200 overflow-hidden">
            <div
              className={`h-full rounded-full bg-indigo-600 transition-all ${
                run.status === 'RUNNING' || run.status === 'STOPPING' ? 'animate-pulse' : ''
              }`}
              style={{ width: `${Math.min(100, Math.max(0, run.progressPct || 0))}%` }}
            />
          </div>
        )}

        {!run.initializing && run.initSec != null && run.initSec >= 15 && (
          <div className="text-xs text-slate-500">
            Инициализация скрипта заняла {run.initSec} с: шкала запуска, прогрев и окна итогов отсчитываются от реального начала нагрузки.
          </div>
        )}

        {run.error && (
          <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-sm text-rose-900">
            {run.error}
          </div>
        )}

        {run.stopReason && (
          <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-2 text-xs text-amber-950">
            <strong>Причина остановки:</strong> {run.stopReason}
          </div>
        )}

        {/* Раскрываемый блок Генераторов над вкладками */}
        <details className="group rounded-xl border border-slate-200 bg-white overflow-hidden">
          <summary className="flex flex-wrap items-center gap-2 px-4 py-2.5 bg-slate-50/70 cursor-pointer select-none text-xs">
            <span className="font-semibold text-slate-900">Генераторы</span>
            <span className="text-slate-500">{generators.length} шт.</span>
            {runningGensCount > 0 && <Badge variant="info">выполняются: {runningGensCount}</Badge>}
            {exitedGensCount > 0 && <Badge variant="success">завершены: {exitedGensCount}</Badge>}
            {offlineGensCount > 0 && <Badge variant="warning">нет связи: {offlineGensCount}</Badge>}
            {hotGensCount > 0 && <Badge variant="danger">CPU выше 80%: {hotGensCount}</Badge>}
            {failedGensCount > 0 && <Badge variant="danger">сбои: {failedGensCount}</Badge>}
            <span className="ml-auto text-slate-500 group-open:hidden">Показать</span>
            <span className="ml-auto text-slate-500 hidden group-open:inline">Скрыть</span>
          </summary>
          <div className="overflow-x-auto border-t border-slate-200">
            <table className="w-full text-xs">
              <thead className="bg-slate-50 text-slate-500 border-b border-slate-100">
                <tr>
                  <th className="px-3.5 py-2 text-left font-medium">Генератор</th>
                  <th className="px-3 py-2 text-left font-medium">Состояние</th>
                  <th className="px-3 py-2 text-left font-medium">CPU</th>
                  <th className="px-3 py-2 text-left font-medium">RAM</th>
                  <th className="px-3 py-2 text-right font-medium">VU</th>
                  <th className="px-3 py-2 text-right font-medium">Итерации</th>
                  <th className="px-3 py-2 text-right font-medium">Пропущено</th>
                  <th className="px-3 py-2 text-left font-medium">Доля</th>
                  <th className="px-3.5 py-2 text-left font-medium">Код выхода</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {generators.map((g: any) => {
                  const gst = genRunStatusLabel(g.status);
                  return (
                    <tr key={g.id} className={g.offline ? 'bg-amber-50/70' : 'hover:bg-slate-50/70'}>
                      <td className="px-3.5 py-2">
                        {g.generatorId != null ? (
                          <Link
                            to={`/generators/${g.generatorId}`}
                            className="font-semibold text-indigo-600 hover:underline"
                          >
                            {g.generatorName}
                          </Link>
                        ) : (
                          <span className="font-semibold text-slate-800">{g.generatorName}</span>
                        )}
                        {g.offline && (
                          <Badge variant="warning" className="ml-1.5">
                            нет связи
                          </Badge>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <Badge variant={gst.variant}>{gst.label}</Badge>
                        {g.k6Status && <span className="text-slate-500 ml-1.5">{g.k6Status}</span>}
                      </td>
                      <td className="px-3 py-2">
                        {g.cpu != null ? (
                          <div className="inline-flex items-center gap-1.5">
                            <span className="inline-block w-12 h-1.5 rounded-full bg-slate-200 overflow-hidden">
                              <i
                                className={`block h-full ${g.cpuHot ? 'bg-rose-500' : 'bg-indigo-600'}`}
                                style={{ width: `${Math.min(100, Math.round(g.cpu))}%` }}
                              />
                            </span>
                            <strong className={g.cpuHot ? 'text-rose-600' : 'text-slate-800'}>
                              {Math.round(g.cpu)}%
                            </strong>
                          </div>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {g.memPct != null ? (
                          <div className="inline-flex items-center gap-1.5">
                            <span className="inline-block w-12 h-1.5 rounded-full bg-slate-200 overflow-hidden">
                              <i
                                className="block h-full bg-indigo-500"
                                style={{ width: `${Math.min(100, Math.round(g.memPct))}%` }}
                              />
                            </span>
                            <strong className="text-slate-800">{Math.round(g.memPct)}%</strong>
                          </div>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right font-mono">
                        {g.vus != null ? (g.vusMax != null ? `${g.vus} / ${g.vusMax}` : g.vus) : '—'}
                      </td>
                      <td className="px-3 py-2 text-right font-mono">{g.iterations ?? '—'}</td>
                      <td className="px-3 py-2 text-right font-mono">{g.dropped ?? '—'}</td>
                      <td className="px-3 py-2 whitespace-nowrap text-slate-600">
                        <code className="bg-slate-100 px-1 rounded">{g.segment}</code>{' '}
                        {g.weight != null && <span>вес {g.weight}</span>}
                      </td>
                      <td className="px-3.5 py-2">
                        <span>{g.exitCode ?? '—'}</span>
                        {g.exitCode === 99 && (
                          <Badge variant="warning" className="ml-1.5">
                            пороги
                          </Badge>
                        )}
                        {g.droppedLogLines > 0 && (
                          <span className="text-slate-400 ml-1">· строк лога отброшено: {g.droppedLogLines}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </details>
      </div>

      {/* 5 вкладок как в runs/view.html */}
      <div className="flex flex-wrap items-center gap-1 border-b border-slate-200">
        {(
          [
            { id: 'metrics', label: 'Метрики', icon: TrendingUp },
            { id: 'system', label: 'Системные метрики', icon: Cpu },
            { id: 'results', label: 'Результаты', icon: ClipboardList },
            { id: 'logs', label: 'Логи', icon: Terminal, errBadge: logCounts.ERROR },
            { id: 'snapshot', label: 'Параметры запуска', icon: Camera },
          ] as const
        ).map((t) => {
          const Icon = t.icon;
          return (
            <button
              key={t.id}
              type="button"
              onClick={() => switchTab(t.id)}
              className={`inline-flex items-center gap-1.5 border-b-2 px-3.5 py-2 text-sm font-medium cursor-pointer transition-colors ${
                tab === t.id
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-slate-500 hover:text-slate-800'
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{t.label}</span>
              {'errBadge' in t && t.errBadge > 0 && (
                <span className="rounded-full bg-rose-600 px-1.5 py-0.5 text-[10px] font-bold text-white leading-none">
                  {t.errBadge}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* ==================== Вкладка 1: Метрики ==================== */}
      {tab === 'metrics' && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs text-slate-500">{metricPhaseText}</span>
            <div className="flex flex-wrap items-center gap-2 ml-auto">
              <div className="inline-flex rounded-lg border border-slate-300 bg-white overflow-hidden">
                {(
                  [
                    ['window', 'Последняя минута'],
                    ['all', 'Весь тест'],
                  ] as const
                ).map(([rKey, rLabel]) => (
                  <button
                    key={rKey}
                    type="button"
                    onClick={() => {
                      setMetricRange(rKey);
                      try {
                        localStorage.setItem('k6lab.metricRange', rKey);
                      } catch {
                        /* ignore */
                      }
                    }}
                    className={`px-2.5 py-1 text-xs font-medium border-r last:border-r-0 border-slate-200 cursor-pointer ${
                      metricRange === rKey ? 'bg-slate-800 text-white' : 'text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    {rLabel}
                  </button>
                ))}
              </div>
              {grafanaUrl && (
                <a href={grafanaUrl} target="_blank" rel="noreferrer">
                  <Button variant="outline" size="xs">
                    <ExternalLink className="h-3.5 w-3.5" />
                    Открыть в Grafana
                  </Button>
                </a>
              )}
            </div>
          </div>

          {!isTerminal && lastM?.autoStopPending && (
            <div className="rounded-xl border border-amber-300 bg-amber-50 px-3.5 py-2 text-xs text-amber-950 flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-600 shrink-0" />
              <span>
                {lastM.autoStopPending.stopAfterStep > lastM.autoStopPending.triggerStep ? (
                  <>
                    <strong>Автостоп сработал на ступени {lastM.autoStopPending.triggerStep}</strong> (
                    {lastM.autoStopPending.reason}) — поиск максимума: проходим контрольную ступень{' '}
                    <strong>{lastM.autoStopPending.stopAfterStep}</strong> и останавливаем тест
                    {lastM.autoStopPending.remainingSec != null
                      ? ` (осталось ≈ ${fmtSpan(lastM.autoStopPending.remainingSec)})`
                      : ''}
                    .
                  </>
                ) : (
                  <>
                    <strong>Автостоп сработал на последней ступени {lastM.autoStopPending.triggerStep}</strong> (
                    {lastM.autoStopPending.reason}) — завершаем ступень до конца
                    {lastM.autoStopPending.remainingSec != null
                      ? ` (осталось ≈ ${fmtSpan(lastM.autoStopPending.remainingSec)})`
                      : ''}
                    .
                  </>
                )}
              </span>
            </div>
          )}

          {/* Таблица текущих показателей */}
          <Card className="overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                  <tr>
                    <th className="px-3.5 py-2 text-left font-medium">Операция</th>
                    <th className="px-3 py-2 text-left font-medium">Интенсивность факт / план, оп/с</th>
                    <th className="px-3 py-2 text-left font-medium">Время ответа / SLA</th>
                    <th className="px-3 py-2 text-left font-medium">Ошибки / порог</th>
                    <th className="px-3 py-2 text-left font-medium">Dropped</th>
                    <th className="px-3.5 py-2 text-left font-medium">Нарушения</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {currentTableRows.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-3.5 py-3 text-slate-400">
                        Нет данных
                      </td>
                    </tr>
                  ) : (
                    currentTableRows.map((r: any) => {
                      const secs = useAllRange ? lastM?.wholeSec || 0 : lastM?.windowSec || 0;
                      let dropTxt = fmtNum(r.dropped, 0);
                      if (r.dropped != null && secs > 0) {
                        dropTxt +=
                          (r.target > 0 ? ` · ${fmtNum((r.dropped / (r.target * secs)) * 100, 1)} %` : '') +
                          ` · ${fmtNum(r.dropped / secs, 2)} ит/с`;
                      }
                      const reasonsTxt = (r.reasons || []).join(', ');
                      return (
                        <tr key={r.op} className={r.violation ? 'bg-rose-50/70' : ''}>
                          <td className="px-3.5 py-2 font-semibold text-slate-900">{r.op}</td>
                          <td className="px-3 py-2 font-mono">
                            {fmtNum(r.actual, 1)} / {fmtNum(r.target, 1)}
                          </td>
                          <td className="px-3 py-2 font-mono">
                            {fmtNum(r.pct, 0)} / {r.sla} мс (p{r.pctN})
                          </td>
                          <td className="px-3 py-2 font-mono">
                            {fmtNum(r.err, 2)} / {r.maxErr} %
                          </td>
                          <td className="px-3 py-2 font-mono">{dropTxt}</td>
                          <td className="px-3.5 py-2">
                            {reasonsTxt ? (
                              <span className="font-semibold text-rose-600">{reasonsTxt}</span>
                            ) : (
                              <Badge variant="success">ОК</Badge>
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

          {/* Живые кастомные проверки Prometheus */}
          {currentCustomChecks.length > 0 && (
            <Card className="overflow-hidden">
              <CardHeader className="py-2">
                <span className="text-xs font-semibold flex items-center gap-1.5">
                  <Gauge className="h-3.5 w-3.5 text-indigo-600" />
                  Кастомные метрики Prometheus (SLA и Автостоп)
                </span>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">Метрика</th>
                      <th className="px-3 py-2 text-left font-medium">Текущее значение</th>
                      <th className="px-3 py-2 text-left font-medium">Порог</th>
                      <th className="px-3 py-2 text-left font-medium">Серия / хост</th>
                      <th className="px-3 py-2 text-left font-medium">Контроль</th>
                      <th className="px-3.5 py-2 text-left font-medium">Статус</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {currentCustomChecks.map((c: any, idx: number) => {
                      const valTxt =
                        c.value == null || !Number.isFinite(c.value)
                          ? 'нет данных'
                          : `${fmtNum(c.value, 1)}${c.unit ? ` ${c.unit}` : ''}`;
                      const role =
                        c.stepSla && c.autoStop
                          ? 'SLA + Автостоп'
                          : c.stepSla
                            ? 'SLA ступени'
                            : 'Автостоп';
                      return (
                        <tr key={idx} className={c.violation ? 'bg-rose-50/70' : ''}>
                          <td className="px-3.5 py-2 font-semibold text-slate-900">{c.name}</td>
                          <td className="px-3 py-2 font-mono">{valTxt}</td>
                          <td className="px-3 py-2 font-mono">
                            {c.thresholdText || `${c.cmp} ${c.threshold}${c.unit ? ` ${c.unit}` : ''}`}
                          </td>
                          <td className="px-3 py-2 text-slate-500">{c.worstSeries || '—'}</td>
                          <td className="px-3 py-2">{role}</td>
                          <td className="px-3.5 py-2">
                            {c.violation ? (
                              <Badge variant="danger">{c.reason || 'нарушение'}</Badge>
                            ) : c.value == null ? (
                              <Badge variant="default">нет данных</Badge>
                            ) : (
                              <Badge variant="success">в норме</Badge>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          {/* 4 графика с правой легендой как в Grafana */}
          {parsedMetrics.hasData && (
            <div className="space-y-3">
              <GrafanaMetricCard
                title="Интенсивность, оп/с: факт (сплошная) и план (пунктир)"
                unit="оп/с"
                decimals={1}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.rateSeries}
                selectedOps={selectedOps}
                onToggleOp={handleToggleOp}
                onResetOps={() => setSelectedOps(null)}
              />
              <GrafanaMetricCard
                title="Время запросов (HTTP), мс: перцентиль SLA (сплошная) и порог (пунктир)"
                unit="мс"
                decimals={0}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.pctSeries}
                selectedOps={selectedOps}
                onToggleOp={handleToggleOp}
                onResetOps={() => setSelectedOps(null)}
              />
              <GrafanaMetricCard
                title="Время метода целиком (итерация: запросы, паузы, работа генератора), мс — перцентиль SLA"
                unit="мс"
                decimals={0}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.iterSeries}
                selectedOps={selectedOps}
                onToggleOp={handleToggleOp}
                onResetOps={() => setSelectedOps(null)}
              />
              <GrafanaMetricCard
                title="Ошибки, %: факт (сплошная) и допустимый порог (пунктир)"
                unit="%"
                decimals={2}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.errSeries}
                selectedOps={selectedOps}
                onToggleOp={handleToggleOp}
                onResetOps={() => setSelectedOps(null)}
              />
            </div>
          )}
        </div>
      )}

      {/* ==================== Вкладка 2: Системные метрики ==================== */}
      {tab === 'system' && (
        <div className="space-y-3">
          <p className="text-xs text-slate-500">
            Состояние самого k6 по всем генераторам: сколько VU задействовано и сколько их доступно, сколько итераций k6
            не успел запустить (dropped). Рост dropped при упирающемся в максимум числе VU означает, что нагрузка не
            достигает заданной интенсивности.
          </p>
          {!parsedMetrics.hasData ? (
            <Card className="p-8 text-center text-xs text-slate-400">Ожидание данных из Prometheus…</Card>
          ) : (
            <div className="space-y-3">
              <GrafanaMetricCard
                title="Виртуальные пользователи (VU) по всем генераторам: задействованные и максимум"
                unit="VU"
                decimals={0}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.vusSeries}
                firstColTitle="Показатель"
                selectable={false}
                selectedOps={null}
              />
              <GrafanaMetricCard
                title="Dropped iterations: итерации, которые k6 не успел запустить, в секунду"
                unit="ит/с"
                decimals={2}
                labels={parsedMetrics.labels}
                phases={parsedMetrics.phases}
                series={parsedMetrics.dropSeries}
                selectedOps={selectedOps}
                onToggleOp={handleToggleOp}
                onResetOps={() => setSelectedOps(null)}
              />
            </div>
          )}
        </div>
      )}

      {/* ==================== Вкладка 3: Результаты (как в runs/_results.html, без авто-дёрганья) ==================== */}
      {tab === 'results' && (
        <div className="space-y-4">
          {!resData ? (
            <Spinner label="Загрузка результатов…" />
          ) : (
            <>
              {!resData.terminal && !resData.livePreview && (
                <div className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600">
                  Тест ещё не запущен — предварительные итоги по ступеням появятся сразу после начала нагрузки.
                </div>
              )}

              {/* Предварительные итоги при идущем тесте */}
              {resData.livePreview && (
                <div className="rounded-xl border border-sky-200 bg-sky-50 px-4 py-2.5 flex flex-wrap items-center gap-3 text-xs text-sky-950">
                  <span className="h-2.5 w-2.5 rounded-full bg-indigo-600 animate-pulse shrink-0" />
                  <div className="flex-1">
                    <strong>Предварительные итоги</strong> — тест ещё выполняется. Показаны уже пройденные ступени и
                    текущая ступень
                    {resData.data?.computedAt ? ` (обновлено в ${fmtTimeOnly(resData.data.computedAt)})` : ''}.
                    Окончательный отчёт, экспорт и вывод инженера будут доступны после завершения запуска.
                  </div>
                  <Button
                    variant="outline"
                    size="xs"
                    loading={refreshingResults}
                    onClick={handleManualRefreshResults}
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    Обновить
                  </Button>
                </div>
              )}

              {/* Панель экспорта отчётов для завершённого теста */}
              {resData.terminal && (
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    loading={refreshingResults}
                    onClick={handleManualRefreshResults}
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    Пересчитать итоги
                  </Button>

                  <div className="inline-flex rounded-lg border border-indigo-300 bg-white overflow-hidden">
                    <a
                      href={buildReportHref(`/runs/${run.id}/report.html`)}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-700 hover:bg-indigo-50 border-r border-indigo-200"
                    >
                      <FileCode className="h-3.5 w-3.5" />
                      HTML-отчёт
                    </a>
                    <a
                      href={buildReportHref(`/runs/${run.id}/report.html`, 'download=true')}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-700 hover:bg-indigo-50 border-r border-indigo-200"
                    >
                      <Download className="h-3.5 w-3.5" />
                      Скачать HTML
                    </a>
                    <a
                      href={buildReportHref(`/runs/${run.id}/report.pdf`)}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-700 hover:bg-indigo-50 border-r border-indigo-200"
                    >
                      <FileText className="h-3.5 w-3.5" />
                      PDF
                    </a>
                    <a
                      href={buildReportHref(`/runs/${run.id}/report.docx`)}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-700 hover:bg-indigo-50"
                    >
                      <FileText className="h-3.5 w-3.5" />
                      Word (docx)
                    </a>
                  </div>

                  <Select
                    value={repTimeAxis}
                    onChange={(e) => setRepTimeAxis(e.target.value as any)}
                    className="w-auto h-8 text-xs sm:ml-auto"
                    title="Ось времени на графиках отчёта"
                  >
                    <option value="">Время: по настройкам</option>
                    <option value="rel">Время: от начала теста</option>
                    <option value="abs">Время: абсолютное</option>
                  </Select>
                </div>
              )}

              {!resData.data ? (
                <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-xs text-amber-900">
                  {resData.livePreview
                    ? 'Данные по ступеням ещё собираются. Нажмите «Обновить» через несколько секунд.'
                    : 'Итоги ещё не рассчитаны (метрики поступают в Prometheus с задержкой). Нажмите «Пересчитать итоги» через несколько секунд.'}
                </div>
              ) : (
                <>
                  {!resData.data.prometheusOk && resData.data.error && (
                    <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-900">
                      {resData.data.error}
                    </div>
                  )}

                  {/* Вывод инженера (для завершённого теста) */}
                  {resData.terminal && (
                    <Card>
                      <CardBody className="space-y-2.5">
                        <h3 className="text-sm font-semibold text-slate-900">Вывод инженера</h3>
                        <div className="grid grid-cols-1 md:grid-cols-12 gap-3">
                          <div className="md:col-span-4">
                            <label className="block text-xs text-slate-500 mb-1">Итоговая оценка</label>
                            <Select value={conclusion} onChange={(e) => setConclusion(e.target.value)}>
                              <option value="">— не указан —</option>
                              {(resData.conclusions || []).map((c: string) => (
                                <option key={c} value={c}>
                                  {c}
                                </option>
                              ))}
                              {conclusion && !(resData.conclusions || []).includes(conclusion) && (
                                <option value={conclusion}>{conclusion}</option>
                              )}
                            </Select>
                          </div>
                          <div className="md:col-span-8">
                            <label className="block text-xs text-slate-500 mb-1">Комментарий</label>
                            <Textarea
                              rows={2}
                              value={comment}
                              onChange={(e) => setComment(e.target.value)}
                              placeholder="Наблюдения, узкие места, ссылка на задачу…"
                            />
                          </div>
                        </div>
                        <div>
                          <Button
                            size="sm"
                            loading={saveConclusionMut.isPending}
                            onClick={() => saveConclusionMut.mutate()}
                          >
                            <Check className="h-3.5 w-3.5" />
                            Сохранить
                          </Button>
                        </div>
                      </CardBody>
                    </Card>
                  )}

                  {/* Ступени и операции (как в reports/_steps.html) */}
                  {(resData.terminal || resData.data.prometheusOk) && (
                    <div className="space-y-4">
                      <div>
                        <h3 className="text-base font-semibold text-slate-900">
                          {resData.livePreview ? 'Ступени и операции (предварительно)' : 'Ступени и операции'}
                        </h3>
                        <p className="text-xs text-slate-500 mt-0.5">
                          Перцентили — histogram_quantile по гистограммам, просуммированным по всем генераторам. Окно
                          ступени — удержание без первых секунд выхода на режим.
                        </p>
                      </div>

                      {/* Поиск максимума + Сводка по ступеням */}
                      {resData.data.stepTest && resData.maxSummary && (
                        <div className="space-y-3">
                          <div
                            className={`rounded-xl border p-3.5 ${
                              resData.maxSummary.confirmedStepNo != null
                                ? 'border-emerald-300 bg-emerald-50/70'
                                : 'border-rose-300 bg-rose-50/70'
                            }`}
                          >
                            <div className="flex flex-wrap items-center gap-2 text-sm">
                              <Badge variant={resData.maxSummary.confirmedStepNo != null ? 'success' : 'danger'}>
                                {resData.maxSummary.confirmedStepNo != null
                                  ? 'Поиск максимума'
                                  : 'Максимум не подтверждён'}
                              </Badge>
                              <strong className="text-slate-900">{resData.maxSummary.headline}</strong>
                            </div>
                            {resData.maxSummary.detail && (
                              <div className="text-xs text-slate-700 mt-1">{resData.maxSummary.detail}</div>
                            )}
                          </div>

                          <Card className="overflow-hidden">
                            <CardHeader className="py-2">
                              <span className="text-xs font-semibold text-slate-900">Сводка по ступеням</span>
                            </CardHeader>
                            <div className="overflow-x-auto">
                              <table className="w-full text-xs">
                                <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                                  <tr>
                                    <th className="px-3.5 py-2 text-left font-medium">Ступень</th>
                                    <th className="px-3 py-2 text-right font-medium">Уровень</th>
                                    <th className="px-3 py-2 text-left font-medium">Окно расчёта</th>
                                    <th className="px-3 py-2 text-right font-medium">План сумм., оп/с</th>
                                    <th className="px-3 py-2 text-right font-medium">Факт сумм., оп/с</th>
                                    <th className="px-3 py-2 text-left font-medium">Причины / SLA</th>
                                    <th className="px-3 py-2 text-right font-medium">Ошибки макс., %</th>
                                    <th className="px-3 py-2 text-right font-medium">CPU ген., %</th>
                                    <th className="px-3.5 py-2 text-left font-medium">Статус</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100">
                                  {(resData.data.steps || []).map((s: any) => {
                                    const isMax = resData.maxSummary?.confirmedStepNo === s.stepNo;
                                    const planSum = (s.ops || []).reduce(
                                      (a: number, o: any) => a + (o.targetRate || 0),
                                      0
                                    );
                                    const actSum = (s.ops || []).reduce(
                                      (a: number, o: any) => a + (o.actualRate || 0),
                                      0
                                    );
                                    const maxErr = (s.ops || []).reduce(
                                      (a: number, o: any) => Math.max(a, o.errorPct || 0),
                                      0
                                    );
                                    const sm = stepStatusMeta(s.status);
                                    return (
                                      <tr
                                        key={s.stepNo}
                                        className={
                                          s.status === 'FAILED' || s.status === 'UNRELIABLE'
                                            ? 'bg-rose-50/50'
                                            : isMax
                                              ? 'bg-emerald-50/60'
                                              : ''
                                        }
                                      >
                                        <td className="px-3.5 py-2 whitespace-nowrap">
                                          <a
                                            href={`#step-${s.stepNo}`}
                                            className="font-semibold text-indigo-600 hover:underline"
                                          >
                                            Ступень {s.stepNo}
                                          </a>
                                          {isMax && (
                                            <Badge variant="success" className="ml-1.5">
                                              макс.
                                            </Badge>
                                          )}
                                        </td>
                                        <td className="px-3 py-2 text-right font-mono">{fmtNum(s.levelPct, 1)}%</td>
                                        <td className="px-3 py-2 font-mono text-slate-600 whitespace-nowrap">
                                          {s.status === 'NOT_RUN'
                                            ? '—'
                                            : `${fmtTimeOnly(s.windowStart)} – ${fmtTimeOnly(s.windowEnd)}`}
                                        </td>
                                        <td className="px-3 py-2 text-right font-mono">{fmtNum(planSum, 1)}</td>
                                        <td className="px-3 py-2 text-right font-mono">{fmtNum(actSum, 1)}</td>
                                        <td className="px-3 py-2 text-slate-600">
                                          {s.reasons?.length > 0 ? s.reasons.join(' · ') : 'в норме'}
                                        </td>
                                        <td className="px-3 py-2 text-right font-mono">{fmtNum(maxErr, 2)}</td>
                                        <td
                                          className={`px-3 py-2 text-right font-mono ${
                                            s.genOverloaded ? 'text-rose-600 font-bold' : ''
                                          }`}
                                        >
                                          {fmtNum(s.genCpuMax, 0)}
                                        </td>
                                        <td className="px-3.5 py-2">
                                          <span
                                            className={`inline-flex rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${sm.cls}`}
                                          >
                                            {sm.label}
                                          </span>
                                        </td>
                                      </tr>
                                    );
                                  })}
                                </tbody>
                              </table>
                            </div>
                          </Card>
                        </div>
                      )}

                      {/* Детальные таблицы по каждой ступени */}
                      {(resData.data.steps || []).map((s: any) => {
                        const sm = stepStatusMeta(s.status);
                        return (
                          <Card key={s.stepNo} id={`step-${s.stepNo}`} className="overflow-hidden">
                            <CardHeader className="flex-wrap">
                              <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-slate-900">
                                <span>
                                  Ступень {s.stepNo} — {fmtNum(s.levelPct, 1)}% от модели
                                </span>
                                <span
                                  className={`inline-flex rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${sm.cls}`}
                                >
                                  {sm.label}
                                </span>
                              </div>
                              {s.status !== 'NOT_RUN' && (
                                <span className="text-xs text-slate-500">
                                  окно расчёта {fmtTimeOnly(s.windowStart)} – {fmtTimeOnly(s.windowEnd)}
                                  {s.genCpuMax != null && (
                                    <span className={s.genOverloaded ? 'text-rose-600 font-semibold' : ''}>
                                      {' '}
                                      · CPU генераторов макс. {fmtNum(s.genCpuMax, 0)}%
                                    </span>
                                  )}
                                </span>
                              )}
                            </CardHeader>

                            {s.reasons?.length > 0 && (
                              <ul className="px-4 py-2 bg-rose-50/60 border-b border-rose-100 text-xs text-rose-800 list-disc list-inside space-y-0.5">
                                {s.reasons.map((r: string, idx: number) => (
                                  <li key={idx}>{r}</li>
                                ))}
                              </ul>
                            )}

                            {s.ops?.length > 0 && (
                              <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                                    <tr>
                                      <th className="px-3.5 py-2 text-left font-medium">Операция</th>
                                      <th className="px-2.5 py-2 text-right font-medium">План, оп/с</th>
                                      <th className="px-2.5 py-2 text-right font-medium">Факт, оп/с</th>
                                      <th className="px-2.5 py-2 text-right font-medium">Откл., %</th>
                                      <th className="px-2.5 py-2 text-right font-medium">p50, мс</th>
                                      <th className="px-2.5 py-2 text-right font-medium">p90, мс</th>
                                      <th className="px-2.5 py-2 text-right font-medium">p95, мс</th>
                                      <th className="px-2.5 py-2 text-right font-medium">p99, мс</th>
                                      <th className="px-2.5 py-2 text-right font-medium">SLA</th>
                                      <th className="px-2.5 py-2 text-right font-medium">Ошибки, %</th>
                                      <th className="px-3.5 py-2 text-right font-medium">Dropped</th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-slate-100">
                                    {s.ops.map((o: any) => (
                                      <tr key={o.operation} className={o.ok ? '' : 'bg-rose-50/60'}>
                                        <td className="px-3.5 py-2 font-medium text-slate-900">{o.operation}</td>
                                        <td className="px-2.5 py-2 text-right font-mono">{fmtNum(o.targetRate, 2)}</td>
                                        <td className="px-2.5 py-2 text-right font-mono">{fmtNum(o.actualRate, 2)}</td>
                                        <td className="px-2.5 py-2 text-right font-mono">
                                          {fmtNum(o.deviationPct, 1)}
                                        </td>
                                        <td className="px-2.5 py-2 text-right font-mono">{fmtNum(o.p50, 0)}</td>
                                        <td
                                          className={`px-2.5 py-2 text-right font-mono ${
                                            o.slaPercentile === 90 ? 'font-bold bg-indigo-50/40' : ''
                                          }`}
                                        >
                                          {fmtNum(o.p90, 0)}
                                        </td>
                                        <td
                                          className={`px-2.5 py-2 text-right font-mono ${
                                            o.slaPercentile === 95 ? 'font-bold bg-indigo-50/40' : ''
                                          }`}
                                        >
                                          {fmtNum(o.p95, 0)}
                                        </td>
                                        <td
                                          className={`px-2.5 py-2 text-right font-mono ${
                                            o.slaPercentile === 99 ? 'font-bold bg-indigo-50/40' : ''
                                          }`}
                                        >
                                          {fmtNum(o.p99, 0)}
                                        </td>
                                        <td className="px-2.5 py-2 text-right font-mono whitespace-nowrap">
                                          p{o.slaPercentile} ≤ {Math.round(o.slaMs)}
                                        </td>
                                        <td className="px-2.5 py-2 text-right font-mono whitespace-nowrap">
                                          {fmtNum(o.errorPct, 2)} / {fmtNum(o.maxErrorPct, 2)}
                                        </td>
                                        <td className="px-3.5 py-2 text-right font-mono">{fmtNum(o.dropped, 0)}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}

                            {s.customChecks?.length > 0 && (
                              <div className="overflow-x-auto border-t border-slate-200">
                                <table className="w-full text-xs">
                                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-100">
                                    <tr>
                                      <th className="px-3.5 py-2 text-left font-medium">
                                        Кастомная метрика Prometheus
                                      </th>
                                      <th className="px-3 py-2 text-right font-medium">Факт (худшее за ступень)</th>
                                      <th className="px-3 py-2 text-right font-medium">Порог SLA</th>
                                      <th className="px-3 py-2 text-left font-medium">Серия / хост</th>
                                      <th className="px-3 py-2 text-left font-medium">Контроль</th>
                                      <th className="px-3.5 py-2 text-left font-medium">Статус</th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-slate-100">
                                    {s.customChecks.map((cm: any, idx: number) => (
                                      <tr key={idx} className={cm.ok ? '' : 'bg-rose-50/60'}>
                                        <td className="px-3.5 py-2 font-medium text-slate-900">{cm.name}</td>
                                        <td className="px-3 py-2 text-right font-mono">
                                          {cm.value == null
                                            ? 'нет данных'
                                            : `${fmtNum(cm.value, 1)}${cm.unit ? ` ${cm.unit}` : ''}`}
                                        </td>
                                        <td className="px-3 py-2 text-right font-mono">
                                          {cm.thresholdText || `${cm.cmp || ''} ${cm.threshold ?? ''}`}
                                        </td>
                                        <td className="px-3 py-2 text-slate-600">{cm.worstSeries || '—'}</td>
                                        <td className="px-3 py-2">
                                          {cm.stepSla && cm.autoStop
                                            ? 'SLA + Автостоп'
                                            : cm.stepSla
                                              ? 'SLA ступени'
                                              : 'Автостоп'}
                                        </td>
                                        <td className="px-3.5 py-2">
                                          <Badge variant={cm.dataMissing ? 'warning' : cm.ok ? 'success' : 'danger'}>
                                            {cm.dataMissing ? 'нет данных' : cm.ok ? 'в норме' : 'нарушение'}
                                          </Badge>
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}
                          </Card>
                        );
                      })}

                      {/* Кастомные графики отчёта */}
                      {resData.charts?.length > 0 && (
                        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
                          {resData.charts.map((ch: any, idx: number) => (
                            <Card key={idx} className="overflow-hidden">
                              <CardHeader className="py-2">
                                <span className="font-semibold text-xs">{ch.title}</span>
                              </CardHeader>
                              <CardBody className="p-2">
                                <img src={ch.dataUri} alt={ch.title} className="w-full h-auto rounded-lg" />
                              </CardBody>
                            </Card>
                          ))}
                        </div>
                      )}

                      {/* Генераторы (справочно) для завершённого теста */}
                      {resData.terminal && resData.data.generators?.length > 0 && (
                        <div className="space-y-2 pt-2">
                          <div>
                            <h3 className="text-base font-semibold text-slate-900">Генераторы (справочно)</h3>
                            <p className="text-xs text-slate-500">
                              Накопленные за весь тест значения k6; для оценки SLA не используются.
                            </p>
                          </div>
                          <Card className="overflow-hidden">
                            <div className="overflow-x-auto">
                              <table className="w-full text-xs">
                                <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                                  <tr>
                                    <th className="px-3.5 py-2 text-left font-medium">Генератор</th>
                                    <th className="px-3 py-2 text-left font-medium">Статус</th>
                                    <th className="px-3 py-2 text-left font-medium">Код k6</th>
                                    <th className="px-3 py-2 text-right font-medium">CPU макс., %</th>
                                    <th className="px-3 py-2 text-right font-medium">Итераций</th>
                                    <th className="px-3 py-2 text-right font-medium">Dropped</th>
                                    <th className="px-3.5 py-2 text-right font-medium">HTTP-запросов</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100">
                                  {resData.data.generators.map((g: any, idx: number) => (
                                    <tr key={idx}>
                                      <td className="px-3.5 py-2 font-semibold text-slate-900">{g.generator}</td>
                                      <td className="px-3 py-2">{g.status}</td>
                                      <td className="px-3 py-2 font-mono">{g.exitCode ?? '—'}</td>
                                      <td className="px-3 py-2 text-right font-mono">{fmtNum(g.cpuMax, 0)}</td>
                                      <td className="px-3 py-2 text-right font-mono">{fmtNum(g.iterations, 0)}</td>
                                      <td className="px-3 py-2 text-right font-mono">{fmtNum(g.dropped, 0)}</td>
                                      <td className="px-3.5 py-2 text-right font-mono">{fmtNum(g.httpReqs, 0)}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          </Card>
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </div>
      )}

      {/* ==================== Вкладка 4: Логи (как в runs/view.html) ==================== */}
      {tab === 'logs' && (
        <Card className="overflow-hidden">
          <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 bg-slate-50 px-3 py-2">
            <div className="flex flex-wrap items-center gap-1">
              {(
                [
                  ['', 'Все', logCounts.all],
                  ['ERROR', 'ERROR', logCounts.ERROR],
                  ['WARN', 'WARN', logCounts.WARN],
                  ['INFO', 'INFO', logCounts.INFO],
                  ['DEBUG', 'DEBUG', logCounts.DEBUG],
                ] as const
              ).map(([lvl, title, cnt]) => (
                <button
                  key={title}
                  type="button"
                  onClick={() => setLogLevel(lvl)}
                  className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium cursor-pointer ${
                    logLevel === lvl
                      ? lvl === 'ERROR'
                        ? 'bg-rose-600 text-white'
                        : lvl === 'WARN'
                          ? 'bg-amber-500 text-white'
                          : 'bg-slate-800 text-white'
                      : 'bg-white border border-slate-200 text-slate-600 hover:bg-slate-100'
                  }`}
                >
                  <span>{title}</span>
                  <span className="opacity-75 text-[10px]">{cnt}</span>
                </button>
              ))}
            </div>

            <Select
              value={logGenFilter}
              onChange={(e) => setLogGenFilter(e.target.value)}
              className="h-7 w-44 text-xs"
            >
              <option value="">все генераторы</option>
              {generators.map((g: any) => (
                <option key={g.id} value={g.generatorId || ''}>
                  {g.generatorName}
                </option>
              ))}
            </Select>

            <Input
              value={logSearch}
              onChange={(e) => setLogSearch(e.target.value)}
              placeholder="Поиск по тексту…"
              className="h-7 w-52 text-xs"
            />

            <div className="ml-auto flex flex-wrap items-center gap-1">
              <Button
                variant={logPaused ? 'warning' : 'outline'}
                size="xs"
                onClick={() => setLogPaused((v) => !v)}
                title="Автопрокрутка к новым строкам"
              >
                {logPaused ? <Play className="h-3 w-3 fill-current" /> : <Pause className="h-3 w-3" />}
                {logPaused ? 'Продолжить' : 'Пауза'}
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  if (logBoxRef.current) logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
                }}
                title="Прокрутить в конец"
              >
                <ArrowDown className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant={logWrap ? 'secondary' : 'outline'}
                size="xs"
                onClick={() => setLogWrap((v) => !v)}
                title="Перенос длинных строк"
              >
                <WrapText className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  const txt = filteredLogs.map((l) => l.line).join('\n');
                  navigator.clipboard.writeText(txt);
                  toast.success('Видимый лог скопирован');
                }}
                title="Копировать видимый лог"
              >
                <Clipboard className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => setClearedBeforeIdx((logsData?.lines || []).length)}
                title="Очистить буфер на экране (файл на сервере не меняется)"
              >
                <Eraser className="h-3.5 w-3.5" />
              </Button>
              <a href={`/runs/${run.id}/log.txt`}>
                <Button variant="outline" size="xs">
                  <Download className="h-3.5 w-3.5" />
                  Скачать
                </Button>
              </a>
            </div>
          </div>

          <pre
            ref={logBoxRef}
            className={`bg-slate-950 text-slate-200 font-mono text-xs p-3 h-[58vh] overflow-auto m-0 ${
              logWrap ? 'whitespace-pre-wrap break-all' : 'whitespace-pre'
            }`}
          >
            {filteredLogs.length === 0 ? (
              <span className="text-slate-500">Логи пусты</span>
            ) : (
              filteredLogs.map((l: any, idx: number) => {
                const lv = String(l.level || '').toUpperCase();
                return (
                  <div
                    key={idx}
                    className={
                      lv === 'ERROR' || String(l.line || '').includes('ERRO')
                        ? 'text-rose-400'
                        : lv === 'WARN'
                          ? 'text-amber-300'
                          : 'text-slate-200'
                    }
                  >
                    {l.line}
                  </div>
                );
              })
            )}
          </pre>
        </Card>
      )}

      {/* ==================== Вкладка 5: Параметры запуска (tab-snapshot) ==================== */}
      {tab === 'snapshot' && (
        <div className="space-y-3.5">
          <div className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
            Снимок нагрузки и конфига k6 на момент запуска: последующие правки скрипта на него не влияют.
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            <Card className="overflow-hidden">
              <CardHeader className="py-2.5">
                <span className="text-xs font-semibold text-slate-900">Профиль</span>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">№</th>
                      <th className="px-3 py-2 text-left font-medium">Метка</th>
                      <th className="px-3 py-2 text-right font-medium">Уровень %</th>
                      <th className="px-3.5 py-2 text-right font-medium">Удержание, с</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {(snapshot?.steps || []).map((s: any) => (
                      <tr key={s.no}>
                        <td className="px-3.5 py-2 font-mono">{s.no}</td>
                        <td className="px-3 py-2">{s.label}</td>
                        <td className="px-3 py-2 text-right font-mono">{s.levelPct}</td>
                        <td className="px-3.5 py-2 text-right font-mono">{s.holdSec}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card className="overflow-hidden">
              <CardHeader className="py-2.5">
                <span className="text-xs font-semibold text-slate-900">Операции и SLA</span>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">Операция</th>
                      <th className="px-3 py-2 text-left font-medium">Функция</th>
                      <th className="px-3 py-2 text-right font-medium">оп/с</th>
                      <th className="px-3.5 py-2 text-left font-medium">SLA</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {(snapshot?.operations || []).map((o: any) => (
                      <tr key={o.name}>
                        <td className="px-3.5 py-2 font-medium text-slate-900">{o.name}</td>
                        <td className="px-3 py-2">
                          <code className="text-slate-600">{o.execFn}</code>
                        </td>
                        <td className="px-3 py-2 text-right font-mono">{fmtNum(o.ratePerSec, 3)}</td>
                        <td className="px-3.5 py-2 text-slate-600">
                          p{o.slaPercentile} ≤ {o.slaThresholdMs} мс, ошибки ≤ {o.maxErrorPct}%
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          {customChecksConfig.length > 0 && (
            <Card className="overflow-hidden">
              <CardHeader className="py-2.5">
                <span className="text-xs font-semibold text-slate-900 flex items-center gap-1.5">
                  <Activity className="h-3.5 w-3.5 text-indigo-600" />
                  Кастомные метрики Prometheus (SLA и Автостоп)
                </span>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">Метрика</th>
                      <th className="px-3 py-2 text-left font-medium">PromQL-запрос</th>
                      <th className="px-3 py-2 text-left font-medium">Порог SLA</th>
                      <th className="px-3.5 py-2 text-left font-medium">Контроль</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {customChecksConfig.map((cc: any, idx: number) => (
                      <tr key={idx}>
                        <td className="px-3.5 py-2 font-semibold text-slate-900">{cc.name}</td>
                        <td className="px-3 py-2">
                          <code className="text-slate-600">{cc.promql}</code>
                        </td>
                        <td className="px-3 py-2 font-mono">{cc.thresholdText}</td>
                        <td className="px-3.5 py-2">
                          <Badge variant="default">
                            {cc.stepSla && cc.autoStop
                              ? 'SLA + Автостоп'
                              : cc.stepSla
                                ? 'SLA отчёта'
                                : 'Автостоп'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          <Card className="overflow-hidden">
            <CardHeader className="py-2.5">
              <span className="text-xs font-semibold text-slate-900">Переменные запуска</span>
            </CardHeader>
            {envRows.length === 0 ? (
              <div className="p-3.5 text-xs text-slate-500">Переменные не заданы.</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="px-3.5 py-2 text-left font-medium">Где задана</th>
                      <th className="px-3 py-2 text-left font-medium">Переменная</th>
                      <th className="px-3.5 py-2 text-left font-medium">Значение</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {envRows.map((e: any, idx: number) => (
                      <tr key={idx}>
                        <td className="px-3.5 py-2 text-slate-600">{e.scope}</td>
                        <td className="px-3 py-2">
                          <code className="font-semibold text-slate-800">{e.name}</code>
                        </td>
                        <td className="px-3.5 py-2 font-mono break-all text-slate-700">{e.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card className="overflow-hidden">
            <CardHeader className="py-2.5">
              <span className="text-xs font-semibold text-slate-900">Конфиг k6</span>
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  const text = typeof k6Config === 'string' ? k6Config : JSON.stringify(k6Config || {}, null, 2);
                  navigator.clipboard.writeText(text);
                  toast.success('Конфиг k6 скопирован');
                }}
              >
                <Clipboard className="h-3.5 w-3.5" />
                Копировать
              </Button>
            </CardHeader>
            <pre className="m-0 p-3 text-xs font-mono bg-slate-950 text-slate-200 max-h-96 overflow-auto">
              {typeof k6Config === 'string' ? k6Config : JSON.stringify(k6Config || {}, null, 2)}
            </pre>
          </Card>
        </div>
      )}
    </div>
  );
}
