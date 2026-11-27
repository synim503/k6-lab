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
  BarChart3,
  Calendar,
  Camera,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronsDown,
  ChevronsUp,
  Clipboard,
  ClipboardList,
  Cpu,
  Download,
  Eraser,
  ExternalLink,
  Eye,
  EyeOff,
  FileCode,
  FileText,
  Gauge,
  LayoutList,
  Maximize2,
  OctagonX,
  Pause,
  Pencil,
  Play,
  Printer,
  RefreshCw,
  RotateCcw,
  RotateCw,
  SlidersHorizontal,
  Sparkles,
  Square,
  Terminal,
  TrendingUp,
  WrapText,
  X,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Modal, Select, Spinner, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { conclusionVariant, runStatusMeta } from '@/lib/utils';

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

  // Results & Report Studio state
  const [repCustomTitle, setRepCustomTitle] = useState('');
  const [repSections, setRepSections] = useState<Record<string, boolean>>({
    summary: true,
    config: true,
    env: true,
    steps: true,
    charts: true,
    generators: true,
  });
  const [repExcludedCharts, setRepExcludedCharts] = useState<string[]>([]);
  const [repTimeAxis, setRepTimeAxis] = useState<'' | 'rel' | 'abs'>('');
  const [repWarmup, setRepWarmup] = useState<'' | 'include' | 'skip'>('');
  const [repSortBy, setRepSortBy] = useState<'' | 'NONE' | 'MAX' | 'MEAN' | 'LAST'>('');
  const [repSortDir, setRepSortDir] = useState<'' | 'DESC' | 'ASC'>('');
  const [conclusion, setConclusion] = useState('');
  const [comment, setComment] = useState('');
  const [savedReportSnap, setSavedReportSnap] = useState('');
  const [reportSettingsOpen, setReportSettingsOpen] = useState(false);
  const [reportPreviewOpen, setReportPreviewOpen] = useState(false);
  const [resultsSubTab, setResultsSubTab] = useState<'summary' | 'charts' | 'generators'>('summary');
  const [editingConclusion, setEditingConclusion] = useState(false);
  const [expandedStepNos, setExpandedStepNos] = useState<number[] | null>(null);
  const [chartCatFilter, setChartCatFilter] = useState<'all' | 'k6' | 'steps' | 'custom' | 'sla'>('all');
  const [lightboxIdx, setLightboxIdx] = useState<number | null>(null);
  const [refreshingResults, setRefreshingResults] = useState(false);
  const reportInitRunRef = useRef<string | undefined>(undefined);
  const commentTaRef = useRef<HTMLTextAreaElement>(null);

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

  const buildResultsApiUrl = (refresh = false) => {
    const qs = new URLSearchParams();
    if (refresh) qs.set('refresh', 'true');
    if (repTimeAxis) qs.set('time', repTimeAxis);
    if (repWarmup) qs.set('warmup', repWarmup);
    if (repSortBy) qs.set('sort', repSortBy);
    if (repSortDir) qs.set('dir', repSortDir);
    const s = qs.toString();
    return `/api/v1/runs/${id}/results${s ? `?${s}` : ''}`;
  };

  // Вкладка «Результаты и отчёт» загружается без таймера (перестроение только при смене фильтров графиков или по кнопке)
  const { data: resData, isFetching: fetchingResults } = useQuery({
    queryKey: ['run-results', id, repTimeAxis, repWarmup, repSortBy, repSortDir],
    queryFn: () => apiFetch(buildResultsApiUrl(false)),
    enabled: !!id,
  });

  useEffect(() => {
    if (lightboxIdx == null) return;
    const list: any[] = resData?.charts || [];
    if (list.length === 0) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setLightboxIdx(null);
      else if (e.key === 'ArrowRight') setLightboxIdx((prev) => (prev == null ? null : (prev + 1) % list.length));
      else if (e.key === 'ArrowLeft')
        setLightboxIdx((prev) => (prev == null ? null : (prev - 1 + list.length) % list.length));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [lightboxIdx, resData?.charts]);

  const makeReportSnap = (
    conc: string,
    comm: string,
    title: string,
    secs: Record<string, boolean>,
    excl: string[],
    tAxis: string,
    wMode: string,
    sBy: string,
    sDir: string
  ) =>
    JSON.stringify({
      conc: conc.trim(),
      comm: comm.replace(/\r\n/g, '\n').trim(),
      title: title.trim(),
      secs: {
        summary: secs.summary !== false,
        config: secs.config !== false,
        env: secs.env !== false,
        steps: secs.steps !== false,
        charts: secs.charts !== false,
        generators: secs.generators !== false,
      },
      excl: [...excl].sort(),
      tAxis,
      wMode,
      sBy,
      sDir,
    });

  useEffect(() => {
    if (!resData || !id) return;
    if (reportInitRunRef.current !== id) {
      reportInitRunRef.current = id;
      const cfg = resData.reportConfig || {};
      const secs = {
        summary: cfg.sections?.summary !== false,
        config: cfg.sections?.config !== false,
        env: cfg.sections?.env !== false,
        steps: cfg.sections?.steps !== false,
        charts: cfg.sections?.charts !== false,
        generators: cfg.sections?.generators !== false,
      };
      const excl = Array.isArray(cfg.excludedCharts) ? cfg.excludedCharts : [];
      const tAxis = (cfg.timeAxis || '') as '' | 'rel' | 'abs';
      const wMode = (cfg.warmup || '') as '' | 'include' | 'skip';
      const sBy = (cfg.sortBy || '') as '' | 'NONE' | 'MAX' | 'MEAN' | 'LAST';
      const sDir = (cfg.sortDir || '') as '' | 'DESC' | 'ASC';
      const conc = resData.conclusion || '';
      const comm = resData.conclusionComment || '';
      const cTitle = cfg.customTitle || '';

      setConclusion(conc);
      setComment(comm);
      setRepCustomTitle(cTitle);
      setRepSections(secs);
      setRepExcludedCharts(excl);
      setRepTimeAxis(tAxis);
      setRepWarmup(wMode);
      setRepSortBy(sBy);
      setRepSortDir(sDir);
      setEditingConclusion(false);
      setExpandedStepNos(null);
      setSavedReportSnap(makeReportSnap(conc, comm, cTitle, secs, excl, tAxis, wMode, sBy, sDir));
    }
  }, [resData, id]);

  const currentReportConfigPayload = useMemo(
    () => ({
      customTitle: repCustomTitle.trim(),
      sections: repSections,
      excludedCharts: repExcludedCharts,
      timeAxis: repTimeAxis,
      warmup: repWarmup,
      sortBy: repSortBy,
      sortDir: repSortDir,
    }),
    [repCustomTitle, repSections, repExcludedCharts, repTimeAxis, repWarmup, repSortBy, repSortDir]
  );

  const currentReportSnap = useMemo(
    () =>
      makeReportSnap(
        conclusion,
        comment,
        repCustomTitle,
        repSections,
        repExcludedCharts,
        repTimeAxis,
        repWarmup,
        repSortBy,
        repSortDir
      ),
    [conclusion, comment, repCustomTitle, repSections, repExcludedCharts, repTimeAxis, repWarmup, repSortBy, repSortDir]
  );

  const isReportDirty = Boolean(savedReportSnap && currentReportSnap !== savedReportSnap);

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
    mutationFn: (silent?: boolean) =>
      apiFetch(`/api/v1/runs/${id}/conclusion`, {
        method: 'POST',
        body: {
          conclusion,
          comment,
          reportConfig: currentReportConfigPayload,
        },
      }).then((res) => ({ res, silent })),
    onSuccess: ({ silent }) => {
      setSavedReportSnap(currentReportSnap);
      if (!silent) {
        setEditingConclusion(false);
        toast.success('Вывод инженера и настройки отчёта сохранены');
      }
    },
    onError: (err: any) => toast.error(err.message),
  });

  const saveReportConfigQuick = async (nextSecs: Record<string, boolean>, nextExcl: string[]) => {
    try {
      await apiFetch(`/api/v1/runs/${id}/report-config`, {
        method: 'POST',
        body: {
          customTitle: repCustomTitle.trim(),
          sections: nextSecs,
          excludedCharts: nextExcl,
          timeAxis: repTimeAxis,
          warmup: repWarmup,
          sortBy: repSortBy,
          sortDir: repSortDir,
        },
      });
      setSavedReportSnap(
        makeReportSnap(
          conclusion,
          comment,
          repCustomTitle,
          nextSecs,
          nextExcl,
          repTimeAxis,
          repWarmup,
          repSortBy,
          repSortDir
        )
      );
    } catch (err: any) {
      toast.error(err.message);
    }
  };

  const handleToggleReportSection = (secKey: string) => {
    const next = { ...repSections, [secKey]: repSections[secKey] === false };
    setRepSections(next);
    if (resData?.terminal) {
      saveReportConfigQuick(next, repExcludedCharts);
    }
  };

  const handleToggleChartIncluded = (chartTitle: string) => {
    const set = new Set(repExcludedCharts);
    if (set.has(chartTitle)) set.delete(chartTitle);
    else set.add(chartTitle);
    const next = Array.from(set);
    setRepExcludedCharts(next);
    if (resData?.terminal) {
      saveReportConfigQuick(repSections, next);
    }
  };

  const handleExportAction = async (url: string, mode: 'tab' | 'download' | 'preview') => {
    if (isReportDirty && resData?.terminal) {
      try {
        await saveConclusionMut.mutateAsync(true);
      } catch {
        return;
      }
    }
    if (mode === 'preview') {
      setReportPreviewOpen(true);
      return;
    }
    if (mode === 'tab') {
      window.open(url, '_blank', 'noopener,noreferrer');
    } else {
      const a = document.createElement('a');
      a.href = url;
      a.click();
    }
  };

  const handleGenerateDraft = async () => {
    if (!resData?.data) return;
    if (comment.trim()) {
      const ok = await confirm({
        title: 'Заменить текущий комментарий черновиком?',
        message: 'Существующий текст вывода инженера будет заменён автоматически собранной сводкой по результатам прогона.',
        confirmText: 'Сформировать заново',
      });
      if (!ok) return;
    }

    const r = detail?.run;
    const snap = detail?.snapshot;
    const steps: any[] = resData.data.steps || [];
    const ms = resData.maxSummary;
    const failedSteps = steps.filter((s) => s.status !== 'SUCCESS' && s.status !== 'NOT_RUN');
    const passedSteps = steps.filter((s) => s.status === 'SUCCESS');

    // Автоподбор итогового вердикта
    if (r?.degraded || !resData.data.prometheusOk) {
      setConclusion('Результат недостоверен');
    } else if (failedSteps.length === 0 && passedSteps.length > 0) {
      setConclusion('Нагрузка выдерживается');
    } else if (r?.stopReason || (ms && ms.confirmedStepNo == null && failedSteps.length > 0)) {
      setConclusion('Деградация');
    } else if (failedSteps.length > 0) {
      setConclusion('Есть нарушения SLA');
    }

    const lines: string[] = [];
    const profPart = r?.loadProfileName ? `, профиль «${r.loadProfileName}»` : '';
    const genCount = snap?.generators?.length || detail?.generators?.length || 1;
    lines.push(
      `Проведён нагрузочный тест сценария «${snap?.script?.name || r?.scriptName || ''}»` +
        `${snap?.script?.versionNumber != null ? ` v${snap.script.versionNumber}` : ''}${profPart} ` +
        `(генераторов: ${genCount}, ступеней: ${steps.length}).`
    );

    if (ms) {
      lines.push(`Итог поиска максимума: ${ms.headline}.${ms.detail ? ` ${ms.detail}` : ''}`);
    } else if (steps.length === 1) {
      const s0 = steps[0];
      lines.push(
        `Стационарная ступень (${fmtNum(s0.levelPct, 1)}% от модели): план ${fmtNum(s0.totalTargetRate, 1)} оп/с, ` +
          `факт ${fmtNum(s0.totalActualRate, 1)} оп/с, макс. ошибки ${fmtNum(s0.maxErrorPct, 2)}%.`
      );
    }

    if (r?.stopReason) {
      lines.push(`Причина досрочной остановки: ${r.stopReason}.`);
    }

    if (failedSteps.length > 0) {
      const breachLines: string[] = [];
      for (const fs of failedSteps.slice(0, 4)) {
        const rs = (fs.reasons || []).join('; ');
        breachLines.push(`• Ступень ${fs.stepNo} (${fmtNum(fs.levelPct, 1)}%): ${rs || fs.status}`);
      }
      lines.push(`Зафиксированные отклонения:\n${breachLines.join('\n')}`);
    } else if (passedSteps.length > 0) {
      lines.push('Нарушений SLA по времени отклика, доле ошибок и кастомным метрикам Prometheus не зафиксировано.');
    }

    const gens: any[] = resData.data.generators || [];
    const hotGen = gens.filter((g) => (g.cpuMax ?? 0) >= 80);
    const dropGen = gens.reduce((acc, g) => acc + (Number(g.dropped) || 0), 0);
    if (r?.degraded) {
      lines.push('Внимание: в ходе теста фиксировалась потеря связи с генераторами нагрузки.');
    } else if (hotGen.length > 0 || dropGen > 0) {
      lines.push(
        `Утилизация генераторов: ${
          hotGen.length > 0
            ? `высокий CPU на ${hotGen.map((g) => `${g.generator} (${fmtNum(g.cpuMax, 0)}%)`).join(', ')}`
            : 'CPU в норме'
        }${dropGen > 0 ? `, накоплено dropped_iterations: ${fmtNum(dropGen, 0)}` : ''}.`
      );
    } else if (gens.length > 0) {
      const maxCpu = Math.max(...gens.map((g) => Number(g.cpuMax) || 0));
      lines.push(`Генераторы нагрузки отработали штатно (пик CPU до ${fmtNum(maxCpu, 0)}%, потерь связи нет).`);
    }

    if (failedSteps.length > 0) {
      lines.push('Рекомендации:\n• Проанализировать узкие места СУБД / сервисов на ступени деградации.\n• Повторить контрольный прогон после оптимизации.');
    }

    setEditingConclusion(true);
    setComment(lines.join('\n\n'));
    toast.success('Черновик вывода сформирован по метрикам прогона');
  };

  const handleManualRefreshResults = async () => {
    setRefreshingResults(true);
    try {
      const fresh = await apiFetch(buildResultsApiUrl(true));
      qc.setQueryData(['run-results', id, repTimeAxis, repWarmup, repSortBy, repSortDir], fresh);
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
          .push(
            r?.droppedPerSec != null
              ? r.droppedPerSec
              : r?.dropped != null
              ? r.dropped / Math.max(1, m.windowSec || 1)
              : null
          );
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

  const allReportCharts: any[] = useMemo(() => resData?.charts || [], [resData?.charts]);
  const excludedSet = useMemo(() => new Set(repExcludedCharts), [repExcludedCharts]);
  const filteredReportCharts = useMemo(
    () =>
      chartCatFilter === 'all'
        ? allReportCharts
        : allReportCharts.filter((c) => (c.category || 'custom') === chartCatFilter),
    [allReportCharts, chartCatFilter]
  );
  const includedChartsCount = useMemo(
    () => allReportCharts.filter((c) => !excludedSet.has(c.title)).length,
    [allReportCharts, excludedSet]
  );

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

  const buildReportHref = (base: string, extra?: string) => {
    const qs = new URLSearchParams();
    if (extra) {
      for (const pair of extra.split('&')) {
        const [k, v] = pair.split('=');
        if (k) qs.set(k, v ?? 'true');
      }
    }
    if (repTimeAxis) qs.set('time', repTimeAxis);
    if (repWarmup) qs.set('warmup', repWarmup);
    if (repSortBy) qs.set('sort', repSortBy);
    if (repSortDir) qs.set('dir', repSortDir);
    const s = qs.toString();
    return s ? `${base}?${s}` : base;
  };

  const downloadDataUriPng = (dataUri: string, chartTitle: string) => {
    const safe = (chartTitle || 'chart').replace(/[\\/:*?"<>|\r\n]+/g, '_');
    const a = document.createElement('a');
    a.href = dataUri;
    a.download = `run-${run.id}-${safe}.png`;
    a.click();
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
            { id: 'results', label: 'Результаты и отчёт', icon: ClipboardList },
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
                      let dropTxt = '—';
                      if (!useAllRange && r.droppedPerSec != null) {
                        const dps = Number(r.droppedPerSec) || 0;
                        const pctPart = r.target > 0 ? ` (${fmtNum((dps / r.target) * 100, 1)} %)` : '';
                        dropTxt = `${fmtNum(dps, 2)} ит/с${pctPart}`;
                      } else if (r.dropped != null) {
                        dropTxt = fmtNum(r.dropped, 0);
                        if (secs > 0) {
                          dropTxt +=
                            (r.target > 0 ? ` · ${fmtNum((r.dropped / (r.target * secs)) * 100, 1)} %` : '') +
                            ` · ${fmtNum(r.dropped / secs, 2)} ит/с`;
                        }
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

      {/* ==================== Вкладка 3: Результаты и отчёт (разгруженный вид с подвкладками и аккордеонами) ==================== */}
      {tab === 'results' && (
        <div className="space-y-4">
          {!resData ? (
            <Spinner label="Загрузка результатов…" />
          ) : (
            (() => {
              const resultSteps: any[] = resData.data?.steps || [];
              const resultGens: any[] = resData.data?.generators || [];
              const executedSteps = resultSteps.filter((s) => s.status !== 'NOT_RUN');
              const passedSteps = executedSteps.filter((s) => s.status === 'SUCCESS');
              const failedSteps = executedSteps.filter((s) => s.status !== 'SUCCESS');

              const defaultOpenNos: number[] =
                resultSteps.length <= 1
                  ? resultSteps.map((s) => s.stepNo)
                  : failedSteps.length > 0
                    ? [failedSteps[0].stepNo]
                    : resData.maxSummary?.confirmedStepNo != null
                      ? [resData.maxSummary.confirmedStepNo]
                      : resultSteps.length > 0
                        ? [resultSteps[0].stepNo]
                        : [];

              const openStepSet = new Set(expandedStepNos ?? defaultOpenNos);
              const allStepsExpanded =
                resultSteps.length > 0 && resultSteps.every((s) => openStepSet.has(s.stepNo));

              const toggleStepOpen = (stepNo: number) => {
                const next = new Set(openStepSet);
                if (next.has(stepNo)) next.delete(stepNo);
                else next.add(stepNo);
                setExpandedStepNos(Array.from(next));
              };

              const worstErrorPct = executedSteps.reduce((acc, s) => {
                const stepMax = (s.ops || []).reduce(
                  (m: number, o: any) => Math.max(m, Number(o.errorPct) || 0),
                  0
                );
                return Math.max(acc, stepMax);
              }, 0);

              const peakGenCpu = Math.max(
                0,
                ...executedSteps.map((s) => Number(s.genCpuMax) || 0),
                ...resultGens.map((g) => Number(g.cpuMax) || 0)
              );

              const peakActualRps = executedSteps.reduce((acc, s) => {
                const sumAct = (s.ops || []).reduce((a: number, o: any) => a + (Number(o.actualRate) || 0), 0);
                return Math.max(acc, sumAct);
              }, 0);

              const peakTargetRps = executedSteps.reduce((acc, s) => {
                const sumPlan = (s.ops || []).reduce((a: number, o: any) => a + (Number(o.targetRate) || 0), 0);
                return Math.max(acc, sumPlan);
              }, 0);

              const isCustomReportSetup = Boolean(
                repCustomTitle.trim() ||
                  Object.values(repSections).some((v) => v === false) ||
                  repExcludedCharts.length > 0
              );

              return (
                <>
                  {!resData.terminal && !resData.livePreview && (
                    <div className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600">
                      Тест ещё не запущен — предварительные итоги по ступеням появятся сразу после начала нагрузки.
                    </div>
                  )}

                  {resData.livePreview && (
                    <div className="rounded-xl border border-sky-200 bg-sky-50 px-4 py-2.5 flex flex-wrap items-center gap-3 text-xs text-sky-950">
                      <span className="h-2.5 w-2.5 rounded-full bg-indigo-600 animate-pulse shrink-0" />
                      <div className="flex-1">
                        <strong>Предварительные итоги</strong> — тест ещё выполняется
                        {resData.data?.computedAt ? ` (обновлено в ${fmtTimeOnly(resData.data.computedAt)})` : ''}.
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

                  {/* Единая лаконичная шапка: переключатель режимов просмотра + экспорт отчёта */}
                  {(resData.terminal || resData.livePreview) && (
                    <Card className="p-2.5 flex flex-wrap items-center justify-between gap-2.5">
                      <div className="inline-flex flex-wrap items-center rounded-lg border border-slate-200 bg-slate-50 p-0.5 gap-0.5">
                        <button
                          type="button"
                          onClick={() => setResultsSubTab('summary')}
                          className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors cursor-pointer ${
                            resultsSubTab === 'summary'
                              ? 'bg-indigo-600 text-white shadow-2xs'
                              : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
                          }`}
                        >
                          <span>Сводка и ступени</span>
                          {resultSteps.length > 0 && (
                            <span className="opacity-75 font-mono text-[10px]">{resultSteps.length}</span>
                          )}
                        </button>
                        <button
                          type="button"
                          onClick={() => setResultsSubTab('charts')}
                          className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors cursor-pointer ${
                            resultsSubTab === 'charts'
                              ? 'bg-indigo-600 text-white shadow-2xs'
                              : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
                          }`}
                        >
                          <BarChart3 className="h-3.5 w-3.5" />
                          <span>Графики отчёта</span>
                          <span className="opacity-75 font-mono text-[10px]">{allReportCharts.length}</span>
                        </button>
                        {resData.terminal && resultGens.length > 0 && (
                          <button
                            type="button"
                            onClick={() => setResultsSubTab('generators')}
                            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors cursor-pointer ${
                              resultsSubTab === 'generators'
                                ? 'bg-indigo-600 text-white shadow-2xs'
                                : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
                            }`}
                          >
                            <Cpu className="h-3.5 w-3.5" />
                            <span>Генераторы</span>
                            <span className="opacity-75 font-mono text-[10px]">{resultGens.length}</span>
                          </button>
                        )}
                      </div>

                      {resData.terminal && (
                        <div className="flex flex-wrap items-center gap-2">
                          {fetchingResults && !refreshingResults && (
                            <span className="text-[11px] text-indigo-600 animate-pulse">Обновление…</span>
                          )}

                          <Button
                            variant="primary"
                            size="sm"
                            onClick={() => handleExportAction(buildReportHref(`/runs/${run.id}/report.html`), 'preview')}
                          >
                            <Eye className="h-3.5 w-3.5" />
                            Предпросмотр
                          </Button>

                          <div className="inline-flex rounded-lg border border-slate-300 bg-white overflow-hidden text-xs">
                            <button
                              type="button"
                              onClick={() => handleExportAction(buildReportHref(`/runs/${run.id}/report.pdf`), 'download')}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 font-medium text-slate-700 hover:bg-slate-100 border-r border-slate-200 cursor-pointer"
                              title="Скачать PDF-отчёт (A4 альбомный)"
                            >
                              <FileText className="h-3.5 w-3.5 text-indigo-600" />
                              PDF
                            </button>
                            <button
                              type="button"
                              onClick={() => handleExportAction(buildReportHref(`/runs/${run.id}/report.docx`), 'download')}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 font-medium text-slate-700 hover:bg-slate-100 border-r border-slate-200 cursor-pointer"
                              title="Скачать редактируемый документ Word (.docx)"
                            >
                              <Download className="h-3.5 w-3.5 text-indigo-600" />
                              Word
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                handleExportAction(buildReportHref(`/runs/${run.id}/report.html`, 'download=true'), 'download')
                              }
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 font-medium text-slate-700 hover:bg-slate-100 cursor-pointer"
                              title="Скачать автономный HTML-файл"
                            >
                              <FileCode className="h-3.5 w-3.5 text-indigo-600" />
                              HTML
                            </button>
                          </div>

                          <Button variant="outline" size="sm" onClick={() => setReportSettingsOpen(true)}>
                            <SlidersHorizontal className="h-3.5 w-3.5" />
                            Настройки
                            {isCustomReportSetup && (
                              <span className="h-2 w-2 rounded-full bg-indigo-500" title="Заданы пользовательские настройки отчёта" />
                            )}
                          </Button>

                          <Button
                            variant="outline"
                            size="icon"
                            loading={refreshingResults}
                            onClick={handleManualRefreshResults}
                            title="Пересчитать итоги и графики из Prometheus"
                          >
                            <RefreshCw className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      )}
                    </Card>
                  )}

                  {!resData.data ? (
                    <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-xs text-amber-900">
                      {resData.livePreview
                        ? 'Данные по ступеням ещё собираются. Нажмите «Обновить» через несколько секунд.'
                        : 'Итоги ещё не рассчитаны (метрики поступают в Prometheus с задержкой). Нажмите кнопку пересчёта через несколько секунд.'}
                    </div>
                  ) : (
                    <>
                      {!resData.data.prometheusOk && resData.data.error && (
                        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-900">
                          {resData.data.error}
                        </div>
                      )}

                      {/* ==================== Подвкладка 1: СВОДКА И СТУПЕНИ ==================== */}
                      {resultsSubTab === 'summary' && (
                        <div className="space-y-4">
                          {/* 4 ключевые KPI-плитки прогона */}
                          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                            <Card className="p-3.5 flex flex-col justify-between">
                              <div className="text-[11px] font-medium text-slate-500">Итоговая оценка</div>
                              <div className="mt-1.5 flex items-center gap-2">
                                {conclusion ? (
                                  <Badge variant={conclusionVariant(conclusion)} className="text-xs px-2.5 py-1">
                                    {conclusion}
                                  </Badge>
                                ) : failedSteps.length === 0 && passedSteps.length > 0 ? (
                                  <Badge variant="success" className="text-xs px-2.5 py-1">
                                    SLA выполнен
                                  </Badge>
                                ) : failedSteps.length > 0 ? (
                                  <Badge variant="danger" className="text-xs px-2.5 py-1">
                                    Есть отклонения SLA
                                  </Badge>
                                ) : (
                                  <Badge variant="default" className="text-xs px-2.5 py-1">
                                    Без оценки
                                  </Badge>
                                )}
                              </div>
                              <div className="text-[11px] text-slate-400 mt-1.5 truncate">
                                {run.stopReason ? `Стоп: ${run.stopReason}` : conclusion ? 'Вердикт инженера' : 'Автооценка по порогам'}
                              </div>
                            </Card>

                            <Card className="p-3.5 flex flex-col justify-between">
                              <div className="text-[11px] font-medium text-slate-500">
                                {resData.maxSummary ? 'Поиск максимума' : 'Интенсивность (пик)'}
                              </div>
                              <div className="mt-1 text-sm font-bold text-slate-900 leading-snug">
                                {resData.maxSummary
                                  ? resData.maxSummary.headline
                                  : `${fmtNum(peakActualRps, 1)} оп/с`}
                              </div>
                              <div className="text-[11px] text-slate-400 mt-1 truncate">
                                {resData.maxSummary?.detail || `план до ${fmtNum(peakTargetRps, 1)} оп/с`}
                              </div>
                            </Card>

                            <Card className="p-3.5 flex flex-col justify-between">
                              <div className="flex items-center justify-between text-[11px] font-medium text-slate-500">
                                <span>Ступени нагрузки</span>
                                <span className="font-mono">
                                  {passedSteps.length}/{executedSteps.length || resultSteps.length} ок
                                </span>
                              </div>
                              <div className="mt-1.5 text-sm font-bold text-slate-900">
                                {failedSteps.length > 0
                                  ? `Нарушения на ${failedSteps.length} ступ.`
                                  : passedSteps.length > 0
                                    ? 'Все ступени в норме'
                                    : 'Нет завершённых ступеней'}
                              </div>
                              {resultSteps.length > 0 && (
                                <div className="mt-2 flex items-center gap-1">
                                  {resultSteps.map((s: any) => {
                                    const barColor =
                                      s.status === 'SUCCESS'
                                        ? 'bg-emerald-500'
                                        : s.status === 'NOT_RUN'
                                          ? 'bg-slate-300'
                                          : 'bg-rose-500';
                                    return (
                                      <button
                                        key={s.stepNo}
                                        type="button"
                                        title={`Ступень ${s.stepNo} (${fmtNum(s.levelPct, 0)}%): ${stepStatusMeta(s.status).label}`}
                                        onClick={() => {
                                          if (!openStepSet.has(s.stepNo)) toggleStepOpen(s.stepNo);
                                          document.getElementById(`step-${s.stepNo}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                                        }}
                                        className={`h-2 flex-1 rounded-full ${barColor} hover:opacity-80 cursor-pointer`}
                                      />
                                    );
                                  })}
                                </div>
                              )}
                            </Card>

                            <Card className="p-3.5 flex flex-col justify-between">
                              <div className="text-[11px] font-medium text-slate-500">Ошибки и ресурсы</div>
                              <div className="mt-1 text-sm font-bold text-slate-900 font-mono">
                                ош. до {fmtNum(worstErrorPct, 2)}%
                              </div>
                              <div className="text-[11px] text-slate-400 mt-1">
                                CPU генераторов макс.: <b className={peakGenCpu >= 80 ? 'text-rose-600' : 'text-slate-700'}>{fmtNum(peakGenCpu, 0)}%</b>
                              </div>
                            </Card>
                          </div>

                          {/* Лаконичная карточка «Вывод инженера» (чтение по умолчанию, форма раскрывается по кнопке) */}
                          {resData.terminal && (
                            <Card className={repSections.summary === false ? 'opacity-65 border-dashed' : ''}>
                              <CardHeader className="py-2.5">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="text-sm font-semibold text-slate-900">Вывод инженера</span>
                                  {conclusion && <Badge variant={conclusionVariant(conclusion)}>{conclusion}</Badge>}
                                  {repSections.summary === false && (
                                    <Badge variant="default">скрыто из экспорта</Badge>
                                  )}
                                  {isReportDirty && (
                                    <Badge variant="warning">● не сохранено</Badge>
                                  )}
                                </div>
                                <div className="flex flex-wrap items-center gap-1.5">
                                  <Button
                                    variant="outline"
                                    size="xs"
                                    onClick={handleGenerateDraft}
                                    title="Автоматически собрать структурированный вывод по ступеням, SLA и генераторам"
                                  >
                                    <Sparkles className="h-3.5 w-3.5 text-indigo-600" />
                                    Автовывод
                                  </Button>
                                  <Button
                                    variant={editingConclusion ? 'secondary' : 'outline'}
                                    size="xs"
                                    onClick={() => setEditingConclusion((v) => !v)}
                                  >
                                    <Pencil className="h-3.5 w-3.5" />
                                    {editingConclusion ? 'Свернуть редактор' : 'Редактировать'}
                                  </Button>
                                </div>
                              </CardHeader>

                              <CardBody className="space-y-3">
                                {!editingConclusion ? (
                                  comment.trim() ? (
                                    <div className="text-xs text-slate-800 whitespace-pre-line leading-relaxed">
                                      {comment}
                                    </div>
                                  ) : (
                                    <div className="text-xs text-slate-400">
                                      Комментарий инженера пока не заполнен — нажмите «Автовывод» для генерации резюме по метрикам или «Редактировать».
                                    </div>
                                  )
                                ) : (
                                  <div className="space-y-3">
                                    <div className="flex flex-wrap items-center gap-1.5">
                                      {(
                                        [
                                          { val: 'Нагрузка выдерживается', dot: 'bg-emerald-500' },
                                          { val: 'Есть нарушения SLA', dot: 'bg-amber-500' },
                                          { val: 'Деградация', dot: 'bg-rose-500' },
                                          { val: 'Результат недостоверен', dot: 'bg-slate-400' },
                                        ] as const
                                      ).map((opt) => {
                                        const selected = conclusion === opt.val;
                                        return (
                                          <button
                                            key={opt.val}
                                            type="button"
                                            onClick={() => setConclusion(selected ? '' : opt.val)}
                                            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-medium transition-colors cursor-pointer ${
                                              selected
                                                ? 'border-indigo-500 bg-indigo-50 text-indigo-900 font-semibold ring-1 ring-indigo-500/40'
                                                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                                            }`}
                                          >
                                            <span className={`h-2 w-2 rounded-full ${opt.dot}`} />
                                            {opt.val}
                                          </button>
                                        );
                                      })}
                                      <Input
                                        value={
                                          [
                                            'Нагрузка выдерживается',
                                            'Есть нарушения SLA',
                                            'Деградация',
                                            'Результат недостоверен',
                                          ].includes(conclusion)
                                            ? ''
                                            : conclusion
                                        }
                                        onChange={(e) => setConclusion(e.target.value)}
                                        placeholder="Или свой вердикт…"
                                        className="h-7 w-44 text-xs"
                                      />
                                    </div>

                                    <Textarea
                                      ref={commentTaRef}
                                      rows={5}
                                      value={comment}
                                      onChange={(e) => setComment(e.target.value)}
                                      onKeyDown={(e) => {
                                        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                                          e.preventDefault();
                                          saveConclusionMut.mutate(false);
                                        }
                                      }}
                                      placeholder="Инженерное резюме: подтверждённый предел RPS, узкие места и рекомендации…"
                                      className="text-xs leading-relaxed"
                                    />

                                    <div className="flex items-center justify-between gap-2">
                                      <span className="text-[11px] text-slate-400">Ctrl+Enter — сохранить</span>
                                      <div className="flex items-center gap-2">
                                        {comment.trim() && (
                                          <Button
                                            variant="ghost"
                                            size="xs"
                                            onClick={() => setComment('')}
                                          >
                                            <Eraser className="h-3.5 w-3.5" />
                                            Очистить
                                          </Button>
                                        )}
                                        <Button
                                          size="sm"
                                          loading={saveConclusionMut.isPending}
                                          onClick={() => saveConclusionMut.mutate(false)}
                                        >
                                          <Check className="h-3.5 w-3.5" />
                                          Сохранить вывод
                                        </Button>
                                      </div>
                                    </div>
                                  </div>
                                )}
                              </CardBody>
                            </Card>
                          )}

                          {/* Сводная таблица и аккордеоны ступеней */}
                          {(resData.terminal || resData.data.prometheusOk) && resultSteps.length > 0 && (
                            <div className="space-y-3">
                              {resultSteps.length > 1 && (
                                <Card className="overflow-hidden">
                                  <CardHeader className="py-2.5">
                                    <span className="text-xs font-semibold text-slate-900">Сводная матрица ступеней</span>
                                    <span className="text-[11px] text-slate-400">клик по строке раскрывает детали ступени</span>
                                  </CardHeader>
                                  <div className="overflow-x-auto">
                                    <table className="w-full text-xs">
                                      <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                                        <tr>
                                          <th className="px-3.5 py-2 text-left font-medium">Ступень</th>
                                          <th className="px-3 py-2 text-right font-medium">Уровень</th>
                                          <th className="px-3 py-2 text-left font-medium">Окно</th>
                                          <th className="px-3 py-2 text-right font-medium">План, оп/с</th>
                                          <th className="px-3 py-2 text-right font-medium">Факт, оп/с</th>
                                          <th className="px-3 py-2 text-left font-medium">Причины / SLA</th>
                                          <th className="px-3 py-2 text-right font-medium">Ошибки, %</th>
                                          <th className="px-3.5 py-2 text-left font-medium">Статус</th>
                                        </tr>
                                      </thead>
                                      <tbody className="divide-y divide-slate-100">
                                        {resultSteps.map((s: any) => {
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
                                              onClick={() => {
                                                if (!openStepSet.has(s.stepNo)) toggleStepOpen(s.stepNo);
                                                document
                                                  .getElementById(`step-${s.stepNo}`)
                                                  ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                                              }}
                                              className={`cursor-pointer ${
                                                s.status === 'FAILED' || s.status === 'UNRELIABLE'
                                                  ? 'bg-rose-50/50'
                                                  : isMax
                                                    ? 'bg-emerald-50/60'
                                                    : ''
                                              }`}
                                            >
                                              <td className="px-3.5 py-2 whitespace-nowrap font-semibold text-indigo-600">
                                                Ступень {s.stepNo}
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
                                              <td className="px-3 py-2 text-slate-600 max-w-xs truncate" title={s.reasons?.join(' · ')}>
                                                {s.reasons?.length > 0 ? s.reasons.join(' · ') : 'в норме'}
                                              </td>
                                              <td className="px-3 py-2 text-right font-mono">{fmtNum(maxErr, 2)}</td>
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
                              )}

                              <div className="flex items-center justify-between pt-1">
                                <span className="text-sm font-semibold text-slate-900">
                                  Детализация по операциям ({resultSteps.length})
                                </span>
                                {resultSteps.length > 1 && (
                                  <Button
                                    variant="ghost"
                                    size="xs"
                                    onClick={() =>
                                      setExpandedStepNos(
                                        allStepsExpanded ? [] : resultSteps.map((s) => s.stepNo)
                                      )
                                    }
                                  >
                                    {allStepsExpanded ? 'Свернуть все ступени' : 'Развернуть все ступени'}
                                  </Button>
                                )}
                              </div>

                              <div className="space-y-2.5">
                                {resultSteps.map((s: any) => {
                                  const sm = stepStatusMeta(s.status);
                                  const isOpen = openStepSet.has(s.stepNo);
                                  const actSum = (s.ops || []).reduce(
                                    (a: number, o: any) => a + (o.actualRate || 0),
                                    0
                                  );
                                  const planSum = (s.ops || []).reduce(
                                    (a: number, o: any) => a + (o.targetRate || 0),
                                    0
                                  );
                                  return (
                                    <Card key={s.stepNo} id={`step-${s.stepNo}`} className="overflow-hidden scroll-mt-16">
                                      <button
                                        type="button"
                                        onClick={() => toggleStepOpen(s.stepNo)}
                                        className="w-full flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 text-left hover:bg-slate-50/80 transition-colors cursor-pointer"
                                      >
                                        <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-slate-900 min-w-0">
                                          {isOpen ? (
                                            <ChevronDown className="h-4 w-4 text-slate-400 shrink-0" />
                                          ) : (
                                            <ChevronRight className="h-4 w-4 text-slate-400 shrink-0" />
                                          )}
                                          <span>
                                            Ступень {s.stepNo} — {fmtNum(s.levelPct, 1)}%
                                          </span>
                                          <span
                                            className={`inline-flex rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${sm.cls}`}
                                          >
                                            {sm.label}
                                          </span>
                                          {!isOpen && s.reasons?.length > 0 && (
                                            <span className="text-xs font-normal text-rose-600 truncate max-w-md">
                                              · {s.reasons[0]}
                                            </span>
                                          )}
                                        </div>

                                        {s.status !== 'NOT_RUN' && (
                                          <div className="flex items-center gap-3 text-xs text-slate-500 font-mono">
                                            <span>
                                              {fmtNum(actSum, 1)} / {fmtNum(planSum, 1)} оп/с
                                            </span>
                                            <span>
                                              {fmtTimeOnly(s.windowStart)} – {fmtTimeOnly(s.windowEnd)}
                                            </span>
                                            {s.genCpuMax != null && (
                                              <span className={s.genOverloaded ? 'text-rose-600 font-semibold' : ''}>
                                                CPU {fmtNum(s.genCpuMax, 0)}%
                                              </span>
                                            )}
                                          </div>
                                        )}
                                      </button>

                                      {isOpen && (
                                        <div className="border-t border-slate-100">
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
                                                      Метрика Prometheus
                                                    </th>
                                                    <th className="px-3 py-2 text-right font-medium">Факт (худшее)</th>
                                                    <th className="px-3 py-2 text-right font-medium">Порог SLA</th>
                                                    <th className="px-3 py-2 text-left font-medium">Серия / хост</th>
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
                                        </div>
                                      )}
                                    </Card>
                                  );
                                })}
                              </div>
                            </div>
                          )}
                        </div>
                      )}

                      {/* ==================== Подвкладка 2: ГРАФИКИ ОТЧЁТА ==================== */}
                      {resultsSubTab === 'charts' && (
                        <div className="space-y-3">
                          <Card className="p-3 flex flex-wrap items-center justify-between gap-2.5">
                            <div className="flex flex-wrap items-center gap-1.5">
                              {(
                                [
                                  ['all', 'Все', allReportCharts.length],
                                  [
                                    'k6',
                                    'k6 (ряды)',
                                    allReportCharts.filter((c) => c.category === 'k6').length,
                                  ],
                                  [
                                    'steps',
                                    'По ступеням',
                                    allReportCharts.filter((c) => c.category === 'steps').length,
                                  ],
                                  [
                                    'custom',
                                    'Скрипт',
                                    allReportCharts.filter((c) => (c.category || 'custom') === 'custom').length,
                                  ],
                                  [
                                    'sla',
                                    'SLA Prometheus',
                                    allReportCharts.filter((c) => c.category === 'sla').length,
                                  ],
                                ] as const
                              )
                                .filter(([k, , cnt]) => k === 'all' || cnt > 0)
                                .map(([catKey, catLabel, cnt]) => (
                                  <button
                                    key={catKey}
                                    type="button"
                                    onClick={() => setChartCatFilter(catKey)}
                                    className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs font-medium cursor-pointer transition-colors ${
                                      chartCatFilter === catKey
                                        ? 'bg-indigo-600 text-white'
                                        : 'bg-slate-50 border border-slate-200 text-slate-600 hover:bg-slate-100'
                                    }`}
                                  >
                                    <span>{catLabel}</span>
                                    <span className="opacity-75 font-mono text-[10px]">{cnt}</span>
                                  </button>
                                ))}

                              {resData.terminal && repExcludedCharts.length > 0 && (
                                <Button
                                  variant="ghost"
                                  size="xs"
                                  onClick={() => {
                                    setRepExcludedCharts([]);
                                    saveReportConfigQuick(repSections, []);
                                  }}
                                >
                                  Вернуть скрытые ({repExcludedCharts.length})
                                </Button>
                              )}
                            </div>

                            <div className="flex flex-wrap items-center gap-1.5">
                              <Select
                                value={repTimeAxis}
                                onChange={(e) => setRepTimeAxis(e.target.value as any)}
                                className="w-auto h-7 text-xs"
                                title="Ось X на временных графиках"
                              >
                                <option value="">Ось X: по умолч.</option>
                                <option value="rel">Минуты от старта</option>
                                <option value="abs">Часы (абс.)</option>
                              </Select>
                              <Select
                                value={repWarmup}
                                onChange={(e) => setRepWarmup(e.target.value as any)}
                                className="w-auto h-7 text-xs"
                                title="Участок прогрева на графиках"
                              >
                                <option value="">Прогрев: по умолч.</option>
                                <option value="include">С начала теста</option>
                                <option value="skip">
                                  Без прогрева
                                  {resData.warmupSec > 0 ? ` (−${Math.round(resData.warmupSec / 6) / 10} мин)` : ''}
                                </option>
                              </Select>
                              <Select
                                value={repSortBy}
                                onChange={(e) => setRepSortBy(e.target.value as any)}
                                className="w-auto h-7 text-xs"
                                title="Сортировка рядов в легенде"
                              >
                                <option value="">Легенда: по умолч.</option>
                                <option value="NONE">Как в запросе</option>
                                <option value="MAX">По Max</option>
                                <option value="MEAN">По Mean</option>
                                <option value="LAST">По Last</option>
                              </Select>
                              {repSortBy && repSortBy !== 'NONE' && (
                                <Select
                                  value={repSortDir || 'DESC'}
                                  onChange={(e) => setRepSortDir(e.target.value as any)}
                                  className="w-auto h-7 text-xs"
                                >
                                  <option value="DESC">↓ убыв.</option>
                                  <option value="ASC">↑ возр.</option>
                                </Select>
                              )}
                              {run.scriptId != null && (
                                <Link to={`/scripts/${run.scriptId}/charts`}>
                                  <Button variant="outline" size="xs">
                                    Конструктор графиков →
                                  </Button>
                                </Link>
                              )}
                            </div>
                          </Card>

                          {allReportCharts.length === 0 ? (
                            <Card className="p-8 text-center text-xs text-slate-500">
                              Графики отчёта для этого запуска не построены.
                            </Card>
                          ) : (
                            <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
                              {filteredReportCharts.map((ch: any, idx: number) => {
                                const globalIdx = allReportCharts.indexOf(ch);
                                const isIncluded = !excludedSet.has(ch.title);
                                const catMeta =
                                  ch.category === 'k6'
                                    ? { label: 'k6', variant: 'info' as const }
                                    : ch.category === 'steps'
                                      ? { label: 'Ступени', variant: 'default' as const }
                                      : ch.category === 'sla'
                                        ? { label: 'SLA', variant: 'warning' as const }
                                        : { label: 'Скрипт', variant: 'success' as const };
                                return (
                                  <Card
                                    key={`${ch.category || 'c'}-${ch.title}-${idx}`}
                                    className={`overflow-hidden transition-opacity ${
                                      !isIncluded ? 'opacity-55 border-dashed' : ''
                                    }`}
                                  >
                                    <CardHeader className="py-2 gap-2">
                                      <div className="flex items-center gap-2 min-w-0">
                                        <Badge variant={catMeta.variant}>{catMeta.label}</Badge>
                                        <span className="font-semibold text-xs text-slate-900 truncate" title={ch.title}>
                                          {ch.title}
                                        </span>
                                      </div>
                                      <div className="flex items-center gap-1 shrink-0">
                                        {resData.terminal && (
                                          <button
                                            type="button"
                                            onClick={() => handleToggleChartIncluded(ch.title)}
                                            className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-medium border cursor-pointer ${
                                              isIncluded
                                                ? 'border-emerald-200 bg-emerald-50 text-emerald-800 hover:bg-emerald-100'
                                                : 'border-slate-200 bg-slate-50 text-slate-500 hover:bg-slate-100'
                                            }`}
                                          >
                                            {isIncluded ? (
                                              <>
                                                <Eye className="h-3 w-3" />В отчёте
                                              </>
                                            ) : (
                                              <>
                                                <EyeOff className="h-3 w-3" />
                                                Скрыт
                                              </>
                                            )}
                                          </button>
                                        )}
                                        <button
                                          type="button"
                                          onClick={() => downloadDataUriPng(ch.dataUri, ch.title)}
                                          title="Скачать PNG"
                                          className="inline-flex items-center justify-center h-6 w-6 rounded-md border border-slate-200 bg-slate-50 text-slate-600 hover:text-indigo-600 cursor-pointer"
                                        >
                                          <Download className="h-3 w-3" />
                                        </button>
                                        <button
                                          type="button"
                                          onClick={() => setLightboxIdx(globalIdx >= 0 ? globalIdx : 0)}
                                          title="Развернуть"
                                          className="inline-flex items-center justify-center h-6 w-6 rounded-md border border-slate-200 bg-slate-50 text-slate-600 hover:text-indigo-600 cursor-pointer"
                                        >
                                          <Maximize2 className="h-3 w-3" />
                                        </button>
                                      </div>
                                    </CardHeader>
                                    <CardBody className="p-2">
                                      <img
                                        src={ch.dataUri}
                                        alt={ch.title}
                                        onClick={() => setLightboxIdx(globalIdx >= 0 ? globalIdx : 0)}
                                        className="w-full h-auto rounded-lg cursor-zoom-in hover:ring-2 hover:ring-indigo-300 transition-shadow"
                                      />
                                    </CardBody>
                                  </Card>
                                );
                              })}
                            </div>
                          )}
                        </div>
                      )}

                      {/* ==================== Подвкладка 3: ГЕНЕРАТОРЫ (СПРАВОЧНО) ==================== */}
                      {resultsSubTab === 'generators' && resData.terminal && (
                        <Card className="overflow-hidden">
                          <CardHeader className="py-2.5">
                            <span className="text-xs font-semibold text-slate-900">
                              Суммарные показатели генераторов за весь тест
                            </span>
                            {repSections.generators === false && (
                              <Badge variant="default">раздел скрыт из экспорта</Badge>
                            )}
                          </CardHeader>
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
                                {resultGens.map((g: any, idx: number) => (
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
                      )}
                    </>
                  )}
                </>
              );
            })()
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

      {/* Полноэкранный просмотр графика отчёта (Lightbox) */}
      {lightboxIdx != null && allReportCharts[lightboxIdx] && (
        <div
          className="fixed inset-0 z-50 bg-slate-950/90 backdrop-blur-xs flex flex-col items-center justify-center p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget) setLightboxIdx(null);
          }}
        >
          <div className="w-full max-w-[96vw] flex items-center justify-between gap-3 text-white mb-3">
            <div className="flex items-center gap-2 min-w-0">
              <span className="text-xs font-mono text-slate-400">
                {lightboxIdx + 1} / {allReportCharts.length}
              </span>
              <span className="font-semibold text-sm truncate">{allReportCharts[lightboxIdx].title}</span>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              {allReportCharts.length > 1 && (
                <>
                  <Button
                    variant="outline"
                    size="xs"
                    className="bg-slate-900/80 border-slate-700 text-slate-200 hover:bg-slate-800"
                    onClick={() =>
                      setLightboxIdx((lightboxIdx - 1 + allReportCharts.length) % allReportCharts.length)
                    }
                  >
                    <ChevronLeft className="h-3.5 w-3.5" />
                    Пред.
                  </Button>
                  <Button
                    variant="outline"
                    size="xs"
                    className="bg-slate-900/80 border-slate-700 text-slate-200 hover:bg-slate-800"
                    onClick={() => setLightboxIdx((lightboxIdx + 1) % allReportCharts.length)}
                  >
                    След.
                    <ChevronRight className="h-3.5 w-3.5" />
                  </Button>
                </>
              )}
              <Button
                variant="outline"
                size="xs"
                className="bg-slate-900/80 border-slate-700 text-slate-200 hover:bg-slate-800"
                onClick={() =>
                  downloadDataUriPng(allReportCharts[lightboxIdx].dataUri, allReportCharts[lightboxIdx].title)
                }
              >
                <Download className="h-3.5 w-3.5" />
                PNG
              </Button>
              <button
                type="button"
                onClick={() => setLightboxIdx(null)}
                className="inline-flex items-center justify-center h-7 w-7 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 cursor-pointer"
                aria-label="Закрыть"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          </div>
          <img
            src={allReportCharts[lightboxIdx].dataUri}
            alt={allReportCharts[lightboxIdx].title}
            onClick={() => setLightboxIdx(null)}
            className="max-w-[96vw] max-h-[86vh] rounded-xl shadow-2xl cursor-zoom-out"
          />
        </div>
      )}

      {/* Настройки структуры и заголовка экспортируемого отчёта */}
      <Modal
        open={reportSettingsOpen}
        onClose={() => setReportSettingsOpen(false)}
        title={
          <span className="flex items-center gap-2">
            <LayoutList className="h-4 w-4 text-indigo-600" />
            Настройки экспорта отчёта
          </span>
        }
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setReportSettingsOpen(false)}>
              Закрыть
            </Button>
            <Button
              size="sm"
              loading={saveConclusionMut.isPending}
              onClick={async () => {
                await saveConclusionMut.mutateAsync(false);
                setReportSettingsOpen(false);
              }}
            >
              <Check className="h-3.5 w-3.5" />
              Сохранить настройки
            </Button>
          </>
        }
      >
        <div className="space-y-4 text-xs">
          <div>
            <label className="block font-medium text-slate-700 mb-1">
              Заголовок отчёта (для HTML, PDF и имени файла Word)
            </label>
            <div className="flex items-center gap-2">
              <Input
                value={repCustomTitle}
                onChange={(e) => setRepCustomTitle(e.target.value)}
                placeholder={resData?.defaultTitle || run.scriptName}
                className="h-8 text-xs"
              />
              {repCustomTitle.trim() && (
                <Button variant="ghost" size="xs" onClick={() => setRepCustomTitle('')}>
                  <RotateCcw className="h-3.5 w-3.5" />
                  Сбросить
                </Button>
              )}
            </div>
          </div>

          <div>
            <label className="block font-medium text-slate-700 mb-1.5">Разделы в HTML / PDF / Word</label>
            <div className="grid grid-cols-2 gap-2">
              {(
                [
                  ['summary', 'Итог и вывод инженера'],
                  ['config', 'Конфигурация запуска'],
                  ['env', 'Переменные запуска'],
                  ['steps', 'Ступени и SLA'],
                  [
                    'charts',
                    allReportCharts.length > 0
                      ? `Графики (${includedChartsCount}/${allReportCharts.length})`
                      : 'Графики отчёта',
                  ],
                  ['generators', 'Генераторы нагрузки'],
                ] as const
              ).map(([sKey, sLabel]) => {
                const active = repSections[sKey] !== false;
                return (
                  <label
                    key={sKey}
                    className={`flex items-center gap-2 rounded-lg border px-3 py-2 cursor-pointer transition-colors ${
                      active
                        ? 'border-indigo-300 bg-indigo-50/70 text-slate-900 font-medium'
                        : 'border-slate-200 bg-slate-50 text-slate-500'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={active}
                      onChange={() => handleToggleReportSection(sKey)}
                    />
                    <span>{sLabel}</span>
                  </label>
                );
              })}
            </div>
          </div>

          <div className="flex items-center justify-between pt-2 border-t border-slate-100">
            <span className="text-slate-500">Глобальная тема и размер PNG-графиков отчёта</span>
            <Link
              to="/settings"
              onClick={() => setReportSettingsOpen(false)}
              className="text-indigo-600 hover:underline font-medium"
            >
              Открыть настройки системы →
            </Link>
          </div>
        </div>
      </Modal>

      {/* Интерактивный предпросмотр печатного HTML-отчёта */}
      <Modal
        open={reportPreviewOpen}
        onClose={() => setReportPreviewOpen(false)}
        title={`Предпросмотр отчёта · ${repCustomTitle.trim() || resData?.defaultTitle || `Запуск #${run.id}`}`}
        maxWidth="max-w-7xl"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-slate-200">
            <span className="text-xs text-slate-500">
              Точный вид самодостаточного отчёта с учётом выбранных разделов, скрытых графиков и вывода инженера.
            </span>
            <div className="flex flex-wrap items-center gap-1.5">
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  const frame = document.getElementById('run-report-preview-frame') as HTMLIFrameElement | null;
                  frame?.contentWindow?.print();
                }}
              >
                <Printer className="h-3.5 w-3.5" />
                Печать
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => window.open(buildReportHref(`/runs/${run.id}/report.html`), '_blank', 'noopener')}
              >
                <ExternalLink className="h-3.5 w-3.5" />В новой вкладке
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() =>
                  handleExportAction(buildReportHref(`/runs/${run.id}/report.html`, 'download=true'), 'download')
                }
              >
                <FileCode className="h-3.5 w-3.5" />
                Скачать HTML
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => handleExportAction(buildReportHref(`/runs/${run.id}/report.pdf`), 'download')}
              >
                <FileText className="h-3.5 w-3.5" />
                PDF
              </Button>
              <Button
                variant="primary"
                size="xs"
                onClick={() => handleExportAction(buildReportHref(`/runs/${run.id}/report.docx`), 'download')}
              >
                <Download className="h-3.5 w-3.5" />
                Word (.docx)
              </Button>
            </div>
          </div>
          <iframe
            id="run-report-preview-frame"
            title="Предпросмотр отчёта"
            src={reportPreviewOpen ? buildReportHref(`/runs/${run.id}/report.html`) : 'about:blank'}
            className="w-full h-[74vh] rounded-lg border border-slate-200 bg-white"
          />
        </div>
      </Modal>
    </div>
  );
}
