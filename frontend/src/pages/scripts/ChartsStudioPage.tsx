import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
import { useAuth } from '@/context/AuthContext';
import { PromqlEditor } from '@/components/ui/PromqlEditor';
import { ScriptHead } from './ScriptHead';
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Copy,
  Database,
  Download,
  Eye,
  FileCode2,
  GripVertical,
  Maximize2,
  Palette,
  Plus,
  RotateCcw,
  Save,
  Search,
  Settings,
  Sparkles,
  Trash2,
  Upload,
  Variable,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

const BUILTIN_VARS = new Set([
  '__interval',
  '__rate_interval',
  '__range',
  '__range_s',
  '__interval_ms',
  '__range_ms',
  '__all',
]);

const VAR_RE = /\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}|\$([A-Za-z_][A-Za-z0-9_]*)|\[\[([A-Za-z_][A-Za-z0-9_]*)(?::\w+)?]]/g;

const CHART_PRESETS = [
  {
    group: 'k6 (нагрузка)',
    items: [
      {
        name: 'HTTP RPS по статусам',
        unit: 'оп/с',
        queries: [
          {
            promql: 'sum by (status) (rate(k6_http_reqs_total{test_run_id="{run_id}"}[{range}]))',
            legend: 'HTTP {{status}}',
          },
        ],
      },
      {
        name: 'Время ответа HTTP (p50 / p95 / p99)',
        unit: 'мс',
        queries: [
          {
            promql:
              'histogram_quantile(0.50, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000',
            legend: 'p50',
          },
          {
            promql:
              'histogram_quantile(0.95, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000',
            legend: 'p95',
          },
          {
            promql:
              'histogram_quantile(0.99, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000',
            legend: 'p99',
          },
        ],
      },
      {
        name: 'Активные виртуальные пользователи (VUs)',
        unit: 'шт',
        queries: [{ promql: 'sum(k6_vus{test_run_id="{run_id}"})', legend: 'VUs' }],
      },
      {
        name: 'Сетевой трафик k6 (генераторы)',
        unit: 'Б/с',
        queries: [
          { promql: 'sum(rate(k6_data_sent_total{test_run_id="{run_id}"}[{range}]))', legend: 'Отправлено' },
          { promql: 'sum(rate(k6_data_received_total{test_run_id="{run_id}"}[{range}]))', legend: 'Получено' },
        ],
      },
    ],
  },
  {
    group: 'Серверы (Node Exporter)',
    items: [
      {
        name: 'Утилизация CPU серверов',
        unit: '%',
        queries: [
          {
            promql: '100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[{range}])) * 100)',
            legend: '{{instance}}',
          },
        ],
      },
      {
        name: 'Использование RAM (%)',
        unit: '%',
        queries: [
          {
            promql: '(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100',
            legend: '{{instance}}',
          },
        ],
      },
      {
        name: 'Доступная память (RAM)',
        unit: 'Б',
        queries: [{ promql: 'node_memory_MemAvailable_bytes', legend: '{{instance}}' }],
      },
      {
        name: 'Load Average (1m / 5m)',
        unit: '',
        queries: [
          { promql: 'node_load1', legend: '1m {{instance}}' },
          { promql: 'node_load5', legend: '5m {{instance}}' },
        ],
      },
      {
        name: 'Сеть серверов (вх. / исх.)',
        unit: 'Б/с',
        queries: [
          {
            promql: 'sum by (instance) (rate(node_network_receive_bytes_total{device!="lo"}[{range}]))',
            legend: 'RX {{instance}}',
          },
          {
            promql: 'sum by (instance) (rate(node_network_transmit_bytes_total{device!="lo"}[{range}]))',
            legend: 'TX {{instance}}',
          },
        ],
      },
    ],
  },
  {
    group: 'Приложения (JVM / Go)',
    items: [
      {
        name: 'JVM Heap (используется / максимум)',
        unit: 'Б',
        queries: [
          { promql: 'sum by (instance) (jvm_memory_used_bytes{area="heap"})', legend: 'Used {{instance}}' },
          { promql: 'sum by (instance) (jvm_memory_max_bytes{area="heap"})', legend: 'Max {{instance}}' },
        ],
      },
      {
        name: 'JVM GC Pause',
        unit: 'с',
        queries: [
          { promql: 'sum by (instance) (rate(jvm_gc_pause_seconds_sum[{range}]))', legend: '{{instance}}' },
        ],
      },
      {
        name: 'Go Goroutines',
        unit: 'шт',
        queries: [{ promql: 'go_goroutines', legend: '{{instance}}' }],
      },
    ],
  },
];

function extractVarRefs(texts: string[]): string[] {
  const found = new Set<string>();
  texts.forEach((txt) => {
    if (!txt) return;
    VAR_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = VAR_RE.exec(txt)) !== null) {
      const name = m[1] || m[2] || m[3];
      if (name && !BUILTIN_VARS.has(name)) found.add(name);
    }
  });
  return Array.from(found);
}

function resolveVarsInText(text: string, varsMap: Record<string, string>): string {
  if (!text) return '';
  return text.replace(VAR_RE, (full, g1, g2, g3) => {
    const k = g1 || g2 || g3;
    if (k && Object.prototype.hasOwnProperty.call(varsMap, k)) {
      return varsMap[k];
    }
    return full;
  });
}

interface QueryDiag {
  index: number;
  expandedPromql?: string;
  seriesCount?: number;
  labelKeys?: string[];
  error?: string | null;
}

