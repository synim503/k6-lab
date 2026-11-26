import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Chart as ChartJS,
  CategoryScale,
  Filler,
  Legend,
  LinearScale,
  LineElement,
  PointElement,
  Title,
  Tooltip,
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import { apiFetch, apiUpload } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Modal, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { MonacoEditor } from '@/components/ui/MonacoEditor';
import { ScriptHead } from './ScriptHead';
import { fmtDurationSec, fmtShortDate, parseDurationToSec } from '@/lib/utils';
import {
  Activity,
  ArrowDownUp,
  CalendarPlus,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  Flame,
  FolderOpen,
  Layers,
  Pencil,
  Play,
  Plus,
  Save,
  Search,
  Send,
  ShieldAlert,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Filler, Legend);

const COLORS = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];

const EXECUTORS = [
  { value: 'constant-arrival-rate', label: 'constant-arrival-rate — постоянная интенсивность' },
  { value: 'ramping-arrival-rate', label: 'ramping-arrival-rate — интенсивность по стадиям' },
  { value: 'constant-vus', label: 'constant-vus — постоянное число VU' },
  { value: 'ramping-vus', label: 'ramping-vus — VU по стадиям' },
  { value: 'per-vu-iterations', label: 'per-vu-iterations — итерации на VU' },
  { value: 'shared-iterations', label: 'shared-iterations — общее число итераций' },
  { value: 'externally-controlled', label: 'externally-controlled — через REST API' },
];

const EXAMPLE_IMPORT_JSON = JSON.stringify(
  {
    profiles: [
      {
        name: 'Поиск максимума (5 ступеней)',
        generatorCount: 2,
        operations: [
          {
            name: 'createOrder',
            execFn: 'createOrder',
            enabled: true,
            executor: 'ramping-arrival-rate',
            scenario: {
              startRate: 0,
              timeUnit: '1s',
              preAllocatedVUs: 20,
              maxVUs: 150,
              warmup: { stages: 1 },
              stages: [
                { duration: '2m', target: 30 },
                { duration: '0s', target: 100 },
                { duration: '5m', target: 100 },
                { duration: '0s', target: 120 },
                { duration: '5m', target: 120 },
                { duration: '0s', target: 140 },
                { duration: '5m', target: 140 },
              ],
            },
            slaPercentile: 95,
            slaThresholdMs: 500,
            maxErrorPct: 1.0,
            failureSource: 'HTTP',
          },
        ],
        env: {
          BASE_URL: 'https://stand.example',
          API_TOKEN: '${secret:API_TOKEN}',
        },
        stop: {
          rateEnabled: true,
          minRatePct: 90,
          errorsEnabled: true,
          percentileEnabled: true,
          slaMultiplier: 1.5,
          consecutive: 4,
          windowSec: 60,
        },
      },
    ],
  },
  null,
  2
);

function defaultScenarioFor(exec: string) {
  switch (exec) {
    case 'constant-arrival-rate':
      return { rate: 50, timeUnit: '1s', duration: '5m', preAllocatedVUs: 20, maxVUs: 100 };
    case 'ramping-arrival-rate':
      return {
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: 20,
        maxVUs: 100,
        stages: [
          { duration: '1m', target: 50 },
          { duration: '5m', target: 50 },
        ],
      };
    case 'constant-vus':
      return { vus: 10, duration: '5m' };
    case 'ramping-vus':
      return {
        startVUs: 0,
        stages: [
          { duration: '1m', target: 20 },
          { duration: '5m', target: 20 },
        ],
      };
    case 'per-vu-iterations':
      return { vus: 10, iterations: 20, maxDuration: '10m' };
    case 'shared-iterations':
      return { vus: 10, iterations: 200, maxDuration: '10m' };
    case 'externally-controlled':
      return { vus: 10, maxVUs: 50, duration: '10m' };
    default:
      return {};
  }
}

