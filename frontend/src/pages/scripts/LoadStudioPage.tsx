import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Chart as ChartJS,
  CategoryScale,
  Filler,
  Legend,
  LinearScale,
  LineElement,
  Plugin,
  PointElement,
  Title,
  Tooltip,
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import { apiFetch, apiUpload } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
  Textarea,
} from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { MonacoEditor } from '@/components/ui/MonacoEditor';
import { FormulaInput } from '@/components/load/FormulaInput';
import {
  K6Stage,
  StageConstructor,
  parseStepsFromStages,
  stepUnitShort,
} from '@/components/load/StageConstructor';
import { ScriptHead } from './ScriptHead';
import { fmtDurationSec, fmtShortDate, parseDurationToSec } from '@/lib/utils';
import {
  Activity,
  AlertTriangle,
  ArrowDownUp,
  CalendarPlus,
  Check,
  ChevronDown,
  ChevronRight,
  ClipboardCopy,
  ClipboardPaste,
  Copy,
  Download,
  ExternalLink,
  Flame,
  FolderOpen,
  KeyRound,
  Layers,
  Minimize2,
  Maximize2,
  Pencil,
  Play,
  Plus,
  Save,
  Search,
  Send,
  Settings2,
  ShieldAlert,
  Star,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Filler, Legend);

const COLORS = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];

const EXECUTORS = [
  { value: 'constant-arrival-rate', short: 'Постоянная оп/с', label: 'constant-arrival-rate — постоянная интенсивность' },
  { value: 'ramping-arrival-rate', short: 'Стадии оп/с', label: 'ramping-arrival-rate — интенсивность по стадиям' },
  { value: 'constant-vus', short: 'Постоянные VU', label: 'constant-vus — постоянное число VU' },
  { value: 'ramping-vus', short: 'Стадии VU', label: 'ramping-vus — VU по стадиям' },
  { value: 'per-vu-iterations', short: 'Итерации / VU', label: 'per-vu-iterations — итерации на VU' },
  { value: 'shared-iterations', short: 'Общие итерации', label: 'shared-iterations — общее число итераций' },
  { value: 'externally-controlled', short: 'REST API k6', label: 'externally-controlled — через REST API' },
];