export function ChartsStudioPage() {
  const { id } = useParams<{ id: string }>();
  const confirm = useConfirm();
  const { isAdmin } = useAuth();
  const qc = useQueryClient();

  const [templates, setTemplates] = useState<any[]>([]);
  const [variables, setVariables] = useState<any[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [checkedIds, setCheckedIds] = useState<Set<number>>(new Set());

  // Left list controls
  const [searchText, setSearchText] = useState('');
  const [filterMode, setFilterMode] = useState<'all' | 'on' | 'off'>('all');
  const [dragSrcId, setDragSrcId] = useState<number | null>(null);

  // Current chart editor
  const [name, setName] = useState('');
  const [unit, setUnit] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [queries, setQueries] = useState<{ promql: string; legend: string }[]>([{ promql: '', legend: '' }]);
  const [chartVarRows, setChartVarRows] = useState<{ name: string; value: string }[]>([]);
  const [baselineJson, setBaselineJson] = useState('');
  const [saving, setSaving] = useState(false);

  const queryTaRefs = useRef<(HTMLTextAreaElement | null)[]>([]);

  // Preview state
  const [previewSrc, setPreviewSrc] = useState<string>('live:15');
  const [autoPreview, setAutoPreview] = useState<boolean>(true);
  const [previewImg, setPreviewImg] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState<string | null>(null);
  const [previewMeta, setPreviewMeta] = useState<{
    title: string;
    windowLabel: string;
    seriesCount: number;
    elapsedMs: number;
  } | null>(null);
  const [queryDiags, setQueryDiags] = useState<Record<number, QueryDiag>>({});
  const [expandedDiagIdx, setExpandedDiagIdx] = useState<Record<number, boolean>>({});
  const [previewLoading, setPreviewLoading] = useState(false);
  const [zoomOpen, setZoomOpen] = useState(false);
  const previewAbortRef = useRef<AbortController | null>(null);

  // Modals
  const [varsOpen, setVarsOpen] = useState(false);
  const [varEdits, setVarEdits] = useState<Record<number, string>>({});
  const [newVarName, setNewVarName] = useState('');
  const [newVarVal, setNewVarVal] = useState('');

  const [grafanaOpen, setGrafanaOpen] = useState(false);
  const [grafanaJson, setGrafanaJson] = useState('');
  const [grafanaFile, setGrafanaFile] = useState<File | null>(null);
  const [grafanaDragOver, setGrafanaDragOver] = useState(false);
  const [importingGrafana, setImportingGrafana] = useState(false);

  const [copyOpen, setCopyOpen] = useState(false);
  const [otherScripts, setOtherScripts] = useState<any[]>([]);
  const [srcScriptId, setSrcScriptId] = useState('');
  const [copySearch, setCopySearch] = useState('');
  const [copySelectedTids, setCopySelectedTids] = useState<Set<number>>(new Set());
  const [copyVarsFlag, setCopyVarsFlag] = useState(true);
  const [copyOverwriteFlag, setCopyOverwriteFlag] = useState(false);
  const [copyingCharts, setCopyingCharts] = useState(false);

  // Inline report chart styling modal
  const [styleOpen, setStyleOpen] = useState(false);
  const [stTheme, setStTheme] = useState('DARK');
  const [stWidth, setStWidth] = useState(1100);
  const [stHeight, setStHeight] = useState(320);
  const [stLegend, setStLegend] = useState('BOTTOM');
  const [stStats, setStStats] = useState(true);
  const [stMaxLegend, setStMaxLegend] = useState(20);
  const [stAutoWidth, setStAutoWidth] = useState(true);
  const [stSortBy, setStSortBy] = useState('NONE');
  const [stSortDir, setStSortDir] = useState('DESC');
  const [stAbsTime, setStAbsTime] = useState(false);
  const [stSkipWarmup, setStSkipWarmup] = useState(false);
  const [savingStyle, setSavingStyle] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['script-charts', id],
    queryFn: () => apiFetch(`/api/v1/scripts/${id}/charts`),
  });

  const makeSnapshot = (
    n: string,
    u: string,
    en: boolean,
    qs: { promql: string; legend: string }[],
    cvs: { name: string; value: string }[]
  ) =>
    JSON.stringify({
      n: n.trim(),
      u: u.trim(),
      en,
      qs: qs.map((q) => ({ promql: q.promql.trim(), legend: q.legend.trim() })),
      cvs: cvs.filter((v) => v.name.trim()).map((v) => ({ name: v.name.trim(), value: v.value.trim() })),
    });

  const currentSnapshot = useMemo(
    () => makeSnapshot(name, unit, enabled, queries, chartVarRows),
    [name, unit, enabled, queries, chartVarRows]
  );

  const isDirty = baselineJson !== '' && currentSnapshot !== baselineJson;

  const buildChartVarsMap = useCallback((rows: { name: string; value: string }[]) => {
    const out: Record<string, string> = {};
    rows.forEach((r) => {
      const k = r.name.trim().replace(/^\$/, '');
      if (k) out[k] = r.value;
    });
    return out;
  }, []);

  const triggerPreview = useCallback(
    async (
      override?: {
        name: string;
        unit: string;
        queries: { promql: string; legend: string }[];
        chartVars: { name: string; value: string }[];
      }
    ) => {
      const targetName = override ? override.name : name;
      const targetUnit = override ? override.unit : unit;
      const targetQueries = override ? override.queries : queries;
      const targetVars = override ? override.chartVars : chartVarRows;

      if (!targetQueries.some((q) => q.promql.trim())) {
        setPreviewErr('Добавьте хотя бы один PromQL-запрос для предпросмотра');
        setPreviewImg(null);
        return;
      }

      if (previewAbortRef.current) {
        previewAbortRef.current.abort();
      }
      const ac = new AbortController();
      previewAbortRef.current = ac;

      setPreviewLoading(true);
      setPreviewErr(null);

      let runId: number | null = null;
      let minutes = 15;
      if (previewSrc.startsWith('run:')) {
        runId = Number(previewSrc.slice(4));
      } else if (previewSrc.startsWith('live:')) {
        minutes = Number(previewSrc.slice(5)) || 15;
      }

      try {
        const res = await apiFetch(`/api/v1/scripts/${id}/charts/preview`, {
          method: 'POST',
          signal: ac.signal,
          body: {
            name: targetName || 'Предпросмотр',
            unit: targetUnit,
            // Сохраняем 1:1 индексы для точной привязки QueryDiag!
            queries: targetQueries.map((q) => ({ promql: q.promql, legend: q.legend })),
            variables: buildChartVarsMap(targetVars),
            runId,
            minutes,
          },
        });

        const diagMap: Record<number, QueryDiag> = {};
        (res.queries || []).forEach((qd: QueryDiag) => {
          diagMap[qd.index] = qd;
        });
        setQueryDiags(diagMap);

        if (res.ok && res.dataUri) {
          setPreviewImg(res.dataUri);
          setPreviewErr(null);
          setPreviewMeta({
            title: res.title || targetName,
            windowLabel: res.windowLabel || '',
            seriesCount: res.seriesCount || 0,
            elapsedMs: res.elapsedMs || 0,
          });
        } else {
          setPreviewImg(null);
          setPreviewMeta(
            res.windowLabel
              ? {
                  title: res.title || targetName,
                  windowLabel: res.windowLabel,
                  seriesCount: res.seriesCount || 0,
                  elapsedMs: res.elapsedMs || 0,
                }
              : null
          );
          setPreviewErr(res.error || 'Нет серий данных за выбранный интервал');
        }
      } catch (err: any) {
        if (err?.name === 'AbortError') return;
        setPreviewErr(err.message);
      } finally {
        if (previewAbortRef.current === ac) {
          setPreviewLoading(false);
        }
      }
    },
    [id, name, unit, queries, chartVarRows, previewSrc, buildChartVarsMap]
  );

  const loadIntoEditor = useCallback(
    (tpl: any | null, shouldAutoPreview = false) => {
      if (previewAbortRef.current) previewAbortRef.current.abort();
      setPreviewImg(null);
      setPreviewErr(null);
      setPreviewMeta(null);
      setQueryDiags({});
      setExpandedDiagIdx({});

      if (!tpl) {
        setSelectedId(null);
        const defName = '';
        const defUnit = '';
        const defQs = [{ promql: '', legend: '' }];
        const defVars: { name: string; value: string }[] = [];
        setName(defName);
        setUnit(defUnit);
        setEnabled(true);
        setQueries(defQs);
        setChartVarRows(defVars);
        setBaselineJson(makeSnapshot(defName, defUnit, true, defQs, defVars));
        return;
      }

      setSelectedId(tpl.id ?? null);
      const tName = tpl.name || '';
      const tUnit = tpl.unit || '';
      const tEn = tpl.enabled !== false;
      const tQs =
        tpl.queries?.length > 0
          ? tpl.queries.map((q: any) => ({ promql: q.promql || '', legend: q.legend || '' }))
          : [{ promql: '', legend: '' }];
      const tVars = Object.entries(tpl.variables || {}).map(([k, v]) => ({
        name: k,
        value: String(v ?? ''),
      }));

      setName(tName);
      setUnit(tUnit);
      setEnabled(tEn);
      setQueries(tQs);
      setChartVarRows(tVars);
      setBaselineJson(makeSnapshot(tName, tUnit, tEn, tQs, tVars));

      if (shouldAutoPreview && data?.prometheusConfigured && tQs.some((q: any) => q.promql.trim())) {
        setTimeout(() => {
          triggerPreview({ name: tName, unit: tUnit, queries: tQs, chartVars: tVars });
        }, 30);
      }
    },
    [data?.prometheusConfigured, triggerPreview]
  );

  useEffect(() => {
    if (!data) return;
    setTemplates(data.templates || []);
    setVariables(data.variables || []);
    if (data.runs?.length > 0 && previewSrc === 'live:15') {
      setPreviewSrc(`run:${data.runs[0].id}`);
    }
    if (data.templates?.length > 0 && selectedId == null && !name) {
      loadIntoEditor(data.templates[0], false);
    } else if (data.templates?.length === 0 && selectedId == null && !name) {
      loadIntoEditor(null, false);
    }
  }, [data]);

  const handleSaveChart = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!name.trim()) {
      toast.error('Введите заголовок графика');
      return;
    }
    const nonEmptyQueries = queries.filter((q) => q.promql.trim());
    if (nonEmptyQueries.length === 0) {
      toast.error('Введите хотя бы один PromQL-запрос');
      return;
    }
    setSaving(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/charts/save`, {
        method: 'POST',
        body: {
          id: selectedId,
          name: name.trim(),
          unit: unit.trim(),
          enabled,
          queries: nonEmptyQueries,
          variables: buildChartVarsMap(chartVarRows),
        },
      });
      setTemplates(res.templates || []);
      if (res.template) loadIntoEditor(res.template, false);
      toast.success(res.message || 'График сохранён');
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  // Keyboard shortcuts: Ctrl+Enter -> Preview, Ctrl+S -> Save
  const shortcutRef = useRef({ preview: () => {}, save: () => {} });
  shortcutRef.current = {
    preview: () => triggerPreview(),
    save: () => handleSaveChart(),
  };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        shortcutRef.current.preview();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        shortcutRef.current.save();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // Combined variables map (script global + local chart overrides)
  const effectiveVarsMap = useMemo(() => {
    const m: Record<string, string> = {};
    variables.forEach((v) => {
      m[v.name] = v.value;
    });
    chartVarRows.forEach((r) => {
      const k = r.name.trim().replace(/^\$/, '');
      if (k) m[k] = r.value;
    });
    return m;
  }, [variables, chartVarRows]);

  // Referenced variables in current editor
  const editorVarRefs = useMemo(
    () => extractVarRefs([name, ...queries.map((q) => q.promql)]),
    [name, queries]
  );

  const missingEditorVars = useMemo(
    () => editorVarRefs.filter((v) => !Object.prototype.hasOwnProperty.call(effectiveVarsMap, v)),
    [editorVarRefs, effectiveVarsMap]
  );

  // Missing variables across ALL saved templates
  const allMissingScriptVars = useMemo(() => {
    const scriptVarNames = new Set(variables.map((v) => v.name));
    const missing = new Set<string>();
    templates.forEach((t) => {
      const localVars = t.variables || {};
      const refs = extractVarRefs([t.name || '', ...(t.queries || []).map((q: any) => q.promql || '')]);
      refs.forEach((r) => {
        if (!scriptVarNames.has(r) && !Object.prototype.hasOwnProperty.call(localVars, r)) {
          missing.add(r);
        }
      });
    });
    return Array.from(missing);
  }, [templates, variables]);

  const resolvedTitleHint = useMemo(() => {
    if (!name.includes('$') && !name.includes('[[')) return null;
    const res = resolveVarsInText(name, effectiveVarsMap);
    return res !== name ? res : null;
  }, [name, effectiveVarsMap]);

  // Filtered left list
  const filteredTemplates = useMemo(() => {
    const q = searchText.trim().toLowerCase();
    return templates.filter((t) => {
      if (filterMode === 'on' && !t.enabled) return false;
      if (filterMode === 'off' && t.enabled) return false;
      if (!q) return true;
      const inName = (t.name || '').toLowerCase().includes(q);
      const inQ = (t.queries || []).some((x: any) => (x.promql || '').toLowerCase().includes(q));
      return inName || inQ;
    });
  }, [templates, searchText, filterMode]);

  const reorderTemplates = async (nextOrderedIds: number[]) => {
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/charts/reorder`, {
        method: 'POST',
        body: { ids: nextOrderedIds },
      });
      setTemplates(res.templates || []);
    } catch (err: any) {
      toast.error(err.message);
    }
  };

  const moveTemplateByOffset = (tid: number, delta: -1 | 1) => {
    const ids = templates.map((t) => t.id);
    const idx = ids.indexOf(tid);
    const nextIdx = idx + delta;
    if (idx < 0 || nextIdx < 0 || nextIdx >= ids.length) return;
    [ids[idx], ids[nextIdx]] = [ids[nextIdx], ids[idx]];
    reorderTemplates(ids);
  };

  if (isLoading || !data) return <Spinner label="Загрузка студии графиков отчёта…" />;

  const enabledCount = templates.filter((t) => t.enabled).length;
  const chartStyle = data.chartStyle || {};
  const existingNamesSet = new Set(templates.map((t) => (t.name || '').trim().toLowerCase()));
  const selectedSrcScript = otherScripts.find((s) => String(s.id) === srcScriptId);
  const filteredSrcTemplates = (selectedSrcScript?.templates || []).filter((t: any) =>
    (t.name || '').toLowerCase().includes(copySearch.trim().toLowerCase())
  );

  return (
    <div className="space-y-4 pb-10">
      <ScriptHead script={data.script} />

      {/* Верхняя панель студии */}
      <Card className="p-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={() => loadIntoEditor(null, false)}>
            <Plus className="h-4 w-4" />
            Новый график
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              const edits: Record<number, string> = {};
              variables.forEach((v) => {
                edits[v.id] = v.value;
              });
              setVarEdits(edits);
              setVarsOpen(true);
            }}
          >
            <Variable className="h-3.5 w-3.5 text-indigo-600" />
            Переменные ($var: {variables.length})
            {allMissingScriptVars.length > 0 && (
              <Badge variant="warning">! {allMissingScriptVars.length}</Badge>
            )}
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              try {
                const res = await apiFetch(`/api/v1/scripts/${id}/charts/other-scripts`);
                const list = res.scripts || [];
                setOtherScripts(list);
                if (list[0]) {
                  setSrcScriptId(String(list[0].id));
                  setCopySelectedTids(new Set((list[0].templates || []).map((t: any) => t.id)));
                }
                setCopyOpen(true);
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <Copy className="h-3.5 w-3.5" />
            Из другого скрипта
          </Button>

          <Button variant="outline" size="sm" onClick={() => setGrafanaOpen(true)}>
            <Upload className="h-3.5 w-3.5" />
            Импорт Grafana JSON
          </Button>

          {templates.length > 0 && (
            <Button
              variant="outline"
              size="sm"
              title="Экспортировать графики и переменные в JSON дашборда Grafana (совместим с импортом в Grafana и k6-lab)"
              onClick={() => {
                const srcList = checkedIds.size > 0 ? templates.filter((t) => checkedIds.has(t.id)) : templates;
                const varMap = new Map<string, string>();
                variables.forEach((v: any) => {
                  if (v.name) varMap.set(v.name, v.value || '');
                });
                srcList.forEach((t: any) => {
                  if (t.variables && typeof t.variables === 'object') {
                    Object.entries(t.variables).forEach(([k, val]) => {
                      if (!varMap.has(k)) varMap.set(k, String(val ?? ''));
                    });
                  }
                });
                const payload = {
                  dashboard: {
                    title: `${data.script?.name || 'script'} — графики отчёта`,
                    templating: {
                      list: Array.from(varMap.entries()).map(([vName, vVal]) => ({
                        name: vName,
                        type: 'custom',
                        current: { value: vVal, text: vVal },
                      })),
                    },
                    panels: srcList.map((t: any, pIdx: number) => ({
                      id: pIdx + 1,
                      type: 'timeseries',
                      title: t.name,
                      fieldConfig: { defaults: { unit: t.unit || '' } },
                      targets: (t.queries || []).map((q: any, qIdx: number) => ({
                        refId: String.fromCharCode(65 + (qIdx % 26)),
                        datasource: { type: 'prometheus' },
                        expr: q.promql || '',
                        legendFormat: q.legend || '',
                      })),
                    })),
                  },
                };
                const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                const safeName = (data.script?.name || `script-${id}`).replace(/[\\/:*?"<>|\r\n]+/g, '_');
                a.href = url;
                a.download = `charts-${safeName}.json`;
                a.click();
                URL.revokeObjectURL(url);
                toast.success(`Экспортировано графиков: ${srcList.length}`);
              }}
            >
              <Download className="h-3.5 w-3.5" />
              Экспорт JSON{checkedIds.size > 0 ? ` (${checkedIds.size})` : ''}
            </Button>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Badge variant={data.prometheusConfigured ? 'success' : 'warning'}>
            <Database className="h-3 w-3" />
            {data.prometheusConfigured ? 'Prometheus подключён' : 'Prometheus не настроен'}
          </Badge>
          {chartStyle.width && (
            <button
              type="button"
              onClick={() => {
                setStTheme(chartStyle.theme || 'DARK');
                setStWidth(chartStyle.width || 1100);
                setStHeight(chartStyle.height || 320);
                setStLegend(chartStyle.legend || 'BOTTOM');
                setStStats(chartStyle.stats !== false);
                setStMaxLegend(chartStyle.maxLegend || 20);
                setStAutoWidth(chartStyle.autoWidth !== false);
                setStSortBy(chartStyle.sortBy || 'NONE');
                setStSortDir(chartStyle.sortDir || 'DESC');
                setStAbsTime(!!chartStyle.absoluteTime);
                setStSkipWarmup(!!chartStyle.skipWarmup);
                setStyleOpen(true);
              }}
              className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1 text-slate-600 hover:bg-indigo-50 hover:border-indigo-200 hover:text-indigo-700 cursor-pointer transition-colors"
              title="Настроить тему, размер и легенду графиков отчёта прямо здесь"
            >
              <Palette className="h-3.5 w-3.5 text-indigo-500" />
              {chartStyle.theme === 'LIGHT' ? 'Светлая' : 'Тёмная'} · {chartStyle.width}×{chartStyle.height}
            </button>
          )}
          {templates.length > 0 && (
            <Button
              variant="ghost"
              size="xs"
              onClick={async () => {
                const ok = await confirm({
                  title: 'Удалить ВСЕ свои графики этого скрипта?',
                  message: 'Все шаблоны графиков этого скрипта будут удалены безвозвратно.',
                });
                if (!ok) return;
                const res = await apiFetch(`/api/v1/scripts/${id}/charts/delete-all`, { method: 'POST' });
                setTemplates(res.templates || []);
                setCheckedIds(new Set());
                loadIntoEditor(null, false);
                toast.info('Все графики удалены');
              }}
            >
              <Trash2 className="h-3.5 w-3.5 text-rose-500" />
              Очистить все
            </Button>
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 items-start">
        {/* Левая колонка: список графиков с фильтром, перетаскиванием и массовыми операциями */}
        <Card className="lg:col-span-4 overflow-hidden">
          <CardHeader className="flex-col items-stretch gap-2.5">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-sm">
                Графики отчёта ({enabledCount} вкл. из {templates.length})
              </span>
              <span className="text-[11px] text-slate-400">перетащите для порядка</span>
            </div>

            {templates.length > 0 && (
              <>
                <div className="flex items-center gap-1.5">
                  <div className="relative flex-1">
                    <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-2" />
                    <Input
                      value={searchText}
                      onChange={(e) => setSearchText(e.target.value)}
                      placeholder="Поиск по названию или PromQL…"
                      className="pl-8 h-7 text-xs"
                    />
                  </div>
                  <div className="inline-flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-[11px]">
                    {(
                      [
                        { k: 'all', l: 'Все' },
                        { k: 'on', l: 'В отчёте' },
                        { k: 'off', l: 'Выкл.' },
                      ] as const
                    ).map((f) => (
                      <button
                        key={f.k}
                        type="button"
                        onClick={() => setFilterMode(f.k)}
                        className={`rounded px-2 py-0.5 font-medium cursor-pointer ${
                          filterMode === f.k ? 'bg-indigo-600 text-white' : 'text-slate-600'
                        }`}
                      >
                        {f.l}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Панель выбора и групповых действий */}
                <div className="flex flex-wrap items-center justify-between gap-2 pt-1 border-t border-slate-100 text-xs">
                  <label className="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={
                        filteredTemplates.length > 0 &&
                        filteredTemplates.every((t) => checkedIds.has(t.id))
                      }
                      onChange={(e) => {
                        const next = new Set(checkedIds);
                        filteredTemplates.forEach((t) => {
                          if (e.target.checked) next.add(t.id);
                          else next.delete(t.id);
                        });
                        setCheckedIds(next);
                      }}
                    />
                    Выбрать все ({checkedIds.size})
                  </label>

                  {checkedIds.size > 0 && (
                    <div className="flex items-center gap-1">
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={async () => {
                          const res = await apiFetch(`/api/v1/scripts/${id}/charts/bulk-toggle`, {
                            method: 'POST',
                            body: { ids: Array.from(checkedIds), enabled: true },
                          });
                          setTemplates(res.templates || []);
                          toast.success(`Включено: ${res.count}`);
                        }}
                      >
                        Вкл.
                      </Button>
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={async () => {
                          const res = await apiFetch(`/api/v1/scripts/${id}/charts/bulk-toggle`, {
                            method: 'POST',
                            body: { ids: Array.from(checkedIds), enabled: false },
                          });
                          setTemplates(res.templates || []);
                          toast.success(`Выключено: ${res.count}`);
                        }}
                      >
                        Выкл.
                      </Button>
                      <Button
                        variant="danger"
                        size="xs"
                        onClick={async () => {
                          const ids = Array.from(checkedIds);
                          const ok = await confirm({
                            title: 'Удалить выбранные графики?',
                            message: `Будет удалено графиков: ${ids.length}`,
                          });
                          if (!ok) return;
                          const res = await apiFetch(`/api/v1/scripts/${id}/charts/delete-selected`, {
                            method: 'POST',
                            body: { ids },
                          });
                          setTemplates(res.templates || []);
                          setCheckedIds(new Set());
                          if (selectedId && ids.includes(selectedId)) {
                            loadIntoEditor(res.templates?.[0] || null, false);
                          }
                        }}
                      >
                        Удалить
                      </Button>
                    </div>
                  )}
                </div>
              </>
            )}
          </CardHeader>

          <CardBody className="p-0 max-h-[68vh] overflow-y-auto divide-y divide-slate-100">
            {filteredTemplates.map((t) => {
              const globalIdx = templates.findIndex((x) => x.id === t.id);
              const isSelected = selectedId === t.id;
              const localVarCount = Object.keys(t.variables || {}).length;
              const scriptVarNames = new Set(variables.map((v) => v.name));
              const tRefs = extractVarRefs([
                t.name || '',
                ...(t.queries || []).map((q: any) => q.promql || ''),
              ]);
              const tMissing = tRefs.filter(
                (r) => !scriptVarNames.has(r) && !Object.prototype.hasOwnProperty.call(t.variables || {}, r)
              );

              return (
                <div
                  key={t.id}
                  draggable={!searchText.trim() && filterMode === 'all'}
                  onDragStart={() => setDragSrcId(t.id)}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={() => {
                    if (dragSrcId == null || dragSrcId === t.id) return;
                    const ids = templates.map((x) => x.id);
                    const from = ids.indexOf(dragSrcId);
                    const to = ids.indexOf(t.id);
                    if (from < 0 || to < 0) return;
                    ids.splice(from, 1);
                    ids.splice(to, 0, dragSrcId);
                    setDragSrcId(null);
                    reorderTemplates(ids);
                  }}
                  onClick={() => loadIntoEditor(t, autoPreview)}
                  className={`group px-3 py-2.5 cursor-pointer text-xs transition-colors ${
                    isSelected
                      ? 'bg-indigo-50/90 border-l-4 border-l-indigo-600'
                      : 'hover:bg-slate-50'
                  } ${!t.enabled ? 'opacity-65' : ''}`}
                >
                  <div className="flex items-center gap-2">
                    <GripVertical className="h-3.5 w-3.5 text-slate-300 shrink-0 cursor-grab" />
                    <input
                      type="checkbox"
                      checked={checkedIds.has(t.id)}
                      onClick={(ev) => ev.stopPropagation()}
                      onChange={(e) => {
                        const next = new Set(checkedIds);
                        if (e.target.checked) next.add(t.id);
                        else next.delete(t.id);
                        setCheckedIds(next);
                      }}
                    />
                    <span className="font-mono text-[11px] text-slate-400">#{globalIdx + 1}</span>
                    <span className="font-semibold text-slate-900 truncate flex-1">{t.name}</span>

                    <div
                      onClick={(ev) => ev.stopPropagation()}
                      className="flex items-center gap-1 shrink-0"
                    >
                      <button
                        type="button"
                        disabled={globalIdx <= 0}
                        title="Выше в отчёте"
                        onClick={() => moveTemplateByOffset(t.id, -1)}
                        className="opacity-0 group-hover:opacity-100 rounded p-0.5 text-slate-400 hover:bg-slate-200/60 hover:text-slate-700 disabled:opacity-20 cursor-pointer"
                      >
                        <ArrowUp className="h-3 w-3" />
                      </button>
                      <button
                        type="button"
                        disabled={globalIdx >= templates.length - 1}
                        title="Ниже в отчёте"
                        onClick={() => moveTemplateByOffset(t.id, 1)}
                        className="opacity-0 group-hover:opacity-100 rounded p-0.5 text-slate-400 hover:bg-slate-200/60 hover:text-slate-700 disabled:opacity-20 cursor-pointer"
                      >
                        <ArrowDown className="h-3 w-3" />
                      </button>
                      <Switch
                        checked={!!t.enabled}
                        onChange={async (en) => {
                          await apiFetch(`/api/v1/scripts/${id}/charts/${t.id}/toggle`, {
                            method: 'POST',
                            body: { enabled: en },
                          });
                          setTemplates((prev) =>
                            prev.map((item) => (item.id === t.id ? { ...item, enabled: en } : item))
                          );
                          if (selectedId === t.id) setEnabled(en);
                        }}
                      />
                    </div>
                  </div>

                  <div className="mt-1.5 pl-6 flex flex-wrap items-center gap-1.5 text-[11px]">
                    {t.unit && <Badge variant="neutral">{t.unit}</Badge>}
                    <span className="text-slate-500">{t.queries?.length || 0} запр.</span>
                    {localVarCount > 0 && <Badge variant="info">$var: {localVarCount}</Badge>}
                    {tMissing.length > 0 && (
                      <Badge variant="warning" title={`Не заданы: $${tMissing.join(', $')}`}>
                        ! ${tMissing[0]}
                      </Badge>
                    )}
                  </div>
                  {t.queries?.[0]?.promql && (
                    <div className="mt-1 pl-6 font-mono text-[10px] text-slate-400 truncate">
                      {t.queries[0].promql}
                    </div>
                  )}
                </div>
              );
            })}

            {templates.length === 0 && (
              <div className="p-6 text-center space-y-3">
                <div className="text-xs font-semibold text-slate-700">Своих графиков отчёта пока нет</div>
                <p className="text-[11px] text-slate-500">
                  Выберите готовый шаблон метрики (CPU, RAM, JVM, RPS), импортируйте дашборд Grafana или скопируйте графики из другого скрипта.
                </p>
                <div className="flex flex-wrap justify-center gap-1.5">
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => {
                      const cpuPreset = CHART_PRESETS[1].items[0];
                      setSelectedId(null);
                      setName(cpuPreset.name);
                      setUnit(cpuPreset.unit);
                      setQueries(cpuPreset.queries.map((q) => ({ ...q })));
                    }}
                  >
                    <Sparkles className="h-3.5 w-3.5 text-indigo-600" />
                    Шаблон CPU серверов
                  </Button>
                  <Button variant="outline" size="xs" onClick={() => setGrafanaOpen(true)}>
                    Импорт Grafana
                  </Button>
                </div>
              </div>
            )}
          </CardBody>
        </Card>

        {/* Правая колонка: конструктор графика + предпросмотр */}
        <Card className="lg:col-span-8">
          <CardHeader>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-sm">
                {selectedId ? `Редактирование графика #${selectedId}` : 'Конструктор нового графика'}
              </span>
              {isDirty && <Badge variant="warning">● Изменено</Badge>}
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              {/* Выпадающий список 12 готовых шаблонов графиков */}
              <Select
                value=""
                onChange={(e) => {
                  const [gIdx, iIdx] = e.target.value.split(':').map(Number);
                  const preset = CHART_PRESETS[gIdx]?.items?.[iIdx];
                  if (!preset) return;
                  setName(preset.name);
                  setUnit(preset.unit);
                  setQueries(preset.queries.map((q) => ({ ...q })));
                  toast.info(`Подставлен шаблон «${preset.name}»`);
                }}
                className="h-7 text-xs w-52"
              >
                <option value="">⚡ Шаблон графика (12)…</option>
                {CHART_PRESETS.map((grp, gIdx) => (
                  <optgroup key={grp.group} label={grp.group}>
                    {grp.items.map((it, iIdx) => (
                      <option key={it.name} value={`${gIdx}:${iIdx}`}>
                        {it.name}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </Select>

              {isDirty && (
                <Button
                  variant="outline"
                  size="xs"
                  title="Отменить несохранённые правки"
                  onClick={() => {
                    const orig = templates.find((t) => t.id === selectedId) || null;
                    loadIntoEditor(orig, false);
                  }}
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  Сбросить
                </Button>
              )}

              {selectedId && (
                <>
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={async () => {
                      const res = await apiFetch(`/api/v1/scripts/${id}/charts/${selectedId}/duplicate`, {
                        method: 'POST',
                      });
                      setTemplates(res.templates || []);
                      if (res.template) loadIntoEditor(res.template, false);
                      toast.success(res.message || 'Создана копия');
                    }}
                  >
                    <Copy className="h-3.5 w-3.5" />
                    Дублировать
                  </Button>
                  <Button
                    variant="danger"
                    size="xs"
                    onClick={async () => {
                      const ok = await confirm({
                        title: 'Удалить график?',
                        message: `График «${name}» будет удалён безвозвратно.`,
                      });
                      if (!ok) return;
                      const res = await apiFetch(`/api/v1/scripts/${id}/charts/${selectedId}/delete`, {
                        method: 'POST',
                      });
                      setTemplates(res.templates || []);
                      loadIntoEditor(res.templates?.[0] || null, false);
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </>
              )}
            </div>
          </CardHeader>

          <CardBody className="space-y-4 text-xs">
            <form onSubmit={handleSaveChart} className="space-y-4">
              {/* Заголовок, единица измерения, включение */}
              <div className="grid grid-cols-1 sm:grid-cols-12 gap-3 items-start">
                <div className="sm:col-span-6 space-y-1">
                  <label className="block text-slate-600 font-medium">Заголовок графика *</label>
                  <Input
                    required
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="Утилизация CPU серверов ($env)"
                  />
                  {resolvedTitleHint && (
                    <div className="text-[11px] text-indigo-600 truncate">
                      В отчёте: <b>{resolvedTitleHint}</b>
                    </div>
                  )}
                </div>

                <div className="sm:col-span-4 space-y-1">
                  <label className="block text-slate-600 font-medium">Единица измерения</label>
                  <Input
                    list="chart-unit-options"
                    value={unit}
                    onChange={(e) => setUnit(e.target.value)}
                    placeholder="%, мс, оп/с, Б/с"
                  />
                  <datalist id="chart-unit-options">
                    <option value="%" />
                    <option value="мс" />
                    <option value="с" />
                    <option value="оп/с" />
                    <option value="Б" />
                    <option value="Б/с" />
                    <option value="шт" />
                  </datalist>
                </div>

                <div className="sm:col-span-2 pt-6">
                  <Switch
                    checked={enabled}
                    onChange={setEnabled}
                    label={<span className="text-xs font-medium">В отчёте</span>}
                  />
                </div>
              </div>

              {/* Локальные переменные этого графика + автообнаружение $var */}
              <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3 space-y-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-semibold text-slate-700">
                    Локальные переменные графика ({chartVarRows.filter((r) => r.name.trim()).length})
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={() => setChartVarRows((prev) => [...prev, { name: '', value: '' }])}
                  >
                    <Plus className="h-3 w-3" />
                    Переменная графика
                  </Button>
                </div>

                {missingEditorVars.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-1.5 text-[11px] text-amber-900">
                    <AlertTriangle className="h-3.5 w-3.5 text-amber-600 shrink-0" />
                    <span>В запросах найдены незаданные переменные (клик добавит):</span>
                    {missingEditorVars.map((mv) => (
                      <button
                        key={mv}
                        type="button"
                        onClick={() => setChartVarRows((prev) => [...prev, { name: mv, value: '' }])}
                        className="rounded bg-amber-200/70 px-1.5 py-0.5 font-mono font-semibold text-amber-950 hover:bg-amber-300 cursor-pointer"
                      >
                        + ${mv}
                      </button>
                    ))}
                  </div>
                )}

                {chartVarRows.length > 0 && (
                  <div className="space-y-1.5">
                    {chartVarRows.map((vr, vIdx) => (
                      <div key={vIdx} className="flex items-center gap-2">
                        <span className="font-mono text-slate-400">$</span>
                        <Input
                          value={vr.name}
                          onChange={(e) =>
                            setChartVarRows((prev) =>
                              prev.map((it, i) =>
                                i === vIdx ? { ...it, name: e.target.value.replace(/^\$/, '') } : it
                              )
                            )
                          }
                          placeholder="instance"
                          className="h-7 w-40 font-mono text-xs bg-white"
                        />
                        <span className="text-slate-400">=</span>
                        <Input
                          value={vr.value}
                          onChange={(e) =>
                            setChartVarRows((prev) =>
                              prev.map((it, i) => (i === vIdx ? { ...it, value: e.target.value } : it))
                            )
                          }
                          placeholder="app-01:9100|app-02:9100"
                          className="h-7 flex-1 font-mono text-xs bg-white"
                        />
                        <button
                          type="button"
                          onClick={() => setChartVarRows((prev) => prev.filter((_, i) => i !== vIdx))}
                          className="text-slate-400 hover:text-rose-600 cursor-pointer"
                        >
                          <X className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Запросы PromQL (A, B, C...) */}
              <div className="space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-800">Запросы PromQL ({queries.length})</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={() => setQueries((prev) => [...prev, { promql: '', legend: '' }])}
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Добавить запрос
                  </Button>
                </div>

                {queries.map((qItem, qIdx) => {
                  const letter = String.fromCharCode(65 + (qIdx % 26));
                  const diag = queryDiags[qIdx];

                  return (
                    <div key={qIdx} className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 space-y-2">
                      {/* Шапка запроса + диагностика + управление строкой */}
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="inline-flex h-6 w-6 items-center justify-center rounded-md bg-indigo-600 font-mono text-xs font-bold text-white">
                          {letter}
                        </span>

                        {diag && (
                          <>
                            {diag.error ? (
                              <Badge variant="danger">✗ Ошибка PromQL</Badge>
                            ) : (diag.seriesCount || 0) > 0 ? (
                              <Badge variant="success">✓ {diag.seriesCount} рядов</Badge>
                            ) : (
                              <Badge variant="warning">⚠ 0 рядов</Badge>
                            )}
                            {diag.expandedPromql && (
                              <button
                                type="button"
                                onClick={() =>
                                  setExpandedDiagIdx((p) => ({ ...p, [qIdx]: !p[qIdx] }))
                                }
                                className="inline-flex items-center gap-1 text-[11px] text-indigo-600 hover:underline cursor-pointer"
                              >
                                <FileCode2 className="h-3 w-3" />
                                Развёрнутый PromQL
                              </button>
                            )}
                          </>
                        )}

                        <div className="flex items-center gap-0.5 ml-auto">
                          <button
                            type="button"
                            disabled={qIdx === 0}
                            title="Выше"
                            onClick={() => {
                              if (qIdx === 0) return;
                              setQueries((prev) => {
                                const next = [...prev];
                                [next[qIdx - 1], next[qIdx]] = [next[qIdx], next[qIdx - 1]];
                                return next;
                              });
                            }}
                            className="rounded p-1 text-slate-400 hover:bg-slate-200/60 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                          >
                            <ArrowUp className="h-3.5 w-3.5" />
                          </button>
                          <button
                            type="button"
                            disabled={qIdx >= queries.length - 1}
                            title="Ниже"
                            onClick={() => {
                              if (qIdx >= queries.length - 1) return;
                              setQueries((prev) => {
                                const next = [...prev];
                                [next[qIdx + 1], next[qIdx]] = [next[qIdx], next[qIdx + 1]];
                                return next;
                              });
                            }}
                            className="rounded p-1 text-slate-400 hover:bg-slate-200/60 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                          >
                            <ArrowDown className="h-3.5 w-3.5" />
                          </button>
                          <button
                            type="button"
                            title="Дублировать запрос"
                            onClick={() =>
                              setQueries((prev) => [
                                ...prev.slice(0, qIdx + 1),
                                { ...qItem },
                                ...prev.slice(qIdx + 1),
                              ])
                            }
                            className="rounded p-1 text-slate-400 hover:bg-slate-200/60 hover:text-indigo-600 cursor-pointer"
                          >
                            <Copy className="h-3.5 w-3.5" />
                          </button>
                          {queries.length > 1 && (
                            <button
                              type="button"
                              title="Удалить запрос"
                              onClick={() => setQueries((prev) => prev.filter((_, i) => i !== qIdx))}
                              className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600 cursor-pointer"
                            >
                              <X className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                      </div>

                      {/* Редактор PromQL с автодополнением метрик, функций, меток и переменных */}
                      <PromqlEditor
                        textareaRef={(el) => {
                          queryTaRefs.current[qIdx] = el;
                        }}
                        rows={2}
                        value={qItem.promql}
                        onChange={(val) =>
                          setQueries((prev) =>
                            prev.map((it, i) => (i === qIdx ? { ...it, promql: val } : it))
                          )
                        }
                        scriptId={id}
                        variables={Object.keys(effectiveVarsMap)}
                        varValues={effectiveVarsMap}
                        allowMacros={true}
                        showToolbar={true}
                        showTestButton={true}
                        placeholder="100 - (avg by (instance) (rate(node_cpu_seconds_total{mode='idle'}[{range}])) * 100)"
                      />

                      {/* Легенда */}
                      <div className="flex items-center gap-2">
                        <span className="text-[11px] text-slate-500">Легенда:</span>
                        <Input
                          value={qItem.legend}
                          onChange={(e) =>
                            setQueries((prev) =>
                              prev.map((it, i) => (i === qIdx ? { ...it, legend: e.target.value } : it))
                            )
                          }
                          placeholder="{{instance}}"
                          className="h-7 w-64 font-mono text-xs bg-white"
                        />
                      </div>

                      {diag?.error && (
                        <div className="rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-[11px] text-rose-700 font-mono">
                          {diag.error}
                        </div>
                      )}

                      {expandedDiagIdx[qIdx] && diag?.expandedPromql && (
                        <div className="rounded-lg border border-slate-200 bg-slate-900 text-slate-100 px-2.5 py-1.5 font-mono text-[11px] break-all">
                          {diag.expandedPromql}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              {/* Панель предпросмотра и сохранения */}
              <div className="flex flex-wrap items-center justify-between gap-2 pt-3 border-t border-slate-100">
                <div className="flex flex-wrap items-center gap-2">
                  <Select
                    value={previewSrc}
                    onChange={(e) => setPreviewSrc(e.target.value)}
                    className="h-8 w-64 text-xs"
                  >
                    <optgroup label="Живое окно (сейчас)">
                      <option value="live:15">Последние 15 минут (live)</option>
                      <option value="live:30">Последние 30 минут (live)</option>
                      <option value="live:60">Последний 1 час (live)</option>
                      <option value="live:360">Последние 6 часов (live)</option>
                    </optgroup>
                    {(data.runs || []).length > 0 && (
                      <optgroup label="Прошедшие запуски скрипта">
                        {(data.runs || []).map((r: any) => (
                          <option key={r.id} value={`run:${r.id}`}>
                            {r.label || `#${r.id} (${r.status})`}
                          </option>
                        ))}
                      </optgroup>
                    )}
                  </Select>

                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    loading={previewLoading}
                    onClick={() => triggerPreview()}
                    title="Ctrl+Enter"
                  >
                    <Eye className="h-3.5 w-3.5" />
                    Предпросмотр
                  </Button>

                  <Switch
                    checked={autoPreview}
                    onChange={setAutoPreview}
                    label={<span className="text-[11px] text-slate-500">Авто при выборе</span>}
                  />
                </div>

                <Button type="submit" size="sm" loading={saving} title="Ctrl+S">
                  <Save className="h-3.5 w-3.5" />
                  {selectedId ? 'Сохранить изменения' : 'Создать график'}
                </Button>
              </div>
            </form>

            {previewErr && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-rose-700">{previewErr}</div>
            )}

            {previewMeta && (
              <div className="flex flex-wrap items-center justify-between text-[11px] text-slate-500">
                <span>
                  Окно: <b>{previewMeta.windowLabel}</b> · Временных рядов: <b>{previewMeta.seriesCount}</b> · Ответ Prometheus:{' '}
                  <b>{previewMeta.elapsedMs} мс</b>
                </span>
                {previewImg && (
                  <div className="inline-flex items-center gap-3">
                    <button
                      type="button"
                      onClick={() => {
                        const safe = (name || previewMeta.title || 'chart').replace(/[\\/:*?"<>|\r\n]+/g, '_');
                        const a = document.createElement('a');
                        a.href = previewImg;
                        a.download = `${safe}.png`;
                        a.click();
                      }}
                      className="inline-flex items-center gap-1 text-slate-600 hover:text-indigo-600 hover:underline cursor-pointer"
                    >
                      <Download className="h-3 w-3" />
                      Скачать PNG
                    </button>
                    <button
                      type="button"
                      onClick={() => setZoomOpen(true)}
                      className="inline-flex items-center gap-1 text-indigo-600 hover:underline cursor-pointer"
                    >
                      <Maximize2 className="h-3 w-3" />
                      Увеличить
                    </button>
                  </div>
                )}
              </div>
            )}

            {previewImg && (
              <div
                onClick={() => setZoomOpen(true)}
                className="rounded-xl border border-slate-200 p-2 bg-slate-50 cursor-zoom-in"
                title="Нажмите для увеличения"
              >
                <img src={previewImg} alt="Предпросмотр графика" className="w-full h-auto rounded-lg" />
              </div>
            )}
          </CardBody>
        </Card>
      </div>

      {/* Увеличенный просмотр рендера графика */}
      <Modal
        open={zoomOpen && !!previewImg}
        onClose={() => setZoomOpen(false)}
        title={previewMeta?.title || name || 'Предпросмотр графика'}
        maxWidth="max-w-6xl"
      >
        {previewImg && (
          <div className="space-y-2">
            <img src={previewImg} alt="Zoom" className="w-full h-auto rounded-lg border border-slate-200" />
            <div className="flex items-center justify-between text-xs text-slate-500">
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  const safe = (name || previewMeta?.title || 'chart').replace(/[\\/:*?"<>|\r\n]+/g, '_');
                  const a = document.createElement('a');
                  a.href = previewImg;
                  a.download = `${safe}.png`;
                  a.click();
                }}
              >
                <Download className="h-3.5 w-3.5" />
                Скачать PNG
              </Button>
              {previewMeta && (
                <span>
                  {previewMeta.windowLabel} · {previewMeta.seriesCount} рядов · {previewMeta.elapsedMs} мс
                </span>
              )}
            </div>
          </div>
        )}
      </Modal>

      {/* Модалка общих переменных скрипта (с инлайн-редактированием) */}
      <Modal
        open={varsOpen}
        onClose={() => setVarsOpen(false)}
        title="Общие переменные графиков скрипта ($var)"
        maxWidth="max-w-2xl"
      >
        <div className="space-y-3 text-xs">
          {allMissingScriptVars.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900">
              <AlertTriangle className="h-3.5 w-3.5 text-amber-600" />
              <span>Используются в графиках, но не заданы:</span>
              {allMissingScriptVars.map((mv) => (
                <button
                  key={mv}
                  type="button"
                  onClick={() => setNewVarName(mv)}
                  className="rounded bg-amber-200/80 px-1.5 py-0.5 font-mono font-semibold hover:bg-amber-300 cursor-pointer"
                >
                  + ${mv}
                </button>
              ))}
            </div>
          )}

          <div className="divide-y divide-slate-100 max-h-72 overflow-y-auto">
            {variables.map((v) => {
              const editedVal = varEdits[v.id] ?? v.value;
              const changed = editedVal !== v.value;
              return (
                <div key={v.id} className="py-2 flex items-center gap-2">
                  <code className="font-semibold text-indigo-700 w-36 truncate">${v.name}</code>
                  <Input
                    value={editedVal}
                    onChange={(e) => setVarEdits((p) => ({ ...p, [v.id]: e.target.value }))}
                    className="h-7 flex-1 font-mono text-xs"
                  />
                  {changed && (
                    <Button
                      size="xs"
                      onClick={async () => {
                        const res = await apiFetch(`/api/v1/scripts/${id}/charts/variables/save`, {
                          method: 'POST',
                          body: { name: v.name, value: editedVal },
                        });
                        setVariables(res.variables || []);
                        toast.success(`Переменная $${v.name} обновлена`);
                      }}
                    >
                      <Check className="h-3 w-3" />
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={async () => {
                      const res = await apiFetch(`/api/v1/scripts/${id}/charts/variables/${v.id}/delete`, {
                        method: 'POST',
                      });
                      if (res.variables) setVariables(res.variables);
                    }}
                  >
                    <X className="h-4 w-4 text-rose-500" />
                  </Button>
                </div>
              );
            })}
            {variables.length === 0 && (
              <div className="py-4 text-center text-slate-400">Общих переменных пока нет</div>
            )}
          </div>

          <form
            className="flex gap-2 pt-2 border-t border-slate-100"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                const res = await apiFetch(`/api/v1/scripts/${id}/charts/variables/save`, {
                  method: 'POST',
                  body: { name: newVarName.trim(), value: newVarVal },
                });
                const nextVars = res.variables || [];
                setVariables(nextVars);
                const edits: Record<number, string> = {};
                nextVars.forEach((nv: any) => {
                  edits[nv.id] = nv.value;
                });
                setVarEdits(edits);
                setNewVarName('');
                setNewVarVal('');
                toast.success(`Переменная $${res.name} сохранена`);
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <Input
              required
              value={newVarName}
              onChange={(e) => setNewVarName(e.target.value)}
              placeholder="instance"
              className="w-40 font-mono text-xs"
            />
            <Input
              value={newVarVal}
              onChange={(e) => setNewVarVal(e.target.value)}
              placeholder="app-1:9100|app-2:9100"
              className="flex-1 font-mono text-xs"
            />
            <Button type="submit" size="sm">
              Добавить
            </Button>
          </form>
        </div>
      </Modal>

      {/* Импорт дашборда Grafana JSON (файл + drag-and-drop + текст) */}
      <Modal
        open={grafanaOpen}
        onClose={() => setGrafanaOpen(false)}
        title="Импорт дашборда Grafana (JSON)"
        maxWidth="max-w-2xl"
      >
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!grafanaFile && !grafanaJson.trim()) {
              toast.error('Выберите JSON-файл дашборда или вставьте JSON');
              return;
            }
            setImportingGrafana(true);
            try {
              const fd = new FormData();
              if (grafanaFile) fd.append('file', grafanaFile);
              if (grafanaJson.trim()) fd.append('json', grafanaJson);
              const res = await apiUpload(`/api/v1/scripts/${id}/charts/import-grafana`, fd);
              setTemplates(res.templates || []);
              setVariables(res.variables || []);
              setGrafanaOpen(false);
              setGrafanaJson('');
              setGrafanaFile(null);
              if (res.templates?.length > 0 && selectedId == null) {
                loadIntoEditor(res.templates[0], false);
              }
              toast.success(res.message);
            } catch (err: any) {
              toast.error(err.message);
            } finally {
              setImportingGrafana(false);
            }
          }}
        >
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setGrafanaDragOver(true);
            }}
            onDragLeave={() => setGrafanaDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setGrafanaDragOver(false);
              const f = e.dataTransfer.files?.[0];
              if (f) setGrafanaFile(f);
            }}
            className={`rounded-xl border-2 border-dashed p-3 text-center transition-colors ${
              grafanaDragOver
                ? 'border-indigo-500 bg-indigo-50/60'
                : 'border-slate-300 bg-slate-50/40 hover:border-indigo-300'
            }`}
          >
            <label className="cursor-pointer flex flex-col items-center gap-1">
              <Upload className="h-5 w-5 text-indigo-600" />
              <span className="font-medium text-slate-700">
                {grafanaFile
                  ? `Выбран файл: ${grafanaFile.name} (${Math.round(grafanaFile.size / 1024)} КБ)`
                  : 'Перетащите экспортированный .json дашборда Grafana сюда или нажмите для выбора'}
              </span>
              <input
                type="file"
                accept=".json,application/json"
                onChange={(e) => setGrafanaFile(e.target.files?.[0] || null)}
                className="hidden"
              />
            </label>
          </div>

          <Textarea
            rows={7}
            value={grafanaJson}
            onChange={(e) => setGrafanaJson(e.target.value)}
            placeholder="Или вставьте JSON экспортированного дашборда Grafana…"
            className="font-mono text-xs"
          />
          <p className="text-[11px] text-slate-500">
            Автоматически переносятся панели с PromQL-запросами, единицы измерения, легенды и используемые переменные дашборда (<code>$__rate_interval</code> заменяется на <code>{'{range}'}</code>).
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setGrafanaOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={importingGrafana}>
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Полноценное выборочное копирование графиков из другого скрипта */}
      <Modal
        open={copyOpen}
        onClose={() => setCopyOpen(false)}
        title="Скопировать графики из другого скрипта"
        maxWidth="max-w-3xl"
      >
        {otherScripts.length === 0 ? (
          <div className="py-6 text-center text-xs text-slate-500">
            У других скриптов пока нет сохранённых графиков отчёта.
          </div>
        ) : (
          <form
            className="space-y-3 text-xs"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!srcScriptId || copySelectedTids.size === 0) {
                toast.warning('Выберите хотя бы один график для копирования');
                return;
              }
              setCopyingCharts(true);
              try {
                const res = await apiFetch(`/api/v1/scripts/${id}/charts/copy-from-script`, {
                  method: 'POST',
                  body: {
                    sourceScriptId: Number(srcScriptId),
                    templateIds: Array.from(copySelectedTids),
                    copyVariables: copyVarsFlag,
                    overwriteExisting: copyOverwriteFlag,
                  },
                });
                setTemplates(res.templates || []);
                if (res.variables) setVariables(res.variables);
                setCopyOpen(false);
                qc.invalidateQueries({ queryKey: ['script-charts', id] });
                toast.success(res.message || 'Графики скопированы');
              } catch (err: any) {
                toast.error(err.message);
              } finally {
                setCopyingCharts(false);
              }
            }}
          >
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <div>
                <label className="block text-slate-600 mb-1 font-medium">Скрипт-источник</label>
                <Select
                  required
                  value={srcScriptId}
                  onChange={(e) => {
                    const nextId = e.target.value;
                    setSrcScriptId(nextId);
                    const sObj = otherScripts.find((s) => String(s.id) === nextId);
                    setCopySelectedTids(new Set((sObj?.templates || []).map((t: any) => t.id)));
                  }}
                >
                  {otherScripts.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name} ({s.chartCount} граф., {s.variableCount} перем.)
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <label className="block text-slate-600 mb-1 font-medium">Фильтр графиков</label>
                <Input
                  value={copySearch}
                  onChange={(e) => setCopySearch(e.target.value)}
                  placeholder="Поиск по названию…"
                />
              </div>
            </div>

            <div className="flex items-center justify-between pt-1">
              <label className="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={
                    filteredSrcTemplates.length > 0 &&
                    filteredSrcTemplates.every((t: any) => copySelectedTids.has(t.id))
                  }
                  onChange={(e) => {
                    const next = new Set(copySelectedTids);
                    filteredSrcTemplates.forEach((t: any) => {
                      if (e.target.checked) next.add(t.id);
                      else next.delete(t.id);
                    });
                    setCopySelectedTids(next);
                  }}
                />
                Выбрать все видимые ({copySelectedTids.size})
              </label>
            </div>

            <div className="max-h-64 overflow-y-auto rounded-xl border border-slate-200 divide-y divide-slate-100">
              {filteredSrcTemplates.map((t: any) => {
                const exists = existingNamesSet.has((t.name || '').trim().toLowerCase());
                const vCnt = Object.keys(t.variables || {}).length;
                return (
                  <div key={t.id} className="flex items-center justify-between gap-2 px-3 py-2 hover:bg-slate-50">
                    <label className="flex items-center gap-2 min-w-0 flex-1 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={copySelectedTids.has(t.id)}
                        onChange={(e) => {
                          const next = new Set(copySelectedTids);
                          if (e.target.checked) next.add(t.id);
                          else next.delete(t.id);
                          setCopySelectedTids(next);
                        }}
                      />
                      <span className="font-semibold text-slate-800 truncate">{t.name}</span>
                      {t.unit && <Badge variant="neutral">{t.unit}</Badge>}
                      <span className="text-slate-400">{t.queries?.length || 0} запр.</span>
                      {vCnt > 0 && <Badge variant="info">$var: {vCnt}</Badge>}
                      {exists && <Badge variant="warning">уже есть</Badge>}
                    </label>

                    <Button
                      type="button"
                      variant="ghost"
                      size="xs"
                      title="Загрузить в конструктор как черновик без сохранения"
                      onClick={() => {
                        loadIntoEditor({ ...t, id: null }, false);
                        setCopyOpen(false);
                        toast.info(`Шаблон «${t.name}» открыт в конструкторе как черновик`);
                      }}
                    >
                      В конструктор
                    </Button>
                  </div>
                );
              })}
            </div>

            <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 space-y-2">
              <Switch
                checked={copyVarsFlag}
                onChange={setCopyVarsFlag}
                label={
                  <span className="text-xs">
                    Также скопировать общие переменные скрипта ({selectedSrcScript?.variableCount || 0})
                  </span>
                }
              />
              <Switch
                checked={copyOverwriteFlag}
                onChange={setCopyOverwriteFlag}
                label={
                  <span className="text-xs text-slate-600">
                    Перезаписывать графики с совпадающим названием (иначе создаётся копия с суффиксом)
                  </span>
                }
              />
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={() => setCopyOpen(false)}>
                Отмена
              </Button>
              <Button type="submit" size="sm" loading={copyingCharts}>
                Скопировать выбранные ({copySelectedTids.size})
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Инлайн-настройка глобального оформления графиков отчёта */}
      <Modal
        open={styleOpen}
        onClose={() => setStyleOpen(false)}
        title="Оформление графиков в отчётах (HTML / PDF / Word)"
        maxWidth="max-w-5xl"
      >
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 text-xs">
          <div className="lg:col-span-5 space-y-3">
            {!isAdmin && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900">
                Предпросмотр доступен всем, а сохранение глобального стиля отчётов требует роли администратора.
              </div>
            )}
            <div className="grid grid-cols-2 gap-2.5">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Тема</label>
                <Select value={stTheme} onChange={(e) => setStTheme(e.target.value)}>
                  <option value="DARK">Grafana Dark (Тёмная)</option>
                  <option value="LIGHT">Светлая (Light)</option>
                </Select>
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Положение легенды</label>
                <Select value={stLegend} onChange={(e) => setStLegend(e.target.value)}>
                  <option value="BOTTOM">Снизу</option>
                  <option value="RIGHT">Справа</option>
                  <option value="LEFT">Слева</option>
                  <option value="NONE">Скрыть</option>
                </Select>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2.5">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Ширина (px)</label>
                <Input type="number" value={stWidth} onChange={(e) => setStWidth(Number(e.target.value))} />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Высота (px)</label>
                <Input type="number" value={stHeight} onChange={(e) => setStHeight(Number(e.target.value))} />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Макс. рядов</label>
                <Input type="number" value={stMaxLegend} onChange={(e) => setStMaxLegend(Number(e.target.value))} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-2.5">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Сортировка легенды</label>
                <Select value={stSortBy} onChange={(e) => setStSortBy(e.target.value)}>
                  <option value="NONE">Как в запросе</option>
                  <option value="MAX">По Max</option>
                  <option value="MEAN">По Mean</option>
                  <option value="LAST">По Last</option>
                </Select>
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Направление</label>
                <Select value={stSortDir} onChange={(e) => setStSortDir(e.target.value)}>
                  <option value="DESC">↓ По убыванию</option>
                  <option value="ASC">↑ По возрастанию</option>
                </Select>
              </div>
            </div>

            <div className="space-y-2 pt-1">
              <Switch
                checked={stStats}
                onChange={setStStats}
                label="Таблица Min / Mean / Max / Last в легенде"
              />
              <Switch
                checked={stAutoWidth}
                onChange={setStAutoWidth}
                label="Авторасширение при длинных подписях серий"
              />
              <Switch
                checked={stAbsTime}
                onChange={setStAbsTime}
                label="Абсолютное время ЧЧ:ММ по умолчанию"
              />
              <Switch
                checked={stSkipWarmup}
                onChange={setStSkipWarmup}
                label="Скрывать прогрев (warmup) по умолчанию"
              />
            </div>

            <div className="flex items-center justify-between pt-2 border-t border-slate-100">
              <Link to="/settings" className="text-indigo-600 hover:underline inline-flex items-center gap-1">
                <Settings className="h-3.5 w-3.5" />Все системные настройки
              </Link>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => setStyleOpen(false)}>
                  Закрыть
                </Button>
                {isAdmin && (
                  <Button
                    size="sm"
                    loading={savingStyle}
                    onClick={async () => {
                      setSavingStyle(true);
                      try {
                        await apiFetch('/api/v1/settings/report-charts', {
                          method: 'POST',
                          body: {
                            theme: stTheme,
                            width: stWidth,
                            height: stHeight,
                            legend: stLegend,
                            stats: stStats,
                            maxLegend: stMaxLegend,
                            absoluteTime: stAbsTime,
                            autoWidth: stAutoWidth,
                            sortBy: stSortBy,
                            sortDir: stSortDir,
                            skipWarmup: stSkipWarmup,
                          },
                        });
                        await qc.invalidateQueries({ queryKey: ['script-charts', id] });
                        setStyleOpen(false);
                        toast.success('Стиль графиков отчёта обновлён');
                        if (queries.some((q) => q.promql.trim())) {
                          triggerPreview();
                        }
                      } catch (err: any) {
                        toast.error(err.message);
                      } finally {
                        setSavingStyle(false);
                      }
                    }}
                  >
                    <Save className="h-3.5 w-3.5" />
                    Применить ко всем отчётам
                  </Button>
                )}
              </div>
            </div>
          </div>

          <div className="lg:col-span-7 space-y-2">
            <div className="font-medium text-slate-700">Живой макет PNG-графика в отчёте:</div>
            <div className="rounded-xl border border-slate-200 bg-slate-100/70 p-2.5 flex items-center justify-center min-h-[260px]">
              <img
                src={`/settings/report-charts/preview.png?theme=${stTheme}&width=${stWidth}&height=${stHeight}&legend=${stLegend}&stats=${stStats}&maxLegend=${stMaxLegend}&autoWidth=${stAutoWidth}&sortBy=${stSortBy}&sortDir=${stSortDir}&timeAxis=${stAbsTime ? 'abs' : 'rel'}&skipWarmup=${stSkipWarmup}&seriesCount=6`}
                alt="Предпросмотр стиля графиков"
                className="max-w-full h-auto rounded-lg shadow-xs"
              />
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
}