export function LoadStudioPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const profileParam = searchParams.get('profile');

  const { data: loadState, isLoading } = useQuery({
    queryKey: ['script-load', id, profileParam],
    queryFn: () =>
      apiFetch(`/api/v1/scripts/${id}/load${profileParam ? `?profile=${encodeURIComponent(profileParam)}` : ''}`),
  });

  // Editable form state
  const [ops, setOps] = useState<any[]>([]);
  const [generatorCount, setGeneratorCount] = useState<number>(1);
  const [envRows, setEnvRows] = useState<{ k: string; v: string }[]>([]);
  const [stop, setStop] = useState<any>({});
  const [dataFiles, setDataFiles] = useState<any[]>([]);
  const [profileName, setProfileName] = useState('');
  const [warmupOn, setWarmupOn] = useState(false);
  const [warmupDur, setWarmupDur] = useState('2m');
  const [saving, setSaving] = useState(false);

  // Base push checkboxes
  const [pushOpts, setPushOpts] = useState({
    env: true,
    stop: true,
    sla: false,
    warmup: false,
    data: false,
    generators: false,
  });

  // UI controls
  const [collapsedOps, setCollapsedOps] = useState<Record<number, boolean>>({});
  const [opSearch, setOpSearch] = useState('');
  const [onlyEnabled, setOnlyEnabled] = useState(false);
  const [chartStacked, setChartStacked] = useState(true);
  const [profilesModalOpen, setProfilesModalOpen] = useState(false);
  const [profSearch, setProfSearch] = useState('');
  const [selectedProfIds, setSelectedProfIds] = useState<Set<number>>(new Set());
  const [lastClickedProfId, setLastClickedProfId] = useState<number | null>(null);
  const [renameModal, setRenameModal] = useState<{ id: number; name: string } | null>(null);

  // Import/export modal
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importJson, setImportJson] = useState('');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importSeq, setImportSeq] = useState(false);
  const [importGap, setImportGap] = useState(5);
  const [importing, setImporting] = useState(false);

  // Advanced k6 JSON config & requirements
  const [advOpen, setAdvOpen] = useState(false);
  const [editedConfig, setEditedConfig] = useState('');
  const [reqInfo, setReqInfo] = useState<{ maxVus: string; totalDuration: string } | null>(null);
  const [reqLoading, setReqLoading] = useState(false);

  // Hydrate local state whenever server state loads or profile changes
  const hydrateFromServer = (st: any) => {
    const f = st.form || {};
    const nextOps = (f.ops || []).map((o: any) => ({
      ...o,
      scenario: o.scenario && Object.keys(o.scenario).length > 0 ? o.scenario : defaultScenarioFor(o.executor || 'ramping-arrival-rate'),
    }));
    setOps(nextOps);
    setGeneratorCount(f.generatorCount || st.script?.generatorCount || 1);
    const entries = Object.entries(st.env || {}).map(([k, v]) => ({ k, v: String(v ?? '') }));
    setEnvRows(entries.length > 0 ? entries : [{ k: '', v: '' }]);
    setStop({
      rateEnabled: true,
      minRatePct: 90,
      droppedEnabled: false,
      maxDroppedPct: 1,
      errorsEnabled: true,
      percentileEnabled: true,
      slaMultiplier: 1.5,
      consecutive: 4,
      windowSec: 60,
      extraStepOnMaxSearch: true,
      customChecks: [],
      ...(f.stop || {}),
    });
    setDataFiles(f.dataFiles || []);
    setProfileName(st.loadedProfileName || '');

    // Detect warmup
    const bw = st.baseWarmupDuration || '';
    const firstWarmOp = nextOps.find((o: any) => o.scenario?.warmup);
    if (bw) {
      setWarmupOn(true);
      setWarmupDur(bw);
    } else if (firstWarmOp?.scenario?.warmup?.duration) {
      setWarmupOn(true);
      setWarmupDur(firstWarmOp.scenario.warmup.duration);
    } else if (firstWarmOp?.scenario?.warmup?.stages && firstWarmOp.scenario.stages?.[0]?.duration) {
      setWarmupOn(true);
      setWarmupDur(firstWarmOp.scenario.stages[0].duration);
    } else {
      setWarmupOn(false);
    }

    setEditedConfig(st.plan?.configJson || '');
    setReqInfo(null);
    if (nextOps.length > 4) {
      const col: Record<number, boolean> = {};
      nextOps.forEach((_: any, idx: number) => {
        col[idx] = true;
      });
      setCollapsedOps(col);
    } else {
      setCollapsedOps({});
    }
  };

  useEffect(() => {
    if (loadState) hydrateFromServer(loadState);
  }, [loadState]);

  const buildPayloadForm = () => {
    const envObj: Record<string, string> = {};
    envRows.forEach((r) => {
      if (r.k.trim()) envObj[r.k.trim()] = r.v;
    });

    const preparedOps = ops.map((o) => {
      const sc = { ...(o.scenario || {}) };
      if (warmupOn && warmupDur.trim()) {
        if (String(o.executor).startsWith('ramping')) {
          sc.warmup = { stages: 1 };
        } else {
          sc.warmup = { duration: warmupDur.trim() };
        }
      } else {
        delete sc.warmup;
      }
      return { ...o, scenario: sc };
    });

    return {
      form: {
        ops: preparedOps,
        generatorCount,
        dataFiles,
        stop: {
          ...stop,
          customChecks: (stop.customChecks || []).filter((c: any) => c.name?.trim() || c.promql?.trim()),
        },
      },
      env: envObj,
      baseWarmupDuration: warmupOn ? warmupDur.trim() : '',
    };
  };

  const handleSaveBase = async () => {
    setSaving(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/load`, {
        method: 'POST',
        body: buildPayloadForm(),
      });
      toast.success(res.message || 'Нагрузка сохранена');
      if (profileParam) {
        setSearchParams({});
      } else {
        qc.setQueryData(['script-load', id, profileParam], res);
      }
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveProfile = async () => {
    const name = profileName.trim();
    if (!name) {
      toast.error('Введите название профиля');
      return;
    }
    setSaving(true);
    try {
      const payload = buildPayloadForm();
      const res = await apiFetch(`/api/v1/scripts/${id}/load/profile`, {
        method: 'POST',
        body: {
          profileName: name,
          profileId: loadState?.loadedProfileId ?? null,
          ...payload,
        },
      });
      toast.success(res.message || 'Профиль сохранён');
      const pidStr = String(res.savedProfileId);
      setSearchParams({ profile: pidStr });
      qc.setQueryData(['script-load', id, pidStr], res);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  const handleApplyBaseToProfiles = async () => {
    if (!Object.values(pushOpts).some(Boolean)) {
      toast.warning('Отметьте хотя бы один раздел для отправки в профили');
      return;
    }
    setSaving(true);
    try {
      const payload = buildPayloadForm();
      const res = await apiFetch(`/api/v1/scripts/${id}/load/apply-base`, {
        method: 'POST',
        body: {
          ...payload,
          baseOverrideEnv: pushOpts.env,
          baseOverrideStop: pushOpts.stop,
          baseOverrideSla: pushOpts.sla,
          baseOverrideWarmup: pushOpts.warmup,
          baseOverrideData: pushOpts.data,
          baseOverrideGenerators: pushOpts.generators,
        },
      });
      toast.success(res.message);
      qc.setQueryData(['script-load', id, profileParam], res);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  // Live Chart & KPI computation
  const kpiAndChart = useMemo(() => {
    const active = ops.filter((o) => o.enabled);
    let peakRate = 0;
    let maxDurSec = 0;
    let totalMaxVus = 0;

    const datasets: any[] = [];
    const timePoints = new Set<number>([0]);

    const seriesRaw = active.map((o, idx) => {
      const ex = o.executor || 'ramping-arrival-rate';
      const sc = o.scenario || {};
      const startSec = parseDurationToSec(sc.startTime);
      const pts: { t: number; v: number }[] = [];

      if (ex === 'constant-arrival-rate') {
        const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
        const r = (Number(sc.rate) || 0) / tu;
        const d = parseDurationToSec(sc.duration || '5m');
        peakRate += r;
        maxDurSec = Math.max(maxDurSec, startSec + d);
        totalMaxVus += Number(sc.maxVUs || sc.preAllocatedVUs || 0);
        pts.push({ t: startSec, v: r }, { t: startSec + d, v: r });
      } else if (ex === 'ramping-arrival-rate') {
        const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
        let curT = startSec;
        let curR = (Number(sc.startRate) || 0) / tu;
        let opPeak = curR;
        pts.push({ t: curT, v: curR });
        (sc.stages || []).forEach((st: any) => {
          const d = parseDurationToSec(st.duration);
          const target = (Number(st.target) || 0) / tu;
          curT += d;
          pts.push({ t: curT, v: target });
          if (target > opPeak) opPeak = target;
        });
        peakRate += opPeak;
        maxDurSec = Math.max(maxDurSec, curT);
        totalMaxVus += Number(sc.maxVUs || sc.preAllocatedVUs || 0);
      } else if (ex === 'ramping-vus') {
        let curT = startSec;
        let curV = Number(sc.startVUs) || 0;
        let opPeakV = curV;
        pts.push({ t: curT, v: curV });
        (sc.stages || []).forEach((st: any) => {
          const d = parseDurationToSec(st.duration);
          const target = Number(st.target) || 0;
          curT += d;
          pts.push({ t: curT, v: target });
          if (target > opPeakV) opPeakV = target;
        });
        totalMaxVus += opPeakV;
        maxDurSec = Math.max(maxDurSec, curT);
      } else {
        const v = Number(sc.vus) || 0;
        const d = parseDurationToSec(sc.duration || sc.maxDuration || '5m');
        totalMaxVus += Number(sc.maxVUs || v);
        maxDurSec = Math.max(maxDurSec, startSec + d);
        pts.push({ t: startSec, v }, { t: startSec + d, v });
      }

      pts.forEach((p) => timePoints.add(Math.round((p.t / 60) * 100) / 100));
      return { name: o.name || o.execFn, color: COLORS[idx % COLORS.length], pts };
    });

    const sortedMins = Array.from(timePoints).sort((a, b) => a - b);
    seriesRaw.forEach((sr) => {
      datasets.push({
        label: sr.name,
        data: sr.pts.map((p) => ({ x: Number((p.t / 60).toFixed(2)), y: Number(p.v.toFixed(2)) })),
        borderColor: sr.color,
        backgroundColor: sr.color + '28',
        fill: chartStacked,
        tension: 0,
        pointRadius: 2,
      });
    });

    return {
      activeCount: active.length,
      peakRate: Math.round(peakRate * 10) / 10,
      maxDurSec,
      totalMaxVus,
      labels: sortedMins,
      datasets,
    };
  }, [ops, chartStacked]);

  if (isLoading || !loadState) return <Spinner label="Загрузка конструктора нагрузки…" />;

  const { script, loadedProfileId, loadedProfileName, profiles = [], secretNames = [], scriptCharts = [], staleOps = [], plan } = loadState;

  const filteredProfiles = profiles.filter((p: any) =>
    (p.name || '').toLowerCase().includes(profSearch.trim().toLowerCase())
  );

  const updateOp = (idx: number, patch: any) => {
    setOps((prev) => prev.map((item, i) => (i === idx ? { ...item, ...patch } : item)));
  };

  const updateScenario = (idx: number, patch: any) => {
    setOps((prev) =>
      prev.map((item, i) => (i === idx ? { ...item, scenario: { ...(item.scenario || {}), ...patch } } : item))
    );
  };

  return (
    <div className="space-y-4 pb-16">
      <ScriptHead
        script={script}
        rightActions={
          <Link
            to={
              loadedProfileId
                ? `/scripts/${script.id}/run?profile=${loadedProfileId}`
                : `/scripts/${script.id}/run`
            }
          >
            <Button variant="success" size="sm">
              <Play className="h-3.5 w-3.5 fill-current" />
              {loadedProfileName ? 'Запустить профиль' : 'Запустить'}
            </Button>
          </Link>
        }
      />

      {plan?.errors?.length > 0 && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-xs text-rose-800 space-y-1">
          {plan.errors.map((e: string, i: number) => (
            <div key={i}>• {e}</div>
          ))}
        </div>
      )}

      {staleOps.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900">
          В текущей версии скрипта отсутствуют функции: <code>{staleOps.join(', ')}</code> — они исключены из запуска.
        </div>
      )}

      {/* Верхняя панель профилей нагрузки */}
      <Card className="p-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setProfilesModalOpen(true)}
            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-slate-50 px-3 py-1.5 text-sm font-semibold text-slate-800 hover:bg-slate-100 cursor-pointer"
          >
            <Layers className="h-4 w-4 text-indigo-600" />
            <span>{loadedProfileName ? `Профиль: ${loadedProfileName}` : '★ Базовый профиль скрипта'}</span>
            <Badge variant="info">{profiles.length}</Badge>
            <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
          </button>

          <div className="flex items-center gap-1.5">
            <Input
              value={profileName}
              onChange={(e) => setProfileName(e.target.value)}
              placeholder="Название профиля…"
              className="h-8 text-xs w-52"
            />
            <Button variant="outline" size="sm" loading={saving} onClick={handleSaveProfile}>
              <Save className="h-3.5 w-3.5 text-emerald-600" />
              Сохранить профиль
            </Button>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 text-xs text-slate-600">
            <span>Генераторов:</span>
            <Input
              type="number"
              min={1}
              max={200}
              value={generatorCount}
              onChange={(e) => setGeneratorCount(Math.max(1, Number(e.target.value) || 1))}
              className="h-8 w-20 text-xs"
            />
          </div>
          <Button variant="outline" size="sm" onClick={() => setImportModalOpen(true)}>
            <ArrowDownUp className="h-3.5 w-3.5" />
            Импорт / экспорт
          </Button>
        </div>
      </Card>

      {/* Разовая отправка из базового профиля в остальные */}
      {loadedProfileId == null && profiles.length > 0 && (
        <Card className="p-3 bg-indigo-50/40 border-indigo-200/70">
          <details className="group">
            <summary className="flex flex-wrap items-center gap-2 cursor-pointer list-none text-xs">
              <ChevronRight className="h-4 w-4 text-indigo-600 transition-transform group-open:rotate-90" />
              <Badge variant="info">
                <Send className="h-3 w-3" />
                Отправка в остальные профили ({profiles.length})
              </Badge>
              <span className="text-slate-600">
                Разово перенести выбранные разделы из базового профиля во все профили (без постоянной привязки)
              </span>
            </summary>
            <div className="mt-3 pt-3 border-t border-indigo-200/60 flex flex-wrap items-center gap-4 text-xs">
              {[
                { key: 'env', label: 'Переменные (env)' },
                { key: 'stop', label: 'Автостоп и метрики Prometheus' },
                { key: 'sla', label: 'SLA операций' },
                { key: 'warmup', label: 'Общий прогрев' },
                { key: 'data', label: 'Тестовые данные (CSV)' },
                { key: 'generators', label: 'Генераторы' },
              ].map((opt) => (
                <label key={opt.key} className="inline-flex items-center gap-1.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={(pushOpts as any)[opt.key]}
                    onChange={(e) => setPushOpts((p) => ({ ...p, [opt.key]: e.target.checked }))}
                    className="rounded border-slate-300 text-indigo-600"
                  />
                  <span>{opt.label}</span>
                </label>
              ))}
              <Button size="xs" className="ml-auto" loading={saving} onClick={handleApplyBaseToProfiles}>
                <Send className="h-3.5 w-3.5" />
                Отправить в профили ({profiles.length})
              </Button>
            </div>
          </details>
        </Card>
      )}

      {/* Живой график + KPI + Общий прогрев */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
        <Card className="lg:col-span-8 p-4 flex flex-col">
          <div className="flex items-center justify-between mb-3">
            <div className="text-xs font-semibold text-slate-800 flex items-center gap-1.5">
              <Activity className="h-4 w-4 text-indigo-600" />
              Визуальный профиль нагрузки (обновляется на лету)
            </div>
            <Button variant="outline" size="xs" onClick={() => setChartStacked((v) => !v)}>
              {chartStacked ? 'Суммарно (стек)' : 'Отдельные линии'}
            </Button>
          </div>
          <div className="h-56 w-full">
            {kpiAndChart.datasets.length === 0 ? (
              <div className="h-full flex items-center justify-center text-xs text-slate-400">
                Включите хотя бы одну операцию, чтобы увидеть кривую нагрузки
              </div>
            ) : (
              <Line
                data={{ datasets: kpiAndChart.datasets }}
                options={{
                  responsive: true,
                  maintainAspectRatio: false,
                  parsing: false,
                  scales: {
                    x: {
                      type: 'linear',
                      title: { display: true, text: 'Время, мин', font: { size: 11 } },
                    },
                    y: {
                      stacked: chartStacked,
                      beginAtZero: true,
                      title: { display: true, text: 'Интенсивность / VU', font: { size: 11 } },
                    },
                  },
                  plugins: {
                    legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 11 } } },
                  },
                }}
              />
            )}
          </div>
        </Card>

        <div className="lg:col-span-4 space-y-3">
          <div className="grid grid-cols-2 gap-2.5">
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Активные операции</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">
                {kpiAndChart.activeCount} / {ops.length}
              </div>
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Пик интенсивности</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">
                {kpiAndChart.peakRate > 0 ? `${kpiAndChart.peakRate} оп/с` : '—'}
              </div>
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Длительность</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">{fmtDurationSec(kpiAndChart.maxDurSec)}</div>
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Сумма maxVUs / ген.</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">{kpiAndChart.totalMaxVus || '—'}</div>
            </Card>
          </div>

          <Card className="p-3.5 space-y-2.5">
            <div className="flex items-center justify-between gap-2">
              <Switch
                checked={warmupOn}
                onChange={setWarmupOn}
                label={
                  <span className="inline-flex items-center gap-1 font-semibold text-xs">
                    <Flame className="h-3.5 w-3.5 text-amber-500" />
                    Общий прогрев
                  </span>
                }
              />
              <Input
                value={warmupDur}
                disabled={!warmupOn}
                onChange={(e) => setWarmupDur(e.target.value)}
                placeholder="2m"
                className="h-7 w-24 text-xs font-mono"
              />
            </div>
            <div className="flex items-center gap-1.5">
              {['30s', '1m', '2m', '5m'].map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => {
                    setWarmupOn(true);
                    setWarmupDur(p);
                  }}
                  className="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] font-mono text-slate-700 hover:bg-amber-50 hover:border-amber-300 cursor-pointer"
                >
                  {p}
                </button>
              ))}
            </div>
            <p className="text-[11px] text-slate-500 leading-snug">
              Ошибки и время ответа на прогреве не портят итоги и SLA ступеней.
            </p>
          </Card>
        </div>
      </div>

      {/* Раздел 1: Операции и сценарии */}
      <div className="space-y-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-base font-bold text-slate-900">Операции и сценарии ({ops.length})</h2>
          <div className="flex items-center gap-2">
            <Switch checked={onlyEnabled} onChange={setOnlyEnabled} label={<span className="text-xs">Только включённые</span>} />
            <Input
              value={opSearch}
              onChange={(e) => setOpSearch(e.target.value)}
              placeholder="Фильтр операций…"
              className="h-8 w-48 text-xs"
            />
          </div>
        </div>

        <div className="space-y-2.5">
          {ops.map((op, idx) => {
            if (onlyEnabled && !op.enabled) return null;
            if (
              opSearch.trim() &&
              !`${op.name} ${op.execFn}`.toLowerCase().includes(opSearch.trim().toLowerCase())
            ) {
              return null;
            }
            const collapsed = !!collapsedOps[idx];
            const ex = op.executor || 'ramping-arrival-rate';
            const sc = op.scenario || {};
            const isRamping = ex.startsWith('ramping');
            const isArrival = ex.includes('arrival');

            return (
              <Card key={idx} className={`overflow-hidden ${!op.enabled ? 'opacity-65' : ''}`}>
                <div className="flex flex-wrap items-center gap-2 bg-slate-50/80 px-3.5 py-2.5 border-b border-slate-100">
                  <button
                    type="button"
                    onClick={() => setCollapsedOps((p) => ({ ...p, [idx]: !collapsed }))}
                    className="rounded p-1 text-slate-500 hover:bg-slate-200/60 cursor-pointer"
                  >
                    {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </button>
                  <Switch checked={!!op.enabled} onChange={(v) => updateOp(idx, { enabled: v })} />
                  <span
                    className="h-2.5 w-2.5 rounded-full shrink-0"
                    style={{ backgroundColor: COLORS[idx % COLORS.length] }}
                  />
                  <code className="text-xs font-semibold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded">
                    {op.execFn}()
                  </code>
                  <Input
                    value={op.name || ''}
                    onChange={(e) => updateOp(idx, { name: e.target.value })}
                    placeholder="Название сценария"
                    className="h-8 w-48 text-xs font-medium"
                  />
                  <Select
                    value={ex}
                    onChange={(e) => {
                      const nextEx = e.target.value;
                      updateOp(idx, { executor: nextEx, scenario: defaultScenarioFor(nextEx) });
                    }}
                    className="h-8 text-xs w-72"
                  >
                    {EXECUTORS.map((eItem) => (
                      <option key={eItem.value} value={eItem.value}>
                        {eItem.label}
                      </option>
                    ))}
                  </Select>

                  <div className="ml-auto flex items-center gap-1">
                    <Button
                      variant="ghost"
                      size="xs"
                      title="Добавить ещё один сценарий для этой функции"
                      onClick={() => {
                        const copy = JSON.parse(JSON.stringify(op));
                        copy.name = `${op.name || op.execFn}_2`;
                        setOps((prev) => [...prev.slice(0, idx + 1), copy, ...prev.slice(idx + 1)]);
                      }}
                    >
                      <Copy className="h-3.5 w-3.5" />
                      Дублировать
                    </Button>
                    {ops.filter((x) => x.execFn === op.execFn).length > 1 && (
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setOps((prev) => prev.filter((_, i) => i !== idx))}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                      </Button>
                    )}
                  </div>
                </div>

                {!collapsed && (
                  <CardBody className="space-y-3 text-xs">
                    {/* Поля исполнителя */}
                    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2.5">
                      {ex === 'constant-arrival-rate' && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Интенсивность (rate)</label>
                            <Input
                              type="number"
                              step="any"
                              value={sc.rate ?? 50}
                              onChange={(e) => updateScenario(idx, { rate: Number(e.target.value) })}
                              className="h-8 text-xs"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Интервал (timeUnit)</label>
                            <Input
                              value={sc.timeUnit ?? '1s'}
                              onChange={(e) => updateScenario(idx, { timeUnit: e.target.value })}
                              className="h-8 text-xs font-mono"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Длительность</label>
                            <Input
                              value={sc.duration ?? '5m'}
                              onChange={(e) => updateScenario(idx, { duration: e.target.value })}
                              className="h-8 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}

                      {ex === 'ramping-arrival-rate' && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Старт (startRate)</label>
                            <Input
                              type="number"
                              step="any"
                              value={sc.startRate ?? 0}
                              onChange={(e) => updateScenario(idx, { startRate: Number(e.target.value) })}
                              className="h-8 text-xs"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Интервал (timeUnit)</label>
                            <Input
                              value={sc.timeUnit ?? '1s'}
                              onChange={(e) => updateScenario(idx, { timeUnit: e.target.value })}
                              className="h-8 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}

                      {isArrival && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">preAllocatedVUs / ген.</label>
                            <Input
                              type="number"
                              value={sc.preAllocatedVUs ?? 20}
                              onChange={(e) => updateScenario(idx, { preAllocatedVUs: Number(e.target.value) })}
                              className="h-8 text-xs"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">maxVUs / ген.</label>
                            <Input
                              type="number"
                              value={sc.maxVUs ?? 100}
                              onChange={(e) => updateScenario(idx, { maxVUs: Number(e.target.value) })}
                              className="h-8 text-xs"
                            />
                          </div>
                        </>
                      )}

                      {(ex === 'constant-vus' || ex === 'per-vu-iterations' || ex === 'shared-iterations' || ex === 'externally-controlled') && (
                        <div>
                          <label className="block text-slate-500 mb-1">VU (всего)</label>
                          <Input
                            type="number"
                            value={sc.vus ?? 10}
                            onChange={(e) => updateScenario(idx, { vus: Number(e.target.value) })}
                            className="h-8 text-xs"
                          />
                        </div>
                      )}

                      {ex === 'ramping-vus' && (
                        <div>
                          <label className="block text-slate-500 mb-1">startVUs</label>
                          <Input
                            type="number"
                            value={sc.startVUs ?? 0}
                            onChange={(e) => updateScenario(idx, { startVUs: Number(e.target.value) })}
                            className="h-8 text-xs"
                          />
                        </div>
                      )}

                      {(ex === 'constant-vus' || ex === 'externally-controlled') && (
                        <div>
                          <label className="block text-slate-500 mb-1">Длительность</label>
                          <Input
                            value={sc.duration ?? '5m'}
                            onChange={(e) => updateScenario(idx, { duration: e.target.value })}
                            className="h-8 text-xs font-mono"
                          />
                        </div>
                      )}

                      {(ex === 'per-vu-iterations' || ex === 'shared-iterations') && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Итераций</label>
                            <Input
                              type="number"
                              value={sc.iterations ?? 100}
                              onChange={(e) => updateScenario(idx, { iterations: Number(e.target.value) })}
                              className="h-8 text-xs"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Макс. длительность</label>
                            <Input
                              value={sc.maxDuration ?? '10m'}
                              onChange={(e) => updateScenario(idx, { maxDuration: e.target.value })}
                              className="h-8 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}
                    </div>

                    {/* Ступени нагрузки для ramping-* */}
                    {isRamping && (
                      <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3 space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-semibold text-slate-700">Стадии нагрузки ({sc.stages?.length || 0})</span>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => {
                                const base = Number(sc.stages?.[0]?.target) || 50;
                                const stages: any[] = [];
                                [1, 1.2, 1.4, 1.6, 1.8].forEach((k) => {
                                  const t = Math.round(base * k);
                                  stages.push({ duration: '0s', target: t }, { duration: '5m', target: t });
                                });
                                updateScenario(idx, { stages });
                              }}
                            >
                              5 ступеней (0s скачком)
                            </Button>
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => {
                                const base = Number(sc.stages?.[0]?.target) || 50;
                                const stages: any[] = [];
                                [1, 1.2, 1.4, 1.6, 1.8].forEach((k, si) => {
                                  const t = Math.round(base * k);
                                  stages.push({ duration: si === 0 ? '1m' : '30s', target: t }, { duration: '3m', target: t });
                                });
                                updateScenario(idx, { stages });
                              }}
                            >
                              5 ступеней (плавно)
                            </Button>
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => {
                                const cur = sc.stages || [];
                                const lastTarget = cur.length > 0 ? cur[cur.length - 1].target : 50;
                                updateScenario(idx, { stages: [...cur, { duration: '3m', target: lastTarget }] });
                              }}
                            >
                              <Plus className="h-3 w-3" />
                              Стадия
                            </Button>
                          </div>
                        </div>

                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2">
                          {(sc.stages || []).map((st: any, sIdx: number) => (
                            <div key={sIdx} className="flex items-center gap-1.5 rounded-lg bg-white border border-slate-200 px-2 py-1.5">
                              <span className="text-[11px] font-mono text-slate-400 w-5">#{sIdx + 1}</span>
                              <Input
                                value={st.duration ?? '1m'}
                                onChange={(e) => {
                                  const next = [...(sc.stages || [])];
                                  next[sIdx] = { ...next[sIdx], duration: e.target.value };
                                  updateScenario(idx, { stages: next });
                                }}
                                placeholder="5m"
                                className="h-7 w-20 text-xs font-mono"
                              />
                              <Input
                                type="number"
                                step="any"
                                value={st.target ?? 0}
                                onChange={(e) => {
                                  const next = [...(sc.stages || [])];
                                  next[sIdx] = { ...next[sIdx], target: Number(e.target.value) };
                                  updateScenario(idx, { stages: next });
                                }}
                                className="h-7 flex-1 text-xs font-mono"
                              />
                              <button
                                type="button"
                                onClick={() => {
                                  const next = (sc.stages || []).filter((_: any, i: number) => i !== sIdx);
                                  updateScenario(idx, { stages: next });
                                }}
                                className="text-slate-400 hover:text-rose-600 cursor-pointer"
                              >
                                <X className="h-3.5 w-3.5" />
                              </button>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* SLA операции */}
                    <div className="flex flex-wrap items-center gap-3 pt-2 border-t border-slate-100">
                      <span className="text-slate-500 font-medium">SLA операции:</span>
                      <div className="flex items-center gap-1.5">
                        <Select
                          value={String(op.slaPercentile || 95)}
                          onChange={(e) => updateOp(idx, { slaPercentile: Number(e.target.value) })}
                          className="h-7 w-20 text-xs"
                        >
                          <option value="90">p90</option>
                          <option value="95">p95</option>
                          <option value="99">p99</option>
                        </Select>
                        <span>≤</span>
                        <Input
                          type="number"
                          step="any"
                          value={op.slaThresholdMs ?? 1000}
                          onChange={(e) => updateOp(idx, { slaThresholdMs: Number(e.target.value) })}
                          className="h-7 w-24 text-xs"
                        />
                        <span className="text-slate-500">мс</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span className="text-slate-500">Ошибки ≤</span>
                        <Input
                          type="number"
                          step="any"
                          value={op.maxErrorPct ?? 1}
                          onChange={(e) => updateOp(idx, { maxErrorPct: Number(e.target.value) })}
                          className="h-7 w-20 text-xs"
                        />
                        <span className="text-slate-500">%</span>
                      </div>
                      <Select
                        value={op.failureSource || 'HTTP'}
                        onChange={(e) => updateOp(idx, { failureSource: e.target.value })}
                        className="h-7 w-40 text-xs"
                      >
                        <option value="HTTP">Ошибки HTTP</option>
                        <option value="CHECKS">Провалы check()</option>
                      </Select>
                    </div>
                  </CardBody>
                )}
              </Card>
            );
          })}
        </div>
      </div>

      {/* Раздел 2: Переменные окружения и секреты */}
      <Card>
        <CardHeader>
          <span className="font-semibold text-sm">Переменные окружения и секреты ({envRows.filter((r) => r.k.trim()).length})</span>
          <Button
            variant="outline"
            size="xs"
            onClick={() => setEnvRows((prev) => [...prev, { k: '', v: '' }])}
          >
            <Plus className="h-3.5 w-3.5" />
            Добавить переменную
          </Button>
        </CardHeader>
        <CardBody className="space-y-2.5">
          {secretNames.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5 text-xs">
              <span className="text-slate-500">Секреты (клик скопирует ссылку):</span>
              {secretNames.map((sn: string) => (
                <button
                  key={sn}
                  type="button"
                  onClick={() => {
                    navigator.clipboard?.writeText(`\${secret:${sn}}`);
                    toast.info(`Скопировано: \${secret:${sn}}`);
                  }}
                  className="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[11px] text-slate-700 hover:bg-indigo-50 hover:text-indigo-700 cursor-pointer"
                >
                  {`\${secret:${sn}}`}
                </button>
              ))}
            </div>
          )}

          <div className="space-y-2">
            {envRows.map((row, rIdx) => (
              <div key={rIdx} className="flex items-center gap-2">
                <Input
                  value={row.k}
                  onChange={(e) =>
                    setEnvRows((prev) => prev.map((it, i) => (i === rIdx ? { ...it, k: e.target.value } : it)))
                  }
                  placeholder="BASE_URL"
                  className="h-8 w-64 font-mono text-xs"
                />
                <Input
                  value={row.v}
                  onChange={(e) =>
                    setEnvRows((prev) => prev.map((it, i) => (i === rIdx ? { ...it, v: e.target.value } : it)))
                  }
                  placeholder="https://... или ${secret:NAME}"
                  className="h-8 flex-1 font-mono text-xs"
                />
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => setEnvRows((prev) => prev.filter((_, i) => i !== rIdx))}
                >
                  <X className="h-4 w-4 text-slate-400 hover:text-rose-600" />
                </Button>
              </div>
            ))}
          </div>
        </CardBody>
      </Card>

      {/* Раздел 3: Автостоп и кастомные проверки Prometheus */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm">
            <ShieldAlert className="h-4 w-4 text-indigo-600" />
            Автостоп и метрики SLA Prometheus
          </div>
        </CardHeader>
        <CardBody className="space-y-4 text-xs">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.rateEnabled}
                onChange={(v) => setStop({ ...stop, rateEnabled: v })}
                label={<span className="text-xs font-medium">Падение интенсивности</span>}
              />
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Мин. от плана:</span>
                <Input
                  type="number"
                  value={stop.minRatePct ?? 90}
                  onChange={(e) => setStop({ ...stop, minRatePct: Number(e.target.value) })}
                  className="h-7 w-20 text-xs"
                />
                <span>%</span>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.percentileEnabled}
                onChange={(v) => setStop({ ...stop, percentileEnabled: v })}
                label={<span className="text-xs font-medium">Превышение времени ответа</span>}
              />
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Множитель к SLA:</span>
                <Input
                  type="number"
                  step="0.1"
                  value={stop.slaMultiplier ?? 1.5}
                  onChange={(e) => setStop({ ...stop, slaMultiplier: Number(e.target.value) })}
                  className="h-7 w-20 text-xs"
                />
                <span>×</span>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.errorsEnabled}
                onChange={(v) => setStop({ ...stop, errorsEnabled: v })}
                label={<span className="text-xs font-medium">Превышение % ошибок</span>}
              />
              <Switch
                checked={!!stop.extraStepOnMaxSearch}
                onChange={(v) => setStop({ ...stop, extraStepOnMaxSearch: v })}
                label={<span className="text-xs text-slate-600">+1 ступень при поиске максимума</span>}
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-slate-600">Окно, сек:</span>
                <Input
                  type="number"
                  value={stop.windowSec ?? 60}
                  onChange={(e) => setStop({ ...stop, windowSec: Number(e.target.value) })}
                  className="h-7 w-20 text-xs"
                />
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-600">Подряд раз:</span>
                <Input
                  type="number"
                  value={stop.consecutive ?? 4}
                  onChange={(e) => setStop({ ...stop, consecutive: Number(e.target.value) })}
                  className="h-7 w-20 text-xs"
                />
              </div>
            </div>
          </div>

          {/* Кастомные PromQL проверки */}
          <div className="border-t border-slate-100 pt-3 space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-semibold text-slate-800">
                Кастомные метрики Prometheus (например CPU / БД / очередь)
              </span>
              <div className="flex items-center gap-1.5">
                {scriptCharts.length > 0 && (
                  <Select
                    value=""
                    onChange={(e) => {
                      const ch = scriptCharts.find((c: any) => String(c.id) === e.target.value);
                      if (!ch) return;
                      const q = ch.queries?.[0]?.promql || '';
                      setStop({
                        ...stop,
                        customChecks: [
                          ...(stop.customChecks || []),
                          { name: ch.name, promql: q, cmp: 'LTE', threshold: 80, unit: ch.unit || '%', stepSla: true, autoStop: false },
                        ],
                      });
                    }}
                    className="h-7 text-xs w-52"
                  >
                    <option value="">+ Из графика скрипта…</option>
                    {scriptCharts.map((c: any) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </Select>
                )}
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() =>
                    setStop({
                      ...stop,
                      customChecks: [
                        ...(stop.customChecks || []),
                        { name: 'CPU хоста', promql: '', cmp: 'LTE', threshold: 80, unit: '%', stepSla: true, autoStop: false },
                      ],
                    })
                  }
                >
                  <Plus className="h-3.5 w-3.5" />
                  Правило PromQL
                </Button>
              </div>
            </div>

            {(stop.customChecks || []).map((cc: any, cIdx: number) => (
              <div key={cIdx} className="rounded-lg border border-slate-200 bg-slate-50/70 p-2.5 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    value={cc.name || ''}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], name: e.target.value };
                      setStop({ ...stop, customChecks: next });
                    }}
                    placeholder="Название метрики"
                    className="h-7 w-44 text-xs"
                  />
                  <Select
                    value={cc.cmp || 'LTE'}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], cmp: e.target.value };
                      setStop({ ...stop, customChecks: next });
                    }}
                    className="h-7 w-20 text-xs"
                  >
                    <option value="LTE">≤</option>
                    <option value="LT">&lt;</option>
                    <option value="GTE">≥</option>
                    <option value="GT">&gt;</option>
                  </Select>
                  <Input
                    type="number"
                    step="any"
                    value={cc.threshold ?? 80}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], threshold: Number(e.target.value) };
                      setStop({ ...stop, customChecks: next });
                    }}
                    className="h-7 w-24 text-xs"
                  />
                  <Input
                    value={cc.unit || ''}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], unit: e.target.value };
                      setStop({ ...stop, customChecks: next });
                    }}
                    placeholder="%"
                    className="h-7 w-16 text-xs"
                  />
                  <Switch
                    checked={!!cc.stepSla}
                    onChange={(v) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], stepSla: v };
                      setStop({ ...stop, customChecks: next });
                    }}
                    label={<span className="text-xs">SLA</span>}
                  />
                  <Switch
                    checked={!!cc.autoStop}
                    onChange={(v) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], autoStop: v };
                      setStop({ ...stop, customChecks: next });
                    }}
                    label={<span className="text-xs">Автостоп</span>}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    className="ml-auto"
                    onClick={() =>
                      setStop({ ...stop, customChecks: stop.customChecks.filter((_: any, i: number) => i !== cIdx) })
                    }
                  >
                    <X className="h-4 w-4 text-rose-500" />
                  </Button>
                </div>
                <Input
                  value={cc.promql || ''}
                  onChange={(e) => {
                    const next = [...stop.customChecks];
                    next[cIdx] = { ...next[cIdx], promql: e.target.value };
                    setStop({ ...stop, customChecks: next });
                  }}
                  placeholder="PromQL запрос..."
                  className="h-8 font-mono text-xs"
                />
              </div>
            ))}
          </div>
        </CardBody>
      </Card>

      {/* Раздел 4: Тестовые данные CSV */}
      {dataFiles.length > 0 && (
        <Card>
          <CardHeader>
            <span className="font-semibold text-sm">Тестовые данные ({dataFiles.length})</span>
          </CardHeader>
          <CardBody className="p-0">
            <table className="w-full text-xs">
              <thead className="bg-slate-50 text-slate-500 border-b border-slate-100">
                <tr>
                  <th className="text-left px-4 py-2">Файл</th>
                  <th className="text-left px-3 py-2">Режим деления</th>
                  <th className="text-center px-3 py-2">Заголовок</th>
                  <th className="text-center px-4 py-2">Расходуется</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {dataFiles.map((df, dIdx) => (
                  <tr key={df.path}>
                    <td className="px-4 py-2 font-mono">{df.path}</td>
                    <td className="px-3 py-2">
                      <Select
                        value={df.mode || 'SHARED'}
                        onChange={(e) =>
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, mode: e.target.value } : it))
                          )
                        }
                        className="h-7 w-44 text-xs"
                      >
                        <option value="SHARED">Общий (на всех одинаковый)</option>
                        <option value="SPLIT">Нарезка по генераторам</option>
                      </Select>
                    </td>
                    <td className="px-3 py-2 text-center">
                      <input
                        type="checkbox"
                        checked={!!df.hasHeader}
                        onChange={(e) =>
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, hasHeader: e.target.checked } : it))
                          )
                        }
                      />
                    </td>
                    <td className="px-4 py-2 text-center">
                      <input
                        type="checkbox"
                        checked={!!df.consumed}
                        onChange={(e) =>
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, consumed: e.target.checked } : it))
                          )
                        }
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardBody>
        </Card>
      )}

      {/* Раздел 5: Итоговый конфиг k6 и расчёт ресурсов */}
      <Card>
        <div
          onClick={() => setAdvOpen((v) => !v)}
          className="flex items-center justify-between px-4 py-3 cursor-pointer hover:bg-slate-50/70"
        >
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-800">
            {advOpen ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            Итоговый конфиг k6 и требования к ресурсам
            {plan?.overridePaths?.length > 0 && (
              <Badge variant="warning">ручных правок: {plan.overridePaths.length}</Badge>
            )}
          </div>
        </div>
        {advOpen && (
          <CardBody className="border-t border-slate-100 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                {!loadedProfileId && plan?.configJson && (
                  <Button
                    size="xs"
                    onClick={async () => {
                      try {
                        const res = await apiFetch(`/api/v1/scripts/${id}/load/k6-config`, {
                          method: 'POST',
                          body: { config: editedConfig },
                        });
                        toast.success(res.message);
                        qc.setQueryData(['script-load', id, profileParam], res);
                      } catch (err: any) {
                        toast.error(err.message);
                      }
                    }}
                  >
                    Сохранить правки JSON
                  </Button>
                )}
                {!loadedProfileId && plan?.overridePaths?.length > 0 && (
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={async () => {
                      const res = await apiFetch(`/api/v1/scripts/${id}/load/k6-config/reset`, { method: 'POST' });
                      toast.success(res.message);
                      qc.setQueryData(['script-load', id, profileParam], res);
                    }}
                  >
                    Сбросить к расчётному
                  </Button>
                )}
              </div>

              <Button
                variant="outline"
                size="xs"
                loading={reqLoading}
                onClick={async () => {
                  setReqLoading(true);
                  try {
                    const res = await apiFetch(
                      `/api/v1/scripts/${id}/load/requirements${loadedProfileId ? `?profile=${loadedProfileId}` : ''}`,
                      { method: 'POST' }
                    );
                    setReqInfo(res);
                  } catch (err: any) {
                    toast.error(err.message);
                  } finally {
                    setReqLoading(false);
                  }
                }}
              >
                Рассчитать (k6 inspect --execution-requirements)
              </Button>
            </div>

            {reqInfo && (
              <div className="rounded-lg bg-slate-100 px-3 py-2 text-xs text-slate-700 flex gap-6">
                <span>
                  Максимум VU: <b>{reqInfo.maxVus}</b>
                </span>
                <span>
                  Общая длительность: <b>{reqInfo.totalDuration}</b>
                </span>
              </div>
            )}

            <MonacoEditor
              value={editedConfig || '// Сохраните нагрузку, чтобы увидеть расчётный JSON конфиг k6'}
              onChange={setEditedConfig}
              language="json"
              readOnly={!!loadedProfileId}
              height="340px"
            />
          </CardBody>
        )}
      </Card>

      {/* Липкая нижняя панель сохранения */}
      <div className="fixed bottom-0 left-0 right-0 z-30 border-t border-slate-200 bg-white/95 backdrop-blur-xs px-4 py-2.5 shadow-lg">
        <div className="mx-auto max-w-[1600px] flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {loadedProfileId ? (
              <>
                <Button variant="primary" size="sm" loading={saving} onClick={handleSaveProfile}>
                  <Save className="h-4 w-4" />
                  Сохранить профиль «{profileName || loadedProfileName}»
                </Button>
                <Button variant="outline" size="sm" loading={saving} onClick={handleSaveBase}>
                  Записать в базовую нагрузку
                </Button>
              </>
            ) : (
              <Button variant="primary" size="sm" loading={saving} onClick={handleSaveBase}>
                <Save className="h-4 w-4" />
                {profiles.length > 0 ? 'Сохранить базовую нагрузку' : 'Сохранить нагрузку'}
              </Button>
            )}
          </div>
          <Link to={`/scripts/${script.id}`}>
            <Button variant="ghost" size="sm">
              К скрипту
            </Button>
          </Link>
        </div>
      </div>

      {/* Модальное окно выбора и массового управления профилями */}
      <Modal
        open={profilesModalOpen}
        onClose={() => setProfilesModalOpen(false)}
        title={`Профили нагрузки (${profiles.length})`}
        maxWidth="max-w-4xl"
      >
        <div className="space-y-3">
          {profiles.length > 0 && (
            <div className="flex flex-wrap items-center gap-3">
              <div className="relative flex-1 min-w-[200px]">
                <Search className="h-4 w-4 text-slate-400 absolute left-3 top-2.5" />
                <Input
                  value={profSearch}
                  onChange={(e) => setProfSearch(e.target.value)}
                  placeholder="Поиск профиля…"
                  className="pl-9 h-8 text-xs"
                />
              </div>
              <label className="inline-flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={filteredProfiles.length > 0 && filteredProfiles.every((p: any) => selectedProfIds.has(p.id))}
                  onChange={(e) => {
                    const next = new Set(selectedProfIds);
                    filteredProfiles.forEach((p: any) => {
                      if (e.target.checked) next.add(p.id);
                      else next.delete(p.id);
                    });
                    setSelectedProfIds(next);
                  }}
                />
                Выбрать все видимые
              </label>
              {selectedProfIds.size > 0 && (
                <Button
                  variant="danger"
                  size="xs"
                  onClick={async () => {
                    const ids = Array.from(selectedProfIds);
                    const ok = await confirm({
                      title: 'Удалить выбранные профили?',
                      message: `Будет удалено профилей: ${ids.length}`,
                      confirmText: `Удалить (${ids.length})`,
                    });
                    if (!ok) return;
                    await apiFetch(`/api/v1/scripts/${id}/load/profiles/bulk-delete`, {
                      method: 'POST',
                      body: { ids },
                    });
                    setSelectedProfIds(new Set());
                    if (loadedProfileId && ids.includes(loadedProfileId)) {
                      setSearchParams({});
                    }
                    qc.invalidateQueries({ queryKey: ['script-load', id] });
                    toast.success(`Удалено профилей: ${ids.length}`);
                  }}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  Удалить выбранные ({selectedProfIds.size})
                </Button>
              )}
            </div>
          )}

          {/* Базовый профиль */}
          <div
            onClick={() => {
              setSearchParams({});
              setProfilesModalOpen(false);
            }}
            className={`flex items-center justify-between rounded-xl border p-3 cursor-pointer transition-colors ${
              loadedProfileId == null ? 'border-indigo-500 bg-indigo-50/50' : 'border-slate-200 hover:bg-slate-50'
            }`}
          >
            <div>
              <div className="text-sm font-semibold text-slate-900 flex items-center gap-1.5">
                <Star className="h-4 w-4 text-amber-500 fill-amber-500" />
                Базовый профиль скрипта
                {loadedProfileId == null && <Badge variant="info">Открыт</Badge>}
              </div>
              <div className="text-xs text-slate-500">Основная нагрузка скрипта</div>
            </div>
            {loadedProfileId != null && (
              <Button variant="outline" size="xs">
                Открыть
              </Button>
            )}
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5 max-h-[55vh] overflow-y-auto">
            {filteredProfiles.map((p: any) => {
              const isCur = loadedProfileId === p.id;
              const isChecked = selectedProfIds.has(p.id);
              const opsCnt = p.data?.operations?.filter((o: any) => o.enabled !== false)?.length || 0;
              return (
                <div
                  key={p.id}
                  className={`rounded-xl border p-3 space-y-2 ${
                    isCur ? 'border-indigo-500 bg-indigo-50/40' : 'border-slate-200 bg-white'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => {}}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          const next = new Set(selectedProfIds);
                          const targetChecked = !isChecked;
                          if (ev.shiftKey && lastClickedProfId != null) {
                            const i1 = filteredProfiles.findIndex((x: any) => x.id === lastClickedProfId);
                            const i2 = filteredProfiles.findIndex((x: any) => x.id === p.id);
                            if (i1 >= 0 && i2 >= 0) {
                              for (let k = Math.min(i1, i2); k <= Math.max(i1, i2); k++) {
                                if (targetChecked) next.add(filteredProfiles[k].id);
                                else next.delete(filteredProfiles[k].id);
                              }
                            }
                          } else {
                            if (targetChecked) next.add(p.id);
                            else next.delete(p.id);
                          }
                          setLastClickedProfId(p.id);
                          setSelectedProfIds(next);
                        }}
                      />
                      <button
                        type="button"
                        onClick={() => {
                          setSearchParams({ profile: String(p.id) });
                          setProfilesModalOpen(false);
                        }}
                        className="font-semibold text-sm text-slate-900 hover:text-indigo-600 truncate text-left cursor-pointer"
                      >
                        {p.name}
                      </button>
                    </div>
                    {isCur && <Badge variant="info">Открыт</Badge>}
                  </div>

                  <div className="text-xs text-slate-500 flex items-center gap-2">
                    <span>{opsCnt} оп.</span>
                    {p.updatedAt && <span>· {fmtShortDate(p.updatedAt)}</span>}
                  </div>

                  <div className="flex items-center gap-1 pt-1 border-t border-slate-100">
                    <Button
                      variant="outline"
                      size="xs"
                      onClick={() => {
                        setSearchParams({ profile: String(p.id) });
                        setProfilesModalOpen(false);
                      }}
                    >
                      <FolderOpen className="h-3.5 w-3.5" />
                      Открыть
                    </Button>
                    <Link to={`/scripts/${script.id}/run?profile=${p.id}`}>
                      <Button variant="outline" size="xs" title="Запустить">
                        <Play className="h-3.5 w-3.5 text-emerald-600 fill-emerald-600" />
                      </Button>
                    </Link>
                    <Link to={`/scripts/${script.id}/run?profile=${p.id}#schedule`}>
                      <Button variant="outline" size="xs" title="Запланировать">
                        <CalendarPlus className="h-3.5 w-3.5 text-indigo-600" />
                      </Button>
                    </Link>
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Дублировать"
                      onClick={async () => {
                        const res = await apiFetch(`/api/v1/scripts/${id}/load/profile/${p.id}/duplicate`, {
                          method: 'POST',
                        });
                        qc.invalidateQueries({ queryKey: ['script-load', id] });
                        toast.success(`Создана копия «${res.profileName}»`);
                      }}
                    >
                      <Copy className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Переименовать"
                      onClick={() => setRenameModal({ id: p.id, name: p.name })}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="ml-auto"
                      title="Удалить"
                      onClick={async () => {
                        const ok = await confirm({
                          title: 'Удалить профиль?',
                          message: `Профиль «${p.name}» будет удалён безвозвратно.`,
                        });
                        if (!ok) return;
                        await apiFetch(`/api/v1/scripts/${id}/load/profile/${p.id}/delete`, { method: 'POST' });
                        if (loadedProfileId === p.id) setSearchParams({});
                        qc.invalidateQueries({ queryKey: ['script-load', id] });
                        toast.success('Профиль удалён');
                      }}
                    >
                      <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </Modal>

      {/* Переименование профиля */}
      <Modal open={!!renameModal} onClose={() => setRenameModal(null)} title="Переименовать профиль" maxWidth="max-w-sm">
        {renameModal && (
          <form
            className="space-y-3"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                await apiFetch(`/api/v1/scripts/${id}/load/profile/${renameModal.id}/rename`, {
                  method: 'POST',
                  body: { name: renameModal.name.trim() },
                });
                setRenameModal(null);
                qc.invalidateQueries({ queryKey: ['script-load', id] });
                toast.success('Профиль переименован');
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <Input
              autoFocus
              required
              value={renameModal.name}
              onChange={(e) => setRenameModal({ ...renameModal, name: e.target.value })}
            />
            <div className="flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={() => setRenameModal(null)}>
                Отмена
              </Button>
              <Button type="submit" size="sm">
                Сохранить
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Импорт и экспорт профилей JSON */}
      <Modal
        open={importModalOpen}
        onClose={() => setImportModalOpen(false)}
        title="Импорт и экспорт профилей (JSON)"
        maxWidth="max-w-2xl"
      >
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            setImporting(true);
            try {
              const fd = new FormData();
              if (importFile) fd.append('file', importFile);
              if (importJson.trim()) fd.append('json', importJson);
              fd.append('scheduleSequential', String(importSeq));
              fd.append('gapMinutes', String(importGap));
              const res = await apiUpload(`/api/v1/scripts/${id}/load/profiles/import`, fd);
              toast.success(res.message);
              setImportModalOpen(false);
              setImportJson('');
              setImportFile(null);
              qc.invalidateQueries({ queryKey: ['script-load', id] });
              if (res.scheduledCount > 0) navigate('/schedule');
            } catch (err: any) {
              toast.error(err.message);
            } finally {
              setImporting(false);
            }
          }}
        >
          <div className="flex items-center justify-between rounded-lg bg-slate-50 border border-slate-200 p-2.5">
            <span>Скачать все сохранённые профили этого скрипта в JSON:</span>
            <a href={`/scripts/${script.id}/load/profiles.json`}>
              <Button type="button" variant="outline" size="xs">
                <Download className="h-3.5 w-3.5" />
                Экспортировать JSON
              </Button>
            </a>
          </div>

          <div>
            <label className="block font-medium text-slate-700 mb-1">JSON-файл профилей</label>
            <input
              type="file"
              accept=".json,application/json"
              onChange={(e) => setImportFile(e.target.files?.[0] || null)}
              className="block w-full text-xs text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:font-semibold file:text-indigo-700"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="font-medium text-slate-700">Или JSON-текст</label>
              <button
                type="button"
                onClick={() => setImportJson(EXAMPLE_IMPORT_JSON)}
                className="text-indigo-600 hover:underline cursor-pointer"
              >
                Вставить полный пример
              </button>
            </div>
            <Textarea
              rows={7}
              value={importJson}
              onChange={(e) => setImportJson(e.target.value)}
              placeholder='{"profiles": [...]}'
              className="font-mono text-xs"
            />
          </div>

          <div className="rounded-xl border border-slate-200 p-3 space-y-2 bg-slate-50/60">
            <Switch
              checked={importSeq}
              onChange={setImportSeq}
              label={<span className="text-xs font-medium">Сразу поставить импортированные профили последовательно в расписание</span>}
            />
            {importSeq && (
              <div className="flex items-center gap-2 pt-1">
                <span>Пауза между прогонами, мин:</span>
                <Input
                  type="number"
                  min={5}
                  max={120}
                  value={importGap}
                  onChange={(e) => setImportGap(Number(e.target.value))}
                  className="h-7 w-20 text-xs"
                />
              </div>
            )}
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setImportModalOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={importing}>
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