const CC_PRESETS = [
  {
    key: 'cpu',
    label: 'CPU хостов (rate)',
    name: 'Утилизация CPU серверов',
    promql: '100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[{range}])) * 100)',
    cmp: 'LTE',
    threshold: 80,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
  {
    key: 'cpuIrate',
    label: 'CPU хостов (irate)',
    name: 'Утилизация CPU (irate)',
    promql: '100 * (1 - avg by (instance) (irate(node_cpu_seconds_total{mode="idle"}[{range}])))',
    cmp: 'LTE',
    threshold: 80,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
  {
    key: 'ram',
    label: 'RAM серверов',
    name: 'Использование RAM серверов',
    promql: '(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100',
    cmp: 'LTE',
    threshold: 85,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
  {
    key: 'disk',
    label: 'Диск I/O',
    name: 'Утилизация диска (I/O)',
    promql: 'max by (instance) (rate(node_disk_io_time_seconds_total[{range}])) * 100',
    cmp: 'LTE',
    threshold: 85,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
  {
    key: 'jvm',
    label: 'JVM Heap Used',
    name: 'JVM Heap Used',
    promql: 'sum by (instance) (jvm_memory_used_bytes{area="heap"}) / sum by (instance) (jvm_memory_max_bytes{area="heap"}) * 100',
    cmp: 'LTE',
    threshold: 85,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
  {
    key: 'db',
    label: 'HikariCP соединения БД',
    name: 'Активные соединения БД (HikariCP)',
    promql: 'hikaricp_connections_active / hikaricp_connections_max * 100',
    cmp: 'LTE',
    threshold: 90,
    unit: '%',
    stepSla: true,
    autoStop: false,
  },
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
          droppedEnabled: false,
          maxDroppedPct: 1,
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

function defaultScenarioFor(exec: string): Record<string, any> {
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

function allowedKeysForExecutor(exec: string): string[] {
  const tail = ['startTime', 'gracefulStop', 'env', 'tags'];
  switch (exec) {
    case 'constant-arrival-rate':
      return ['warmup', 'rate', 'timeUnit', 'duration', 'preAllocatedVUs', 'maxVUs', ...tail];
    case 'ramping-arrival-rate':
      return ['warmup', 'startRate', 'timeUnit', 'stages', 'preAllocatedVUs', 'maxVUs', ...tail];
    case 'constant-vus':
      return ['warmup', 'vus', 'duration', ...tail];
    case 'ramping-vus':
      return ['warmup', 'startVUs', 'stages', 'gracefulRampDown', ...tail];
    case 'per-vu-iterations':
    case 'shared-iterations':
      return ['vus', 'iterations', 'maxDuration', ...tail];
    case 'externally-controlled':
      return ['vus', 'maxVUs', 'duration', ...tail];
    default:
      return tail;
  }
}

function supportsConstantWarmup(exec: string): boolean {
  return exec === 'constant-arrival-rate' || exec === 'constant-vus';
}

function isRampingExecutor(exec: string): boolean {
  return exec === 'ramping-arrival-rate' || exec === 'ramping-vus';
}

function applyWarmupTransitionToOp(op: any, nextWarmOn: boolean, nextWarmDur: string): any {
  const ex = op.executor || 'ramping-arrival-rate';
  const sc = { ...(op.scenario || {}) };
  const dur = nextWarmDur.trim() || '2m';

  if (isRampingExecutor(ex)) {
    const stages: K6Stage[] = Array.isArray(sc.stages) ? [...sc.stages] : [];
    const hadWarmStage = Number(sc.warmup?.stages) > 0;
    if (nextWarmOn) {
      if (hadWarmStage && stages.length > 0) {
        stages[0] = { ...stages[0], duration: dur };
      } else {
        const firstTarget = Number(stages[0]?.target) || (ex.includes('arrival') ? 50 : 20);
        const warmTarget = Math.max(1, Math.round(firstTarget * 0.3));
        stages.unshift({ duration: dur, target: warmTarget });
      }
      sc.stages = stages;
      sc.warmup = { stages: 1 };
    } else {
      if (hadWarmStage && stages.length > 1) {
        stages.shift();
      }
      sc.stages = stages;
      delete sc.warmup;
    }
  } else if (supportsConstantWarmup(ex)) {
    if (nextWarmOn) {
      sc.warmup = { duration: dur };
    } else {
      delete sc.warmup;
    }
  } else {
    delete sc.warmup;
  }

  return { ...op, scenario: sc };
}

interface TrajectoryPoint {
  t: number;
  v: number;
}

function sampleTrajectoryAt(pts: TrajectoryPoint[], tSec: number): number {
  if (!pts.length) return 0;
  if (tSec < pts[0].t - 1e-6) return 0;
  if (tSec > pts[pts.length - 1].t + 1e-6) return 0;

  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i];
    const b = pts[i + 1];
    if (tSec >= a.t - 1e-6 && tSec <= b.t + 1e-6) {
      const span = b.t - a.t;
      if (span <= 1e-6) return b.v;
      const ratio = Math.max(0, Math.min(1, (tSec - a.t) / span));
      return a.v + (b.v - a.v) * ratio;
    }
  }
  return pts[pts.length - 1].v;
}

function buildOpSummary(op: any, globalWarmDur: string) {
  const ex = op.executor || 'ramping-arrival-rate';
  const sc = op.scenario || {};
  const exMeta = EXECUTORS.find((e) => e.value === ex);
  const unit = stepUnitShort(ex, sc.timeUnit);
  let trajectory = '';
  let durSec = 0;

  if (ex === 'constant-arrival-rate') {
    trajectory = `${sc.rate ?? 0} ${unit}`;
    durSec = parseDurationToSec(sc.duration || '5m') + parseDurationToSec(sc.warmup?.duration || '');
  } else if (ex === 'constant-vus' || ex === 'externally-controlled') {
    trajectory = `${sc.vus ?? 0} VU`;
    durSec = parseDurationToSec(sc.duration || '5m') + parseDurationToSec(sc.warmup?.duration || '');
  } else if (ex === 'per-vu-iterations' || ex === 'shared-iterations') {
    trajectory = `${sc.vus ?? 0} VU × ${sc.iterations ?? 0} ит.`;
    durSec = parseDurationToSec(sc.maxDuration || '10m');
  } else if (isRampingExecutor(ex)) {
    const warmCnt = Number(sc.warmup?.stages) > 0 ? 1 : 0;
    const parsed = parseStepsFromStages(
      sc.stages || [],
      Number(ex.includes('arrival') ? sc.startRate : sc.startVUs) || 0,
      warmCnt,
      globalWarmDur
    );
    durSec = (sc.stages || []).reduce((acc: number, s: any) => acc + parseDurationToSec(s.duration), 0);
    if (parsed.steps.length > 0) {
      const targets = parsed.steps.map((s) => s.target);
      trajectory =
        targets.length <= 5
          ? `${targets.join(' → ')} ${unit}`
          : `${targets[0]} → … → ${targets[targets.length - 1]} ${unit} (${targets.length} ступ.)`;
    } else {
      trajectory = `${sc.stages?.length || 0} стадий`;
    }
  }

  return {
    shortExec: exMeta?.short || ex,
    trajectory,
    durText: durSec > 0 ? fmtDurationSec(durSec) : '—',
  };
}

function summarizeProfileData(data: any) {
  const ops = (data?.operations || data?.ops || []).filter((o: any) => o.enabled !== false);
  let peakRate = 0;
  let peakVu = 0;
  let maxDurSec = 0;
  let warmup = data?.baseWarmupDuration || '';

  ops.forEach((o: any) => {
    const ex = o.executor || 'ramping-arrival-rate';
    const sc = o.scenario || {};
    const startSec = parseDurationToSec(sc.startTime);
    if (!warmup) {
      if (sc.warmup?.duration) warmup = sc.warmup.duration;
      else if (sc.warmup?.stages && sc.stages?.[0]?.duration) warmup = sc.stages[0].duration;
    }
    if (ex === 'constant-arrival-rate') {
      const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
      peakRate += (Number(sc.rate) || 0) / tu;
      peakVu += Number(sc.maxVUs || sc.preAllocatedVUs || 0);
      maxDurSec = Math.max(maxDurSec, startSec + parseDurationToSec(sc.duration) + parseDurationToSec(sc.warmup?.duration));
    } else if (ex === 'ramping-arrival-rate') {
      const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
      let opPeak = (Number(sc.startRate) || 0) / tu;
      let dur = startSec;
      (sc.stages || []).forEach((st: any) => {
        dur += parseDurationToSec(st.duration);
        const r = (Number(st.target) || 0) / tu;
        if (r > opPeak) opPeak = r;
      });
      peakRate += opPeak;
      peakVu += Number(sc.maxVUs || sc.preAllocatedVUs || 0);
      maxDurSec = Math.max(maxDurSec, dur);
    } else if (ex === 'ramping-vus') {
      let opPeak = Number(sc.startVUs) || 0;
      let dur = startSec;
      (sc.stages || []).forEach((st: any) => {
        dur += parseDurationToSec(st.duration);
        if ((Number(st.target) || 0) > opPeak) opPeak = Number(st.target) || 0;
      });
      peakVu += opPeak;
      maxDurSec = Math.max(maxDurSec, dur);
    } else {
      peakVu += Number(sc.maxVUs || sc.vus || 0);
      maxDurSec = Math.max(maxDurSec, startSec + parseDurationToSec(sc.duration || sc.maxDuration) + parseDurationToSec(sc.warmup?.duration));
    }
  });

  const envCnt = data?.env ? Object.keys(data.env).length : 0;
  return {
    opsCnt: ops.length,
    peakRate: Math.round(peakRate * 10) / 10,
    peakVu,
    maxDurSec,
    warmup,
    envCnt,
    gens: data?.generatorCount || 1,
  };
}

function parseEnvText(text: string): { k: string; v: string }[] {
  const out: { k: string; v: string }[] = [];
  String(text || '')
    .split(/\r?\n/)
    .forEach((line) => {
      const s = line.trim();
      if (!s || s.startsWith('#')) return;
      const eq = s.indexOf('=');
      if (eq > 0) out.push({ k: s.slice(0, eq).trim(), v: s.slice(eq + 1) });
      else out.push({ k: s, v: '' });
    });
  return out;
}

function formatEnvRows(rows: { k: string; v: string }[]): string {
  return rows
    .filter((r) => r.k.trim() !== '')
    .map((r) => `${r.k.trim()}=${r.v}`)
    .join('\n');
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
  const [envMode, setEnvMode] = useState<'table' | 'raw'>('table');
  const [envRawText, setEnvRawText] = useState('');
  const [focusedEnvRowIdx, setFocusedEnvRowIdx] = useState<number | null>(null);
  const [stop, setStop] = useState<any>({});
  const [dataFiles, setDataFiles] = useState<any[]>([]);
  const [profileName, setProfileName] = useState('');
  const [warmupOn, setWarmupOn] = useState(false);
  const [warmupDur, setWarmupDur] = useState('2m');
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  // Scenario clipboard (sessionStorage)
  const [copiedScenario, setCopiedScenario] = useState<{ executor: string; scenario: any } | null>(() => {
    try {
      return JSON.parse(sessionStorage.getItem('k6lab-scenario') || 'null');
    } catch {
      return null;
    }
  });

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
  const [advScenarioOpen, setAdvScenarioOpen] = useState<Record<number, boolean>>({});
  const [opSearch, setOpSearch] = useState('');
  const [onlyEnabled, setOnlyEnabled] = useState(false);
  const [chartStacked, setChartStacked] = useState(true);
  const [chartUnitMode, setChartUnitMode] = useState<'rate' | 'vu'>('rate');
  const [profilesModalOpen, setProfilesModalOpen] = useState(false);
  const [profSearch, setProfSearch] = useState('');
  const [selectedProfIds, setSelectedProfIds] = useState<Set<number>>(new Set());
  const [lastClickedProfId, setLastClickedProfId] = useState<number | null>(null);
  const [renameModal, setRenameModal] = useState<{ id: number; name: string } | null>(null);

  // Import/export modal
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importJson, setImportJson] = useState('');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importDragOver, setImportDragOver] = useState(false);
  const [importSeq, setImportSeq] = useState(false);
  const [importRunAt, setImportRunAt] = useState('');
  const [importCount, setImportCount] = useState<string>('');
  const [importGap, setImportGap] = useState(5);
  const [importShift, setImportShift] = useState(true);
  const [importing, setImporting] = useState(false);

  // Advanced k6 JSON config & requirements
  const [advOpen, setAdvOpen] = useState(false);
  const [editedConfig, setEditedConfig] = useState('');
  const [reqInfo, setReqInfo] = useState<{ maxVus: string; totalDuration: string } | null>(null);
  const [reqLoading, setReqLoading] = useState(false);

  const hydrateFromServer = useCallback((st: any) => {
    const f = st.form || {};
    const nextOps = (f.ops || []).map((o: any) => ({
      ...o,
      scenario:
        o.scenario && Object.keys(o.scenario).length > 0
          ? JSON.parse(JSON.stringify(o.scenario))
          : defaultScenarioFor(o.executor || 'ramping-arrival-rate'),
    }));
    setOps(nextOps);
    setGeneratorCount(f.generatorCount || st.script?.generatorCount || 1);
    const entries = Object.entries(st.env || {}).map(([k, v]) => ({ k, v: String(v ?? '') }));
    const nextEnvRows = entries.length > 0 ? entries : [{ k: '', v: '' }];
    setEnvRows(nextEnvRows);
    setEnvRawText(formatEnvRows(entries));
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

    if (st.basePushDefaults) {
      const d = st.basePushDefaults;
      const anyDef = Object.values(d).some(Boolean);
      if (anyDef) {
        setPushOpts({
          env: !!d.env,
          stop: !!d.stop,
          sla: !!d.sla,
          warmup: !!d.warmup,
          data: !!d.data,
          generators: !!d.generators,
        });
      }
    }

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
    setDirty(false);
    if (nextOps.length > 4) {
      const col: Record<number, boolean> = {};
      nextOps.forEach((_: any, idx: number) => {
        col[idx] = true;
      });
      setCollapsedOps(col);
    } else {
      setCollapsedOps({});
    }
  }, []);

  useEffect(() => {
    if (loadState) hydrateFromServer(loadState);
  }, [loadState, hydrateFromServer]);

  const toggleGlobalWarmup = (nextOn: boolean) => {
    setWarmupOn(nextOn);
    setOps((prev) => prev.map((o) => applyWarmupTransitionToOp(o, nextOn, warmupDur)));
    setDirty(true);
  };

  const changeGlobalWarmupDur = (nextDur: string) => {
    setWarmupDur(nextDur);
    if (warmupOn) {
      const sec = parseDurationToSec(nextDur);
      if (sec >= 5) {
        setOps((prev) => prev.map((o) => applyWarmupTransitionToOp(o, true, nextDur)));
      }
    }
    setDirty(true);
  };

  const validateWarmupDuration = (): boolean => {
    if (!warmupOn) return true;
    const sec = parseDurationToSec(warmupDur);
    if (sec < 5 || sec > 3600) {
      toast.error('Длительность общего прогрева должна быть от 5s до 1h (например 30s, 2m, 5m)');
      return false;
    }
    return true;
  };

  const buildPayloadForm = () => {
    const activeEnvRows = envMode === 'raw' ? parseEnvText(envRawText) : envRows;
    const envObj: Record<string, string> = {};
    activeEnvRows.forEach((r) => {
      if (r.k.trim()) envObj[r.k.trim()] = r.v;
    });

    const preparedOps = ops.map((o) => applyWarmupTransitionToOp(o, warmupOn, warmupDur));

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
    if (!validateWarmupDuration()) return;
    setSaving(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/load`, {
        method: 'POST',
        body: buildPayloadForm(),
      });
      toast.success(res.message || 'Нагрузка сохранена');
      setDirty(false);
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
    if (!validateWarmupDuration()) return;
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
      setDirty(false);
      const pidStr = String(res.savedProfileId);
      setSearchParams({ profile: pidStr });
      qc.setQueryData(['script-load', id, pidStr], res);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  // Ctrl+S / Cmd+S shortcut
  const saveActionRef = useRef<() => void>(() => {});
  saveActionRef.current = () => {
    if (saving) return;
    if (loadState?.loadedProfileId) handleSaveProfile();
    else handleSaveBase();
  };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        saveActionRef.current();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const handleApplyBaseToProfiles = async () => {
    if (!validateWarmupDuration()) return;
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
      setDirty(false);
      qc.setQueryData(['script-load', id, profileParam], res);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  // Accurate Piecewise-Linear Live Chart & KPI computation
  const kpiAndChart = useMemo(() => {
    const active = ops.filter((o) => o.enabled);
    let peakRate = 0;
    let maxDurSec = 0;
    let totalMaxVus = 0;
    let totalPreVus = 0;
    let maxWarmupSec = warmupOn ? parseDurationToSec(warmupDur) : 0;

    interface BuiltSeries {
      name: string;
      color: string;
      kind: 'rate' | 'vu';
      pts: TrajectoryPoint[];
    }

    const rateSeries: BuiltSeries[] = [];
    const vuSeries: BuiltSeries[] = [];
    const breakpointsSec = new Set<number>([0]);

    const addBreakpoint = (sec: number, isCliff = false) => {
      if (!Number.isFinite(sec) || sec < 0) return;
      if (isCliff && sec > 0.05) breakpointsSec.add(sec - 0.01);
      breakpointsSec.add(sec);
    };

    active.forEach((o, idx) => {
      const ex = o.executor || 'ramping-arrival-rate';
      const sc = o.scenario || {};
      const startSec = parseDurationToSec(sc.startTime);
      const color = COLORS[idx % COLORS.length];
      const label = o.name || o.execFn;
      const pts: TrajectoryPoint[] = [];

      addBreakpoint(startSec, startSec > 0);

      if (ex === 'constant-arrival-rate') {
        const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
        const r = (Number(sc.rate) || 0) / tu;
        const wSec = sc.warmup?.duration ? parseDurationToSec(sc.warmup.duration) : 0;
        if (wSec > 0) maxWarmupSec = Math.max(maxWarmupSec, startSec + wSec);
        const mainDur = parseDurationToSec(sc.duration || '5m');
        const totalD = wSec + mainDur;
        peakRate += r;
        maxDurSec = Math.max(maxDurSec, startSec + totalD);
        totalPreVus += Number(sc.preAllocatedVUs || 0) * Math.max(1, generatorCount);
        totalMaxVus += Number(sc.maxVUs || sc.preAllocatedVUs || 0) * Math.max(1, generatorCount);
        if (wSec > 0) {
          const wRate = Math.max(0.1, Math.round(r * 0.3 * 100) / 100);
          pts.push({ t: startSec, v: wRate }, { t: startSec + wSec, v: wRate });
          addBreakpoint(startSec + wSec, true);
          pts.push({ t: startSec + wSec, v: r }, { t: startSec + totalD, v: r });
        } else {
          pts.push({ t: startSec, v: r }, { t: startSec + totalD, v: r });
        }
        addBreakpoint(startSec + totalD);
        rateSeries.push({ name: label, color, kind: 'rate', pts });
      } else if (ex === 'ramping-arrival-rate') {
        const tu = Math.max(1, parseDurationToSec(sc.timeUnit || '1s'));
        let curT = startSec;
        const curR = (Number(sc.startRate) || 0) / tu;
        let opPeak = curR;
        pts.push({ t: curT, v: curR });
        if (sc.warmup?.stages && sc.stages?.[0]) {
          maxWarmupSec = Math.max(maxWarmupSec, startSec + parseDurationToSec(sc.stages[0].duration));
        }
        (sc.stages || []).forEach((st: any) => {
          const d = parseDurationToSec(st.duration);
          const target = (Number(st.target) || 0) / tu;
          if (d <= 0) {
            addBreakpoint(curT, true);
            pts.push({ t: curT, v: target });
          } else {
            curT += d;
            addBreakpoint(curT);
            pts.push({ t: curT, v: target });
          }
          if (target > opPeak) opPeak = target;
        });
        peakRate += opPeak;
        maxDurSec = Math.max(maxDurSec, curT);
        totalPreVus += Number(sc.preAllocatedVUs || 0) * Math.max(1, generatorCount);
        totalMaxVus += Number(sc.maxVUs || sc.preAllocatedVUs || 0) * Math.max(1, generatorCount);
        rateSeries.push({ name: label, color, kind: 'rate', pts });
      } else if (ex === 'ramping-vus') {
        let curT = startSec;
        const startVu = Number(sc.startVUs) || 0;
        let opPeakV = startVu;
        pts.push({ t: curT, v: startVu });
        if (sc.warmup?.stages && sc.stages?.[0]) {
          maxWarmupSec = Math.max(maxWarmupSec, startSec + parseDurationToSec(sc.stages[0].duration));
        }
        (sc.stages || []).forEach((st: any) => {
          const d = parseDurationToSec(st.duration);
          const target = Number(st.target) || 0;
          if (d <= 0) {
            addBreakpoint(curT, true);
            pts.push({ t: curT, v: target });
          } else {
            curT += d;
            addBreakpoint(curT);
            pts.push({ t: curT, v: target });
          }
          if (target > opPeakV) opPeakV = target;
        });
        totalMaxVus += opPeakV;
        maxDurSec = Math.max(maxDurSec, curT);
        vuSeries.push({ name: label, color, kind: 'vu', pts });
      } else {
        const v = Number(sc.vus) || 0;
        const wSec = sc.warmup?.duration ? parseDurationToSec(sc.warmup.duration) : 0;
        if (wSec > 0) maxWarmupSec = Math.max(maxWarmupSec, startSec + wSec);
        const d = parseDurationToSec(sc.duration || sc.maxDuration || '5m') + wSec;
        totalMaxVus += Number(sc.maxVUs || v);
        maxDurSec = Math.max(maxDurSec, startSec + d);
        pts.push({ t: startSec, v }, { t: startSec + d, v });
        addBreakpoint(startSec + d);
        vuSeries.push({ name: label, color, kind: 'vu', pts });
      }
    });

    const hasBoth = rateSeries.length > 0 && vuSeries.length > 0;
    const effectiveMode: 'rate' | 'vu' = hasBoth
      ? chartUnitMode
      : rateSeries.length > 0
      ? 'rate'
      : 'vu';
    const targetSeries = effectiveMode === 'rate' ? rateSeries : vuSeries;

    const sortedSec = Array.from(breakpointsSec).sort((a, b) => a - b);
    const datasets = targetSeries.map((sr) => ({
      label: sr.name,
      data: sortedSec.map((sec) => ({
        x: Math.round((sec / 60) * 10000) / 10000,
        y: Math.round(sampleTrajectoryAt(sr.pts, sec) * 100) / 100,
      })),
      borderColor: sr.color,
      backgroundColor: sr.color + '28',
      borderWidth: 2,
      fill: chartStacked,
      tension: 0,
      pointRadius: sortedSec.length > 24 ? 0 : 2,
      pointHoverRadius: 4,
    }));

    return {
      activeCount: active.length,
      peakRate: Math.round(peakRate * 10) / 10,
      maxDurSec,
      totalMaxVus,
      totalPreVus,
      warmupMin: maxWarmupSec > 0 ? maxWarmupSec / 60 : 0,
      hasBothUnits: hasBoth,
      effectiveMode,
      datasets,
    };
  }, [ops, chartStacked, chartUnitMode, warmupOn, warmupDur, generatorCount]);

  // Chart.js plugin for amber Warmup zone band
  const warmupBandPlugin = useMemo<Plugin<'line'>>(
    () => ({
      id: 'ldWarmupBand',
      beforeDatasetsDraw(chart) {
        const wMin = kpiAndChart.warmupMin;
        if (!wMin || wMin <= 0) return;
        const { ctx, chartArea, scales } = chart;
        if (!chartArea || !scales.x) return;
        const x0 = Math.max(chartArea.left, scales.x.getPixelForValue(0));
        const x1 = Math.min(chartArea.right, scales.x.getPixelForValue(wMin));
        if (x1 <= x0 + 2) return;
        ctx.save();
        ctx.fillStyle = 'rgba(245, 158, 11, 0.10)';
        ctx.fillRect(x0, chartArea.top, x1 - x0, chartArea.bottom - chartArea.top);
        ctx.strokeStyle = 'rgba(217, 119, 6, 0.55)';
        ctx.setLineDash([4, 4]);
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(x1, chartArea.top);
        ctx.lineTo(x1, chartArea.bottom);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = '#b45309';
        ctx.font = '600 10px Inter, system-ui, sans-serif';
        if (x1 - x0 > 46) {
          ctx.fillText('Прогрев', x0 + 6, chartArea.top + 13);
        }
        ctx.restore();
      },
    }),
    [kpiAndChart.warmupMin]
  );

  if (isLoading || !loadState) return <Spinner label="Загрузка конструктора нагрузки…" />;

  const {
    script,
    loadedProfileId,
    loadedProfileName,
    profiles = [],
    secretNames = [],
    scriptCharts = [],
    staleOps = [],
    plan,
  } = loadState;

  const filteredProfiles = profiles.filter((p: any) =>
    (p.name || '').toLowerCase().includes(profSearch.trim().toLowerCase())
  );

  const updateOp = (idx: number, patch: any) => {
    setOps((prev) => prev.map((item, i) => (i === idx ? { ...item, ...patch } : item)));
    setDirty(true);
  };

  const updateScenario = (idx: number, patch: any) => {
    setOps((prev) =>
      prev.map((item, i) => {
        if (i !== idx) return item;
        const nextSc = { ...(item.scenario || {}), ...patch };
        Object.keys(nextSc).forEach((k) => {
          if (nextSc[k] === undefined || nextSc[k] === '') delete nextSc[k];
        });
        return { ...item, scenario: nextSc };
      })
    );
    setDirty(true);
  };

  const switchExecutor = (idx: number, nextEx: string) => {
    setOps((prev) =>
      prev.map((item, i) => {
        if (i !== idx) return item;
        const oldSc = item.scenario || {};
        const def = defaultScenarioFor(nextEx);
        const allowed = new Set(allowedKeysForExecutor(nextEx));
        const merged: Record<string, any> = { ...def };
        Object.keys(oldSc).forEach((k) => {
          if (!allowed.has(k)) return;
          if (k === 'warmup') return;
          if (k === 'stages' && (!Array.isArray(oldSc.stages) || oldSc.stages.length === 0)) return;
          if (oldSc[k] !== undefined) merged[k] = oldSc[k];
        });
        return applyWarmupTransitionToOp({ ...item, executor: nextEx, scenario: merged }, warmupOn, warmupDur);
      })
    );
    setDirty(true);
  };

  const nextUniqueOpName = (baseFn: string): string => {
    const taken = new Set(ops.map((o) => String(o.name || '').trim()));
    let n = 2;
    while (taken.has(`${baseFn}_${n}`)) n++;
    return `${baseFn}_${n}`;
  };

  const anyExpandedOp = ops.some((o, idx) => o.enabled && !collapsedOps[idx]);

  const toggleCollapseAllOps = () => {
    if (anyExpandedOp) {
      const next: Record<number, boolean> = {};
      ops.forEach((_, idx) => {
        next[idx] = true;
      });
      setCollapsedOps(next);
    } else {
      setCollapsedOps({});
    }
  };

  const insertSecretToken = (secName: string) => {
    const token = `\${secret:${secName}}`;
    if (envMode === 'raw') {
      setEnvRawText((prev) => (prev.trim() ? `${prev.trim()}\n${secName}=${token}` : `${secName}=${token}`));
      setDirty(true);
      toast.success(`Добавлена переменная ${secName}`);
      return;
    }
    if (focusedEnvRowIdx !== null && envRows[focusedEnvRowIdx]) {
      setEnvRows((prev) =>
        prev.map((r, i) =>
          i === focusedEnvRowIdx ? { k: r.k.trim() ? r.k : secName, v: token } : r
        )
      );
    } else {
      const emptyIdx = envRows.findIndex((r) => !r.k.trim() && !r.v.trim());
      if (emptyIdx >= 0) {
        setEnvRows((prev) =>
          prev.map((r, i) => (i === emptyIdx ? { k: secName, v: token } : r))
        );
      } else {
        setEnvRows((prev) => [...prev, { k: secName, v: token }]);
      }
    }
    setDirty(true);
    toast.success(`Подставлен секрет ${token}`);
  };

  const envCount =
    envMode === 'raw' ? parseEnvText(envRawText).length : envRows.filter((r) => r.k.trim()).length;

  const stopEtaSec = Math.max(1, Number(stop.consecutive) || 4) * 15;

  return (
    <div className="space-y-4 pb-20">
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

      {plan?.warnings?.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900 space-y-1">
          {plan.warnings.map((w: string, i: number) => (
            <div key={i} className="flex items-start gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-amber-600 shrink-0 mt-0.5" />
              <span>{w}</span>
            </div>
          ))}
        </div>
      )}

      {staleOps.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900">
          В текущей версии скрипта отсутствуют функции: <code>{staleOps.join(', ')}</code> — они исключены из запуска.
        </div>
      )}

      {/* Верхняя панель профилей нагрузки + быстрые якоря */}
      <Card className="p-3 space-y-2.5">
        <div className="flex flex-wrap items-center justify-between gap-3">
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
                onChange={(e) => {
                  setProfileName(e.target.value);
                  setDirty(true);
                }}
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
              <FormulaInput
                value={generatorCount}
                integer
                min={1}
                onChange={(v) => {
                  setGeneratorCount(Math.max(1, Math.min(200, v)));
                  setDirty(true);
                }}
                className="w-20"
              />
            </div>
            <Button variant="outline" size="sm" onClick={() => setImportModalOpen(true)}>
              <ArrowDownUp className="h-3.5 w-3.5" />
              Импорт / экспорт
            </Button>
          </div>
        </div>

        {/* Навигация по разделам */}
        <div className="flex flex-wrap items-center gap-1.5 pt-2 border-t border-slate-100 text-xs">
          {[
            { href: '#sec-chart', label: 'Профиль нагрузки' },
            { href: '#sec-ops', label: `Операции (${kpiAndChart.activeCount}/${ops.length})` },
            { href: '#sec-env', label: `Переменные (${envCount})` },
            { href: '#sec-stop', label: `Автостоп и SLA (${(stop.customChecks || []).length})` },
            ...(dataFiles.length > 0 ? [{ href: '#sec-data', label: `CSV данные (${dataFiles.length})` }] : []),
            { href: '#sec-json', label: 'Итоговый JSON k6' },
          ].map((nav) => (
            <a
              key={nav.href}
              href={nav.href}
              className="rounded-lg bg-slate-100/80 px-2.5 py-1 font-medium text-slate-600 hover:bg-indigo-50 hover:text-indigo-700 transition-colors"
            >
              {nav.label}
            </a>
          ))}
          {dirty && (
            <span className="ml-auto inline-flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold text-amber-800">
              ● Есть несохранённые правки (Ctrl+S)
            </span>
          )}
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
      <div id="sec-chart" className="grid grid-cols-1 lg:grid-cols-12 gap-4 scroll-mt-4">
        <Card className="lg:col-span-8 p-4 flex flex-col">
          <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
            <div className="text-xs font-semibold text-slate-800 flex items-center gap-1.5">
              <Activity className="h-4 w-4 text-indigo-600" />
              Визуальный профиль нагрузки (обновляется на лету)
              {kpiAndChart.warmupMin > 0 && (
                <Badge variant="warning">Прогрев {warmupDur}</Badge>
              )}
            </div>
            <div className="flex items-center gap-1.5">
              {kpiAndChart.hasBothUnits && (
                <div className="inline-flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-[11px]">
                  <button
                    type="button"
                    onClick={() => setChartUnitMode('rate')}
                    className={`rounded-md px-2 py-0.5 font-semibold cursor-pointer ${
                      kpiAndChart.effectiveMode === 'rate' ? 'bg-indigo-600 text-white' : 'text-slate-600'
                    }`}
                  >
                    оп/с
                  </button>
                  <button
                    type="button"
                    onClick={() => setChartUnitMode('vu')}
                    className={`rounded-md px-2 py-0.5 font-semibold cursor-pointer ${
                      kpiAndChart.effectiveMode === 'vu' ? 'bg-indigo-600 text-white' : 'text-slate-600'
                    }`}
                  >
                    VU
                  </button>
                </div>
              )}
              <Button variant="outline" size="xs" onClick={() => setChartStacked((v) => !v)}>
                {chartStacked ? 'Суммарно (стек)' : 'Отдельные линии'}
              </Button>
            </div>
          </div>
          <div className="h-60 w-full">
            {kpiAndChart.datasets.length === 0 ? (
              <div className="h-full flex items-center justify-center text-xs text-slate-400">
                Включите хотя бы одну операцию, чтобы увидеть кривую нагрузки
              </div>
            ) : (
              <Line
                key={`ld-chart-${kpiAndChart.warmupMin}-${chartStacked}-${kpiAndChart.effectiveMode}`}
                data={{ datasets: kpiAndChart.datasets }}
                plugins={[warmupBandPlugin]}
                options={{
                  responsive: true,
                  maintainAspectRatio: false,
                  parsing: false,
                  interaction: { mode: 'index', intersect: false },
                  scales: {
                    x: {
                      type: 'linear',
                      min: 0,
                      title: { display: true, text: 'Время, мин', font: { size: 11 } },
                    },
                    y: {
                      stacked: chartStacked,
                      beginAtZero: true,
                      title: {
                        display: true,
                        text: kpiAndChart.effectiveMode === 'rate' ? 'Интенсивность, оп/с' : 'Виртуальные пользователи (VU)',
                        font: { size: 11 },
                      },
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
              {generatorCount > 1 && (
                <div className="text-[11px] text-indigo-600 mt-0.5">на {generatorCount} генераторах</div>
              )}
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">
                {kpiAndChart.peakRate > 0 ? 'Пик интенсивности' : 'Пик нагрузки (VU)'}
              </div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">
                {kpiAndChart.peakRate > 0
                  ? `${kpiAndChart.peakRate} оп/с`
                  : kpiAndChart.totalMaxVus > 0
                  ? `${kpiAndChart.totalMaxVus} VU`
                  : '—'}
              </div>
              {generatorCount > 1 && kpiAndChart.peakRate > 0 && (
                <div className="text-[11px] text-slate-500 mt-0.5">
                  ≈ {Math.round((kpiAndChart.peakRate / generatorCount) * 10) / 10} оп/с на ген.
                </div>
              )}
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Общая длительность</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">{fmtDurationSec(kpiAndChart.maxDurSec)}</div>
              {warmupOn && <div className="text-[11px] text-amber-700 mt-0.5">вкл. прогрев {warmupDur}</div>}
            </Card>
            <Card className="p-3">
              <div className="text-[11px] text-slate-500">Пул VU (всего)</div>
              <div className="text-lg font-bold text-slate-900 mt-0.5">{kpiAndChart.totalMaxVus || '—'}</div>
              {kpiAndChart.totalPreVus > 0 && (
                <div className="text-[11px] text-slate-500 mt-0.5">preAlloc: {kpiAndChart.totalPreVus}</div>
              )}
            </Card>
          </div>

          <Card className="p-3.5 space-y-2.5">
            <div className="flex items-center justify-between gap-2">
              <Switch
                checked={warmupOn}
                onChange={toggleGlobalWarmup}
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
                onChange={(e) => changeGlobalWarmupDur(e.target.value)}
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
                    if (!warmupOn) {
                      setWarmupDur(p);
                      setWarmupOn(true);
                      setOps((prev) => prev.map((o) => applyWarmupTransitionToOp(o, true, p)));
                      setDirty(true);
                    } else {
                      changeGlobalWarmupDur(p);
                    }
                  }}
                  className={`rounded-md border px-2 py-0.5 text-[11px] font-mono cursor-pointer ${
                    warmupOn && warmupDur === p
                      ? 'border-amber-400 bg-amber-100 text-amber-900 font-semibold'
                      : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-amber-50 hover:border-amber-300'
                  }`}
                >
                  {p}
                </button>
              ))}
            </div>
            <p className="text-[11px] text-slate-500 leading-snug">
              Для стадий автоматически создаёт 1-ю прогревочную стадию (30% от старта). Ошибки и время ответа на прогреве не портят итоги и SLA ступеней.
            </p>
          </Card>
        </div>
      </div>

      {/* Раздел 1: Операции и сценарии */}
      <div id="sec-ops" className="space-y-2.5 scroll-mt-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-base font-bold text-slate-900">Операции и сценарии ({ops.length})</h2>
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5 text-xs">
              <button
                type="button"
                onClick={() => setOnlyEnabled(false)}
                className={`rounded-md px-2.5 py-1 font-medium cursor-pointer ${
                  !onlyEnabled ? 'bg-indigo-600 text-white' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                Все ({ops.length})
              </button>
              <button
                type="button"
                onClick={() => setOnlyEnabled(true)}
                className={`rounded-md px-2.5 py-1 font-medium cursor-pointer ${
                  onlyEnabled ? 'bg-indigo-600 text-white' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                Вкл. ({kpiAndChart.activeCount})
              </button>
            </div>
            <Button variant="outline" size="xs" onClick={toggleCollapseAllOps}>
              {anyExpandedOp ? (
                <>
                  <Minimize2 className="h-3.5 w-3.5" />
                  Свернуть все
                </>
              ) : (
                <>
                  <Maximize2 className="h-3.5 w-3.5" />
                  Развернуть все
                </>
              )}
            </Button>
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
            const isRamping = isRampingExecutor(ex);
            const isArrival = ex.includes('arrival');
            const summary = buildOpSummary(op, warmupDur);

            const advCount =
              (sc.startTime ? 1 : 0) +
              (sc.gracefulStop ? 1 : 0) +
              (sc.gracefulRampDown ? 1 : 0) +
              (sc.env ? Object.keys(sc.env).length : 0) +
              (sc.tags ? Object.keys(sc.tags).length : 0);

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
                    className="h-8 w-44 text-xs font-medium bg-white"
                  />
                  {!collapsed ? (
                    <Select
                      value={ex}
                      onChange={(e) => switchExecutor(idx, e.target.value)}
                      className="h-8 text-xs w-72 bg-white"
                    >
                      {EXECUTORS.map((eItem) => (
                        <option key={eItem.value} value={eItem.value}>
                          {eItem.label}
                        </option>
                      ))}
                    </Select>
                  ) : (
                    /* Компактная информационная сводка на свёрнутой карточке */
                    <div
                      onClick={() => setCollapsedOps((p) => ({ ...p, [idx]: false }))}
                      className="flex flex-wrap items-center gap-1.5 text-[11px] cursor-pointer"
                    >
                      <Badge variant="neutral">{summary.shortExec}</Badge>
                      <span className="rounded-md bg-white border border-slate-200 px-2 py-0.5 font-mono font-semibold text-slate-800">
                        {summary.trajectory}
                      </span>
                      <span className="text-slate-500">⏱ {summary.durText}</span>
                      {(sc.warmup?.duration || sc.warmup?.stages) && (
                        <Badge variant="warning">
                          🔥 {sc.warmup?.duration || sc.stages?.[0]?.duration || warmupDur}
                        </Badge>
                      )}
                      <span className="text-slate-400">
                        SLA p{op.slaPercentile || 95} ≤ {op.slaThresholdMs ?? 1000}мс · ош. ≤ {op.maxErrorPct ?? 1}%
                      </span>
                    </div>
                  )}

                  <div className="ml-auto flex items-center gap-1">
                    <Button
                      variant="ghost"
                      size="xs"
                      title="Скопировать настройки сценария в буфер"
                      onClick={() => {
                        const clip = {
                          executor: ex,
                          scenario: JSON.parse(JSON.stringify(sc)),
                        };
                        try {
                          sessionStorage.setItem('k6lab-scenario', JSON.stringify(clip));
                        } catch {}
                        setCopiedScenario(clip);
                        toast.success(`Сценарий «${op.name || op.execFn}» скопирован`);
                      }}
                    >
                      <ClipboardCopy className="h-3.5 w-3.5" />
                    </Button>
                    {copiedScenario && (
                      <Button
                        variant="ghost"
                        size="xs"
                        title="Вставить скопированный сценарий"
                        onClick={() => {
                          const pasted = applyWarmupTransitionToOp(
                            {
                              ...op,
                              executor: copiedScenario.executor,
                              scenario: JSON.parse(JSON.stringify(copiedScenario.scenario || {})),
                            },
                            warmupOn,
                            warmupDur
                          );
                          updateOp(idx, { executor: pasted.executor, scenario: pasted.scenario });
                          toast.success('Настройки сценария вставлены');
                        }}
                      >
                        <ClipboardPaste className="h-3.5 w-3.5 text-indigo-600" />
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="xs"
                      title="Добавить ещё один сценарий для этой функции"
                      onClick={() => {
                        const copy = JSON.parse(JSON.stringify(op));
                        copy.name = nextUniqueOpName(op.name || op.execFn);
                        setOps((prev) => [...prev.slice(0, idx + 1), copy, ...prev.slice(idx + 1)]);
                        setDirty(true);
                      }}
                    >
                      <Copy className="h-3.5 w-3.5" />
                      Дублировать
                    </Button>
                    {ops.filter((x) => x.execFn === op.execFn).length > 1 && (
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setOps((prev) => prev.filter((_, i) => i !== idx));
                          setDirty(true);
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                      </Button>
                    )}
                  </div>
                </div>

                {!collapsed && (
                  <CardBody className="space-y-3 text-xs">
                    {/* Поля исполнителя с поддержкой формул */}
                    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2.5">
                      {ex === 'constant-arrival-rate' && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Интенсивность (rate)</label>
                            <FormulaInput
                              value={sc.rate ?? 50}
                              onChange={(v) => updateScenario(idx, { rate: v })}
                              className="w-full"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Интервал (timeUnit)</label>
                            <Input
                              value={sc.timeUnit ?? '1s'}
                              onChange={(e) => updateScenario(idx, { timeUnit: e.target.value })}
                              className="h-7 text-xs font-mono"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Длительность</label>
                            <Input
                              value={sc.duration ?? '5m'}
                              onChange={(e) => updateScenario(idx, { duration: e.target.value })}
                              className="h-7 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}

                      {ex === 'ramping-arrival-rate' && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Старт (startRate)</label>
                            <FormulaInput
                              value={sc.startRate ?? 0}
                              onChange={(v) => updateScenario(idx, { startRate: v })}
                              className="w-full"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Интервал (timeUnit)</label>
                            <Input
                              value={sc.timeUnit ?? '1s'}
                              onChange={(e) => updateScenario(idx, { timeUnit: e.target.value })}
                              className="h-7 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}

                      {isArrival && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">preAllocatedVUs / ген.</label>
                            <FormulaInput
                              value={sc.preAllocatedVUs ?? 20}
                              integer
                              min={1}
                              onChange={(v) => updateScenario(idx, { preAllocatedVUs: v })}
                              className="w-full"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">maxVUs / ген.</label>
                            <FormulaInput
                              value={sc.maxVUs ?? 100}
                              integer
                              min={1}
                              onChange={(v) => updateScenario(idx, { maxVUs: v })}
                              className="w-full"
                            />
                          </div>
                        </>
                      )}

                      {(ex === 'constant-vus' ||
                        ex === 'per-vu-iterations' ||
                        ex === 'shared-iterations' ||
                        ex === 'externally-controlled') && (
                        <div>
                          <label className="block text-slate-500 mb-1">VU (всего)</label>
                          <FormulaInput
                            value={sc.vus ?? 10}
                            integer
                            min={1}
                            onChange={(v) => updateScenario(idx, { vus: v })}
                            className="w-full"
                          />
                        </div>
                      )}

                      {ex === 'externally-controlled' && (
                        <div>
                          <label className="block text-slate-500 mb-1">Максимум VU (maxVUs)</label>
                          <FormulaInput
                            value={sc.maxVUs ?? 50}
                            integer
                            min={1}
                            onChange={(v) => updateScenario(idx, { maxVUs: v })}
                            className="w-full"
                          />
                        </div>
                      )}

                      {ex === 'ramping-vus' && (
                        <div>
                          <label className="block text-slate-500 mb-1">Стартовые VU (startVUs)</label>
                          <FormulaInput
                            value={sc.startVUs ?? 0}
                            integer
                            min={0}
                            onChange={(v) => updateScenario(idx, { startVUs: v })}
                            className="w-full"
                          />
                        </div>
                      )}

                      {(ex === 'constant-vus' || ex === 'externally-controlled') && (
                        <div>
                          <label className="block text-slate-500 mb-1">Длительность</label>
                          <Input
                            value={sc.duration ?? '5m'}
                            onChange={(e) => updateScenario(idx, { duration: e.target.value })}
                            className="h-7 text-xs font-mono"
                          />
                        </div>
                      )}

                      {(ex === 'per-vu-iterations' || ex === 'shared-iterations') && (
                        <>
                          <div>
                            <label className="block text-slate-500 mb-1">Итераций</label>
                            <FormulaInput
                              value={sc.iterations ?? 100}
                              integer
                              min={1}
                              onChange={(v) => updateScenario(idx, { iterations: v })}
                              className="w-full"
                            />
                          </div>
                          <div>
                            <label className="block text-slate-500 mb-1">Макс. длительность</label>
                            <Input
                              value={sc.maxDuration ?? '10m'}
                              onChange={(e) => updateScenario(idx, { maxDuration: e.target.value })}
                              className="h-7 text-xs font-mono"
                            />
                          </div>
                        </>
                      )}
                    </div>

                    {/* Двухрежимный конструктор ступеней и стадий для ramping-* */}
                    {isRamping && (
                      <StageConstructor
                        executor={ex}
                        timeUnit={sc.timeUnit || '1s'}
                        startVal={Number(isArrival ? sc.startRate : sc.startVUs) || 0}
                        stages={sc.stages || []}
                        warmupStagesCount={Number(sc.warmup?.stages) > 0 ? 1 : 0}
                        globalWarmupDur={warmupDur}
                        onChangeStages={(nextStages) => updateScenario(idx, { stages: nextStages })}
                      />
                    )}

                    {/* SLA операции + кнопка Доп. параметров k6 */}
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
                        <FormulaInput
                          value={op.slaThresholdMs ?? 1000}
                          min={1}
                          onChange={(v) => updateOp(idx, { slaThresholdMs: v })}
                          className="w-24"
                        />
                        <span className="text-slate-500">мс</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span className="text-slate-500">Ошибки ≤</span>
                        <FormulaInput
                          value={op.maxErrorPct ?? 1}
                          min={0}
                          onChange={(v) => updateOp(idx, { maxErrorPct: v })}
                          className="w-20"
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

                      <button
                        type="button"
                        onClick={() => setAdvScenarioOpen((p) => ({ ...p, [idx]: !p[idx] }))}
                        className="ml-auto inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] font-medium text-slate-600 hover:bg-slate-100 cursor-pointer"
                      >
                        <Settings2 className="h-3.5 w-3.5 text-slate-500" />
                        Доп. параметры k6
                        {advCount > 0 && <Badge variant="info">{advCount}</Badge>}
                      </button>
                    </div>

                    {/* Доп. параметры сценария k6: startTime, gracefulStop, gracefulRampDown, env, tags */}
                    {advScenarioOpen[idx] && (
                      <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3 space-y-3">
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                          <div>
                            <label className="block text-[11px] text-slate-500 mb-0.5">
                              Задержка старта (startTime)
                            </label>
                            <Input
                              value={sc.startTime ?? ''}
                              onChange={(e) => updateScenario(idx, { startTime: e.target.value })}
                              placeholder="0s (сразу)"
                              className="h-7 text-xs font-mono bg-white"
                            />
                          </div>
                          <div>
                            <label className="block text-[11px] text-slate-500 mb-0.5">
                              Ожидание завершения (gracefulStop)
                            </label>
                            <Input
                              value={sc.gracefulStop ?? ''}
                              onChange={(e) => updateScenario(idx, { gracefulStop: e.target.value })}
                              placeholder="30s"
                              className="h-7 text-xs font-mono bg-white"
                            />
                          </div>
                          {ex === 'ramping-vus' && (
                            <div>
                              <label className="block text-[11px] text-slate-500 mb-0.5">
                                Плавное снижение VU (gracefulRampDown)
                              </label>
                              <Input
                                value={sc.gracefulRampDown ?? ''}
                                onChange={(e) => updateScenario(idx, { gracefulRampDown: e.target.value })}
                                placeholder="30s"
                                className="h-7 text-xs font-mono bg-white"
                              />
                            </div>
                          )}
                        </div>

                        {/* Локальные env и tags сценария */}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 pt-1 border-t border-slate-200/70">
                          {(['env', 'tags'] as const).map((kvField) => {
                            const obj: Record<string, string> = sc[kvField] || {};
                            const pairs = Object.entries(obj);
                            return (
                              <div key={kvField} className="space-y-1.5">
                                <div className="flex items-center justify-between">
                                  <span className="text-[11px] font-semibold text-slate-600">
                                    {kvField === 'env'
                                      ? 'Локальные переменные сценария (env)'
                                      : 'Теги метрик сценария (tags)'}
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      const next = { ...obj, [`${kvField}_${pairs.length + 1}`]: '' };
                                      updateScenario(idx, { [kvField]: next });
                                    }}
                                    className="text-[11px] font-medium text-indigo-600 hover:underline cursor-pointer"
                                  >
                                    + Добавить
                                  </button>
                                </div>
                                {pairs.map(([k, v], pIdx) => (
                                  <div key={pIdx} className="flex items-center gap-1.5">
                                    <Input
                                      value={k}
                                      onChange={(e) => {
                                        const nextEntries = [...pairs];
                                        nextEntries[pIdx] = [e.target.value, v];
                                        const rebuilt: Record<string, string> = {};
                                        nextEntries.forEach(([nk, nv]) => {
                                          if (nk.trim()) rebuilt[nk.trim()] = nv;
                                        });
                                        updateScenario(idx, {
                                          [kvField]: Object.keys(rebuilt).length ? rebuilt : undefined,
                                        });
                                      }}
                                      placeholder={kvField === 'env' ? 'KEY' : 'tag'}
                                      className="h-7 w-36 font-mono text-xs bg-white"
                                    />
                                    <Input
                                      value={v}
                                      onChange={(e) => {
                                        const next = { ...obj, [k]: e.target.value };
                                        updateScenario(idx, { [kvField]: next });
                                      }}
                                      placeholder="значение"
                                      className="h-7 flex-1 font-mono text-xs bg-white"
                                    />
                                    <button
                                      type="button"
                                      onClick={() => {
                                        const next = { ...obj };
                                        delete next[k];
                                        updateScenario(idx, {
                                          [kvField]: Object.keys(next).length ? next : undefined,
                                        });
                                      }}
                                      className="text-slate-400 hover:text-rose-600 cursor-pointer"
                                    >
                                      <X className="h-3.5 w-3.5" />
                                    </button>
                                  </div>
                                ))}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </CardBody>
                )}
              </Card>
            );
          })}
        </div>
      </div>

      {/* Раздел 2: Переменные окружения и секреты */}
      <Card id="sec-env" className="scroll-mt-4">
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-sm">Переменные окружения и секреты ({envCount})</span>
            <div className="inline-flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-xs">
              <button
                type="button"
                onClick={() => {
                  if (envMode === 'raw') {
                    const parsed = parseEnvText(envRawText);
                    setEnvRows(parsed.length > 0 ? parsed : [{ k: '', v: '' }]);
                    setEnvMode('table');
                  }
                }}
                className={`rounded-md px-2.5 py-0.5 font-medium cursor-pointer ${
                  envMode === 'table' ? 'bg-indigo-600 text-white' : 'text-slate-600'
                }`}
              >
                Таблица
              </button>
              <button
                type="button"
                onClick={() => {
                  if (envMode === 'table') {
                    setEnvRawText(formatEnvRows(envRows));
                    setEnvMode('raw');
                  }
                }}
                className={`rounded-md px-2.5 py-0.5 font-medium cursor-pointer ${
                  envMode === 'raw' ? 'bg-indigo-600 text-white' : 'text-slate-600'
                }`}
              >
                .env Текст
              </button>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Link to="/secrets" className="text-xs text-indigo-600 hover:underline inline-flex items-center gap-1">
              <KeyRound className="h-3.5 w-3.5" />
              Хранилище секретов
              <ExternalLink className="h-3 w-3" />
            </Link>
            {envMode === 'table' && (
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  setEnvRows((prev) => [...prev, { k: '', v: '' }]);
                  setDirty(true);
                }}
              >
                <Plus className="h-3.5 w-3.5" />
                Переменная
              </Button>
            )}
          </div>
        </CardHeader>
        <CardBody className="space-y-2.5">
          {secretNames.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5 text-xs">
              <span className="text-slate-500">
                Секреты (клик подставит в выбранное поле или добавит строку):
              </span>
              {secretNames.map((sn: string) => (
                <button
                  key={sn}
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => insertSecretToken(sn)}
                  className="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-[11px] text-slate-700 hover:border-indigo-300 hover:bg-indigo-50 hover:text-indigo-700 cursor-pointer"
                >
                  {`\${secret:${sn}}`}
                </button>
              ))}
            </div>
          )}

          {envMode === 'table' ? (
            <div className="space-y-2">
              {envRows.map((row, rIdx) => (
                <div key={rIdx} className="flex items-center gap-2">
                  <Input
                    value={row.k}
                    onFocus={() => setFocusedEnvRowIdx(rIdx)}
                    onChange={(e) => {
                      setEnvRows((prev) => prev.map((it, i) => (i === rIdx ? { ...it, k: e.target.value } : it)));
                      setDirty(true);
                    }}
                    placeholder="BASE_URL"
                    className="h-8 w-64 font-mono text-xs"
                  />
                  <Input
                    value={row.v}
                    onFocus={() => setFocusedEnvRowIdx(rIdx)}
                    onChange={(e) => {
                      setEnvRows((prev) => prev.map((it, i) => (i === rIdx ? { ...it, v: e.target.value } : it)));
                      setDirty(true);
                    }}
                    placeholder="https://... или ${secret:NAME}"
                    className="h-8 flex-1 font-mono text-xs"
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => {
                      setEnvRows((prev) => {
                        const next = prev.filter((_, i) => i !== rIdx);
                        return next.length > 0 ? next : [{ k: '', v: '' }];
                      });
                      setDirty(true);
                    }}
                  >
                    <X className="h-4 w-4 text-slate-400 hover:text-rose-600" />
                  </Button>
                </div>
              ))}
            </div>
          ) : (
            <Textarea
              rows={6}
              value={envRawText}
              onChange={(e) => {
                setEnvRawText(e.target.value);
                setDirty(true);
              }}
              placeholder={'BASE_URL=https://stand.example\nAPI_TOKEN=${secret:API_TOKEN}'}
              className="font-mono text-xs"
            />
          )}
        </CardBody>
      </Card>

      {/* Раздел 3: Автостоп и кастомные проверки Prometheus */}
      <Card id="sec-stop" className="scroll-mt-4">
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2 font-semibold text-sm">
            <ShieldAlert className="h-4 w-4 text-indigo-600" />
            Автостоп и метрики SLA Prometheus
          </div>
          <span className="text-xs text-slate-500">
            Срабатывание автостопа ≈ <b>{stopEtaSec} с</b> ({stop.consecutive ?? 4} проверок подряд по 15 с)
          </span>
        </CardHeader>
        <CardBody className="space-y-4 text-xs">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-3">
            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.rateEnabled}
                onChange={(v) => {
                  setStop({ ...stop, rateEnabled: v });
                  setDirty(true);
                }}
                label={<span className="text-xs font-medium">Падение интенсивности</span>}
              />
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Мин. от плана:</span>
                <Input
                  type="number"
                  value={stop.minRatePct ?? 90}
                  onChange={(e) => {
                    setStop({ ...stop, minRatePct: Number(e.target.value) });
                    setDirty(true);
                  }}
                  className="h-7 w-16 text-xs"
                />
                <span>%</span>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.droppedEnabled}
                onChange={(v) => {
                  setStop({ ...stop, droppedEnabled: v });
                  setDirty(true);
                }}
                label={<span className="text-xs font-medium">Высокий dropped_iterations</span>}
              />
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Порог от плана:</span>
                <Input
                  type="number"
                  step="0.1"
                  value={stop.maxDroppedPct ?? 1}
                  onChange={(e) => {
                    setStop({ ...stop, maxDroppedPct: Number(e.target.value) });
                    setDirty(true);
                  }}
                  className="h-7 w-16 text-xs"
                />
                <span>%</span>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.percentileEnabled}
                onChange={(v) => {
                  setStop({ ...stop, percentileEnabled: v });
                  setDirty(true);
                }}
                label={<span className="text-xs font-medium">Превышение времени ответа</span>}
              />
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Множитель к SLA:</span>
                <Input
                  type="number"
                  step="0.1"
                  value={stop.slaMultiplier ?? 1.5}
                  onChange={(e) => {
                    setStop({ ...stop, slaMultiplier: Number(e.target.value) });
                    setDirty(true);
                  }}
                  className="h-7 w-16 text-xs"
                />
                <span>×</span>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <Switch
                checked={!!stop.errorsEnabled}
                onChange={(v) => {
                  setStop({ ...stop, errorsEnabled: v });
                  setDirty(true);
                }}
                label={<span className="text-xs font-medium">Превышение % ошибок</span>}
              />
              <Switch
                checked={!!stop.extraStepOnMaxSearch}
                onChange={(v) => {
                  setStop({ ...stop, extraStepOnMaxSearch: v });
                  setDirty(true);
                }}
                label={<span className="text-xs text-slate-600">+1 ступень при поиске макс.</span>}
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-slate-600">Окно, сек:</span>
                <Input
                  type="number"
                  value={stop.windowSec ?? 60}
                  onChange={(e) => {
                    setStop({ ...stop, windowSec: Number(e.target.value) });
                    setDirty(true);
                  }}
                  className="h-7 w-16 text-xs"
                />
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-600">Подряд раз:</span>
                <Input
                  type="number"
                  value={stop.consecutive ?? 4}
                  onChange={(e) => {
                    setStop({ ...stop, consecutive: Number(e.target.value) });
                    setDirty(true);
                  }}
                  className="h-7 w-16 text-xs"
                />
              </div>
            </div>
          </div>

          {/* Кастомные PromQL проверки + готовые инфраструктурные пресеты */}
          <div className="border-t border-slate-100 pt-3 space-y-2.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-semibold text-slate-800">
                Инфраструктурные и прикладные метрики Prometheus (SLA ступеней / автостоп)
              </span>
              <div className="flex flex-wrap items-center gap-1.5">
                <Select
                  value=""
                  onChange={(e) => {
                    const pr = CC_PRESETS.find((p) => p.key === e.target.value);
                    if (!pr) return;
                    setStop({
                      ...stop,
                      customChecks: [
                        ...(stop.customChecks || []),
                        {
                          name: pr.name,
                          promql: pr.promql,
                          cmp: pr.cmp,
                          threshold: pr.threshold,
                          unit: pr.unit,
                          stepSla: pr.stepSla,
                          autoStop: pr.autoStop,
                        },
                      ],
                    });
                    setDirty(true);
                  }}
                  className="h-7 text-xs w-52"
                >
                  <option value="">⚡ Готовый шаблон метрики…</option>
                  {CC_PRESETS.map((pr) => (
                    <option key={pr.key} value={pr.key}>
                      {pr.label} ({pr.cmp === 'LTE' ? '≤' : ''} {pr.threshold}
                      {pr.unit})
                    </option>
                  ))}
                </Select>

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
                          {
                            name: ch.name,
                            promql: q,
                            cmp: 'LTE',
                            threshold: 80,
                            unit: ch.unit || '%',
                            stepSla: true,
                            autoStop: false,
                          },
                        ],
                      });
                      setDirty(true);
                    }}
                    className="h-7 text-xs w-48"
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
                  onClick={() => {
                    setStop({
                      ...stop,
                      customChecks: [
                        ...(stop.customChecks || []),
                        { name: '', promql: '', cmp: 'LTE', threshold: 80, unit: '%', stepSla: true, autoStop: false },
                      ],
                    });
                    setDirty(true);
                  }}
                >
                  <Plus className="h-3.5 w-3.5" />
                  Своё правило PromQL
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
                      setDirty(true);
                    }}
                    placeholder="Название метрики (например CPU серверов)"
                    className="h-7 w-52 text-xs bg-white"
                  />
                  <Select
                    value={cc.cmp || 'LTE'}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], cmp: e.target.value };
                      setStop({ ...stop, customChecks: next });
                      setDirty(true);
                    }}
                    className="h-7 w-20 text-xs bg-white"
                  >
                    <option value="LTE">≤</option>
                    <option value="LT">&lt;</option>
                    <option value="GTE">≥</option>
                    <option value="GT">&gt;</option>
                  </Select>
                  <FormulaInput
                    value={cc.threshold ?? 80}
                    onChange={(v) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], threshold: v };
                      setStop({ ...stop, customChecks: next });
                      setDirty(true);
                    }}
                    className="w-24"
                  />
                  <Input
                    value={cc.unit || ''}
                    onChange={(e) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], unit: e.target.value };
                      setStop({ ...stop, customChecks: next });
                      setDirty(true);
                    }}
                    placeholder="%"
                    className="h-7 w-16 text-xs bg-white"
                  />
                  <Switch
                    checked={cc.stepSla !== false}
                    onChange={(v) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], stepSla: v };
                      setStop({ ...stop, customChecks: next });
                      setDirty(true);
                    }}
                    label={<span className="text-xs">SLA ступени</span>}
                  />
                  <Switch
                    checked={!!cc.autoStop}
                    onChange={(v) => {
                      const next = [...stop.customChecks];
                      next[cIdx] = { ...next[cIdx], autoStop: v };
                      setStop({ ...stop, customChecks: next });
                      setDirty(true);
                    }}
                    label={<span className="text-xs">Автостоп</span>}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    className="ml-auto"
                    onClick={() => {
                      setStop({ ...stop, customChecks: stop.customChecks.filter((_: any, i: number) => i !== cIdx) });
                      setDirty(true);
                    }}
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
                    setDirty(true);
                  }}
                  placeholder="PromQL (доступны {range}, {run_id}, $var)"
                  className="h-8 font-mono text-xs bg-white"
                />
              </div>
            ))}
          </div>
        </CardBody>
      </Card>

      {/* Раздел 4: Тестовые данные CSV */}
      {dataFiles.length > 0 && (
        <Card id="sec-data" className="scroll-mt-4">
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
                        onChange={(e) => {
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, mode: e.target.value } : it))
                          );
                          setDirty(true);
                        }}
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
                        onChange={(e) => {
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, hasHeader: e.target.checked } : it))
                          );
                          setDirty(true);
                        }}
                      />
                    </td>
                    <td className="px-4 py-2 text-center">
                      <input
                        type="checkbox"
                        checked={!!df.consumed}
                        onChange={(e) => {
                          setDataFiles((prev) =>
                            prev.map((it, i) => (i === dIdx ? { ...it, consumed: e.target.checked } : it))
                          );
                          setDirty(true);
                        }}
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
      <Card id="sec-json" className="scroll-mt-4">
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
              <div className="flex flex-wrap items-center gap-2">
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
                {editedConfig && (
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => {
                      navigator.clipboard?.writeText(editedConfig);
                      toast.success('JSON конфиг скопирован');
                    }}
                  >
                    <Copy className="h-3.5 w-3.5" />
                    Копировать JSON
                  </Button>
                )}
                {plan?.configJson && (
                  <a
                    href={`/scripts/${script.id}/load/config.json${
                      loadedProfileId ? `?profile=${loadedProfileId}` : ''
                    }`}
                  >
                    <Button type="button" variant="outline" size="xs">
                      <Download className="h-3.5 w-3.5" />
                      Скачать config.json
                    </Button>
                  </a>
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
          <div className="flex flex-wrap items-center gap-2">
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
            <span className="text-xs text-slate-400 hidden sm:inline">
              {dirty ? 'Есть несохранённые изменения · Ctrl+S' : 'Все изменения сохранены'}
            </span>
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
              <div className="text-xs text-slate-500">Основная нагрузка скрипта по умолчанию</div>
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
              const meta = summarizeProfileData(p.data);
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

                  {/* Метрики профиля */}
                  <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-slate-600">
                    <Badge variant="neutral">{meta.opsCnt} оп.</Badge>
                    {meta.peakRate > 0 && <Badge variant="info">{meta.peakRate} оп/с</Badge>}
                    {meta.peakVu > 0 && <Badge variant="neutral">{meta.peakVu} VU</Badge>}
                    {meta.maxDurSec > 0 && <span>⏱ {fmtDurationSec(meta.maxDurSec)}</span>}
                    {meta.warmup && <Badge variant="warning">🔥 {meta.warmup}</Badge>}
                    {meta.envCnt > 0 && <span>· env: {meta.envCnt}</span>}
                    {meta.gens > 1 && <span>· ген: {meta.gens}</span>}
                  </div>

                  <div className="text-[11px] text-slate-400 flex items-center gap-1.5">
                    {p.updatedAt && <span>{fmtShortDate(p.updatedAt)}</span>}
                    {p.updatedBy && <span>· {p.updatedBy}</span>}
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
              if (importSeq) {
                if (importRunAt.trim()) {
                  const dt = new Date(importRunAt);
                  if (!Number.isNaN(dt.getTime())) fd.append('runAt', dt.toISOString());
                }
                if (importCount.trim() && Number(importCount) > 0) {
                  fd.append('count', String(Math.round(Number(importCount))));
                }
                fd.append('gapMinutes', String(Math.max(5, importGap)));
                fd.append('shift', String(importShift));
              }
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
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-slate-50 border border-slate-200 p-2.5">
            <span>Скачать все сохранённые профили этого скрипта в JSON:</span>
            <div className="flex items-center gap-1.5">
              <a href={`/scripts/${script.id}/load/load.json`}>
                <Button type="button" variant="outline" size="xs">
                  Базовая нагрузка (.json)
                </Button>
              </a>
              <a href={`/scripts/${script.id}/load/profiles.json`}>
                <Button type="button" variant="outline" size="xs">
                  <Download className="h-3.5 w-3.5" />
                  Все профили (.json)
                </Button>
              </a>
            </div>
          </div>

          <div
            onDragOver={(e) => {
              e.preventDefault();
              setImportDragOver(true);
            }}
            onDragLeave={() => setImportDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setImportDragOver(false);
              const f = e.dataTransfer.files?.[0];
              if (f) setImportFile(f);
            }}
            className={`rounded-xl border-2 border-dashed p-3 text-center transition-colors ${
              importDragOver
                ? 'border-indigo-500 bg-indigo-50/60'
                : 'border-slate-300 bg-slate-50/40 hover:border-indigo-300'
            }`}
          >
            <label className="cursor-pointer flex flex-col items-center gap-1">
              <Upload className="h-5 w-5 text-indigo-600" />
              <span className="font-medium text-slate-700">
                {importFile
                  ? `Выбран файл: ${importFile.name} (${Math.round(importFile.size / 1024)} КБ)`
                  : 'Перетащите JSON-файл профилей сюда или нажмите для выбора'}
              </span>
              <input
                type="file"
                accept=".json,application/json"
                onChange={(e) => setImportFile(e.target.files?.[0] || null)}
                className="hidden"
              />
            </label>
          </div>

          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="font-medium text-slate-700">Или вставьте JSON-текст</label>
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

          <div className="rounded-xl border border-slate-200 p-3 space-y-2.5 bg-slate-50/60">
            <Switch
              checked={importSeq}
              onChange={setImportSeq}
              label={
                <span className="text-xs font-medium">
                  Сразу поставить импортированные профили последовательно в расписание
                </span>
              }
            />
            {importSeq && (
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 pt-1">
                <div>
                  <label className="block text-[11px] text-slate-600 mb-0.5">Старт 1-го прогона</label>
                  <Input
                    type="datetime-local"
                    value={importRunAt}
                    onChange={(e) => setImportRunAt(e.target.value)}
                    className="h-7 text-xs bg-white"
                  />
                  <span className="text-[10px] text-slate-400">Пусто — ближайшее окно</span>
                </div>
                <div>
                  <label className="block text-[11px] text-slate-600 mb-0.5">Сколько раз повторить</label>
                  <Input
                    type="number"
                    min={1}
                    max={50}
                    value={importCount}
                    onChange={(e) => setImportCount(e.target.value)}
                    placeholder="По 1 разу каждый"
                    className="h-7 text-xs bg-white"
                  />
                </div>
                <div>
                  <label className="block text-[11px] text-slate-600 mb-0.5">Пауза между прогонами, мин</label>
                  <Input
                    type="number"
                    min={5}
                    max={120}
                    value={importGap}
                    onChange={(e) => setImportGap(Number(e.target.value))}
                    className="h-7 text-xs bg-white"
                  />
                </div>
                <div className="sm:col-span-3">
                  <Switch
                    checked={importShift}
                    onChange={setImportShift}
                    label={
                      <span className="text-xs text-slate-700">
                        Раздвинуть чужие слоты в расписании при пересечении
                      </span>
                    }
                  />
                </div>
              </div>
            )}
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setImportModalOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={importing}>
              <Check className="h-3.5 w-3.5" />
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
