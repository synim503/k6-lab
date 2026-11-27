import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Activity,
  CheckCircle2,
  Maximize2,
  Palette,
  RotateCcw,
  Save,
  Server,
  Sliders,
  XCircle,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
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

interface SettingsResponse {
  prometheus: {
    rwUrl: string;
    queryUrl: string;
    authType: 'NONE' | 'BASIC' | 'BEARER';
    user: string;
    hasSecret: boolean;
    grafanaUrlTpl: string;
    histogramMode: 'CLASSIC' | 'NATIVE';
    pushIntervalSec: number;
  };
  diagnostics: {
    running: boolean;
    passed: boolean;
    last?: {
      ok: boolean;
      summary?: string;
      steps?: { name: string; ok: boolean; detail?: string }[];
    } | null;
    progress?: string;
  };
  connectedGenerators: { id: number; name: string }[];
  chartStyle: {
    theme: string;
    width: number;
    height: number;
    legend: string;
    stats: boolean;
    maxLegend: number;
    autoWidth: boolean;
    sortBy: string;
    sortDir: string;
    absoluteTime: boolean;
    skipWarmup: boolean;
  };
  limits: {
    logLinesPerSec: number;
    retentionLogDays: number;
    retentionMetricsDays: number;
    allowMetrics: string;
    essentialMetrics?: string[];
    onlyNeededHistograms: boolean;
    urlNameTag: boolean;
  };
  agentPort?: number;
  requireDiagnostics?: boolean;
  metricNamesJson: string;
}

export function SettingsPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'prom' | 'charts' | 'limits' | 'names'>('prom');
  const hydratedRef = useRef(false);

  // Prometheus state
  const [rwUrl, setRwUrl] = useState('');
  const [queryUrl, setQueryUrl] = useState('');
  const [authType, setAuthType] = useState<'NONE' | 'BASIC' | 'BEARER'>('NONE');
  const [promUser, setPromUser] = useState('');
  const [promSecret, setPromSecret] = useState('');
  const [grafanaUrlTpl, setGrafanaUrlTpl] = useState('');
  const [histogramMode, setHistogramMode] = useState<'CLASSIC' | 'NATIVE'>('CLASSIC');
  const [pushIntervalSec, setPushIntervalSec] = useState(10);
  const [diagGenId, setDiagGenId] = useState('');

  // Chart style state
  const [theme, setTheme] = useState('DARK');
  const [width, setWidth] = useState(1100);
  const [height, setHeight] = useState(360);
  const [legend, setLegend] = useState('BOTTOM');
  const [stats, setStats] = useState(true);
  const [maxLegend, setMaxLegend] = useState(12);
  const [autoWidth, setAutoWidth] = useState(true);
  const [sortBy, setSortBy] = useState('MEAN');
  const [sortDir, setSortDir] = useState('DESC');
  const [absoluteTime, setAbsoluteTime] = useState(false);
  const [skipWarmup, setSkipWarmup] = useState(false);
  const [seriesCount, setSeriesCount] = useState<number>(6);
  const [naturalDim, setNaturalDim] = useState<{ w: number; h: number } | null>(null);
  const [zoomPreviewOpen, setZoomPreviewOpen] = useState(false);

  // Limits & metrics state
  const [logLinesPerSec, setLogLinesPerSec] = useState(200);
  const [retentionLogDays, setRetentionLogDays] = useState(30);
  const [retentionMetricsDays, setRetentionMetricsDays] = useState(7);
  const [allowMetrics, setAllowMetrics] = useState('');
  const [onlyNeededHistograms, setOnlyNeededHistograms] = useState(true);
  const [urlNameTag, setUrlNameTag] = useState(true);

  // Metric names JSON
  const [metricNamesJson, setMetricNamesJson] = useState('{}');

  const { data, isLoading } = useQuery<SettingsResponse>({
    queryKey: ['settings'],
    queryFn: () => apiFetch('/api/v1/settings'),
    refetchInterval: (query) => (query.state.data?.diagnostics?.running ? 1500 : false),
  });

  const applyServerStateToForms = (d: SettingsResponse) => {
    setRwUrl(d.prometheus.rwUrl);
    setQueryUrl(d.prometheus.queryUrl);
    setAuthType(d.prometheus.authType);
    setPromUser(d.prometheus.user);
    setPromSecret('');
    setGrafanaUrlTpl(d.prometheus.grafanaUrlTpl);
    setHistogramMode(d.prometheus.histogramMode);
    setPushIntervalSec(d.prometheus.pushIntervalSec);
    if (d.connectedGenerators.length > 0) {
      setDiagGenId((prev) => prev || String(d.connectedGenerators[0].id));
    }

    setTheme(d.chartStyle.theme === 'LIGHT' ? 'LIGHT' : 'DARK');
    setWidth(d.chartStyle.width);
    setHeight(d.chartStyle.height);
    setLegend(d.chartStyle.legend);
    setStats(d.chartStyle.stats);
    setMaxLegend(d.chartStyle.maxLegend);
    setAutoWidth(d.chartStyle.autoWidth);
    setSortBy(d.chartStyle.sortBy);
    setSortDir(d.chartStyle.sortDir);
    setAbsoluteTime(d.chartStyle.absoluteTime);
    setSkipWarmup(d.chartStyle.skipWarmup);

    setLogLinesPerSec(d.limits.logLinesPerSec);
    setRetentionLogDays(d.limits.retentionLogDays);
    setRetentionMetricsDays(d.limits.retentionMetricsDays);
    setAllowMetrics(d.limits.allowMetrics);
    setOnlyNeededHistograms(d.limits.onlyNeededHistograms);
    setUrlNameTag(d.limits.urlNameTag);

    setMetricNamesJson(d.metricNamesJson || '{}');
  };

  useEffect(() => {
    if (!data) return;
    if (!hydratedRef.current) {
      hydratedRef.current = true;
      applyServerStateToForms(data);
    } else if (!diagGenId && data.connectedGenerators.length > 0) {
      setDiagGenId(String(data.connectedGenerators[0].id));
    }
  }, [data]);

  const promMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/settings/prometheus', {
        method: 'POST',
        body: {
          rwUrl,
          queryUrl,
          authType,
          user: promUser,
          secret: promSecret || null,
          grafanaUrlTpl,
          histogramMode,
          pushIntervalSec: Number(pushIntervalSec) || 10,
        },
      }),
    onSuccess: () => {
      toast.success('Параметры Prometheus сохранены');
      setPromSecret('');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const diagMut = useMutation({
    mutationFn: () =>
      apiFetch<{ ok: boolean; started?: boolean }>('/api/v1/settings/diagnostics', {
        method: 'POST',
        body: { generatorId: Number(diagGenId), async: true },
      }),
    onSuccess: () => {
      toast.info('Диагностика запущена — шаги обновляются в реальном времени');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => {
      toast.error(e.message);
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
  });

  const chartMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/settings/report-charts', {
        method: 'POST',
        body: {
          theme,
          width: Number(width) || 1100,
          height: Number(height) || 360,
          legend,
          stats,
          maxLegend: Number(maxLegend) || 12,
          absoluteTime,
          autoWidth,
          sortBy,
          sortDir,
          skipWarmup,
        },
      }),
    onSuccess: () => {
      toast.success('Оформление графиков отчёта сохранено');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const resetChartMut = useMutation({
    mutationFn: () => apiFetch('/api/v1/settings/report-charts/reset', { method: 'POST' }),
    onSuccess: async () => {
      toast.success('Стиль графиков сброшен к значениям по умолчанию');
      const fresh = await apiFetch<SettingsResponse>('/api/v1/settings');
      qc.setQueryData(['settings'], fresh);
      applyServerStateToForms(fresh);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const limitsMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/settings/limits', {
        method: 'POST',
        body: {
          logLinesPerSec: Number(logLinesPerSec) || 200,
          retentionLogDays: Number(retentionLogDays) || 30,
          retentionMetricsDays: Number(retentionMetricsDays) || 7,
          allowMetrics,
          onlyNeededHistograms,
          urlNameTag,
        },
      }),
    onSuccess: () => {
      toast.success('Лимиты и фильтрация метрик сохранены');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const namesMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/settings/metric-names', {
        method: 'POST',
        body: { json: metricNamesJson },
      }),
    onSuccess: () => {
      toast.success('Словарь названий метрик сохранён');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const previewUrl = useMemo(() => {
    const sp = new URLSearchParams({
      theme,
      width: String(width),
      height: String(height),
      legend,
      stats: String(stats),
      maxLegend: String(maxLegend),
      timeAxis: absoluteTime ? 'abs' : 'rel',
      autoWidth: String(autoWidth),
      sortBy,
      sortDir,
      skipWarmup: String(skipWarmup),
      seriesCount: String(seriesCount),
    });
    return `/settings/report-charts/preview.png?${sp.toString()}`;
  }, [
    theme,
    width,
    height,
    legend,
    stats,
    maxLegend,
    absoluteTime,
    autoWidth,
    sortBy,
    sortDir,
    skipWarmup,
    seriesCount,
  ]);

  if (isLoading && !data) return <Spinner label="Загрузка системных настроек…" />;

  const diagRunning = !!data?.diagnostics?.running;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Системные настройки</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Prometheus / VictoriaMetrics, сквозная диагностика, стиль графиков отчёта, хранение и словарь метрик
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          {data?.agentPort && (
            <Badge variant="neutral">
              <Server className="h-3 w-3" />
              Порт агентов: {data.agentPort}
            </Badge>
          )}
          {data?.requireDiagnostics !== undefined && (
            <Badge variant={data.requireDiagnostics ? 'info' : 'warning'}>
              {data.requireDiagnostics
                ? 'Запуск тестов требует диагностики'
                : 'Проверка диагностики отключена (dev)'}
            </Badge>
          )}
        </div>
      </div>

      {/* Вкладки */}
      <div className="flex flex-wrap gap-1.5">
        {(
          [
            ['prom', 'Prometheus и диагностика'],
            ['charts', 'Графики отчёта'],
            ['limits', 'Метрики и хранение'],
            ['names', 'Словарь имён метрик'],
          ] as const
        ).map(([tabId, title]) => (
          <button
            key={tabId}
            type="button"
            onClick={() => setTab(tabId)}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer ${
              tab === tabId
                ? 'bg-indigo-600 text-white'
                : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-50'
            }`}
          >
            {title}
          </button>
        ))}
      </div>

      {tab === 'prom' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          <Card className="lg:col-span-2">
            <CardHeader>
              <div className="font-semibold text-sm text-slate-900">
                Подключение к Prometheus / VictoriaMetrics
              </div>
            </CardHeader>
            <CardBody className="space-y-3.5">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">
                    Remote Write URL (для агентов)
                  </label>
                  <Input
                    value={rwUrl}
                    onChange={(e) => setRwUrl(e.target.value)}
                    placeholder="http://prometheus:9090/api/v1/write"
                    className="font-mono text-xs"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">
                    Query Base URL (для админки)
                  </label>
                  <Input
                    value={queryUrl}
                    onChange={(e) => setQueryUrl(e.target.value)}
                    placeholder="http://prometheus:9090"
                    className="font-mono text-xs"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Авторизация</label>
                  <Select value={authType} onChange={(e) => setAuthType(e.target.value as any)}>
                    <option value="NONE">Без авторизации</option>
                    <option value="BASIC">Basic Auth</option>
                    <option value="BEARER">Bearer Token</option>
                  </Select>
                </div>
                {authType === 'BASIC' && (
                  <div>
                    <label className="block text-xs font-medium text-slate-700 mb-1">Логин</label>
                    <Input value={promUser} onChange={(e) => setPromUser(e.target.value)} />
                  </div>
                )}
                {authType !== 'NONE' && (
                  <div className={authType === 'BEARER' ? 'sm:col-span-2' : ''}>
                    <label className="block text-xs font-medium text-slate-700 mb-1">
                      {authType === 'BEARER' ? 'Токен' : 'Пароль'}{' '}
                      {data?.prometheus.hasSecret && '(оставьте пустым, чтобы сохранить текущий)'}
                    </label>
                    <Input type="password" value={promSecret} onChange={(e) => setPromSecret(e.target.value)} />
                  </div>
                )}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Режим гистограмм k6</label>
                  <Select value={histogramMode} onChange={(e) => setHistogramMode(e.target.value as any)}>
                    <option value="CLASSIC">CLASSIC (_bucket)</option>
                    <option value="NATIVE">NATIVE Histograms</option>
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">
                    Интервал отправки (5–60 сек)
                  </label>
                  <Input
                    type="number"
                    min={5}
                    max={60}
                    value={pushIntervalSec}
                    onChange={(e) => setPushIntervalSec(Number(e.target.value))}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Шаблон ссылки Grafana</label>
                  <Input
                    value={grafanaUrlTpl}
                    onChange={(e) => setGrafanaUrlTpl(e.target.value)}
                    placeholder="https://grafana/d/k6?var-testid={runId}"
                    className="font-mono text-xs"
                  />
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <Button size="sm" loading={promMut.isPending} onClick={() => promMut.mutate()}>
                  <Save className="h-3.5 w-3.5" />
                  Сохранить Prometheus
                </Button>
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader>
              <div className="font-semibold text-sm text-slate-900">Сквозная диагностика</div>
              <Badge variant={diagRunning ? 'info' : data?.diagnostics.passed ? 'success' : 'warning'}>
                {diagRunning ? 'Выполняется…' : data?.diagnostics.passed ? 'Пройдена' : 'Не подтверждена'}
              </Badge>
            </CardHeader>
            <CardBody className="space-y-3">
              <p className="text-xs text-slate-500">
                Отправляет пробную метрику с выбранного онлайн-генератора в Remote Write и проверяет чтение через Query API.
              </p>
              <Select value={diagGenId} onChange={(e) => setDiagGenId(e.target.value)}>
                <option value="">— Выберите онлайн-генератор —</option>
                {data?.connectedGenerators.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </Select>
              <Button
                className="w-full"
                size="sm"
                variant="secondary"
                disabled={!diagGenId || diagRunning}
                loading={diagMut.isPending || diagRunning}
                onClick={() => diagMut.mutate()}
              >
                <Activity className="h-3.5 w-3.5" />
                {diagRunning ? 'Проверка выполняется…' : 'Запустить проверку'}
              </Button>

              {diagRunning && data?.diagnostics?.progress && (
                <div className="rounded-lg border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs text-indigo-800 animate-pulse">
                  {data.diagnostics.progress}
                </div>
              )}

              {data?.diagnostics.last && (
                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 space-y-2 text-xs">
                  <div className="font-medium text-slate-800">{data.diagnostics.last.summary}</div>
                  {data.diagnostics.last.steps?.map((s, idx) => (
                    <div key={idx} className="flex items-start gap-2">
                      {s.ok ? (
                        <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 shrink-0 mt-0.5" />
                      ) : (
                        <XCircle className="h-3.5 w-3.5 text-rose-600 shrink-0 mt-0.5" />
                      )}
                      <div>
                        <div className="font-medium text-slate-700">{s.name}</div>
                        {s.detail && <div className="text-slate-500 break-words">{s.detail}</div>}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardBody>
          </Card>
        </div>
      )}

      {tab === 'charts' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
          <Card className="lg:col-span-5">
            <CardHeader>
              <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
                <Palette className="h-4 w-4 text-indigo-600" />
                Стиль графиков отчёта (HTML / PDF / DOCX)
              </div>
            </CardHeader>
            <CardBody className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Тема</label>
                  <Select value={theme} onChange={(e) => setTheme(e.target.value)}>
                    <option value="DARK">Grafana Dark (Тёмная)</option>
                    <option value="LIGHT">Светлая (Light)</option>
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Положение легенды</label>
                  <Select value={legend} onChange={(e) => setLegend(e.target.value)}>
                    <option value="BOTTOM">Снизу</option>
                    <option value="RIGHT">Справа</option>
                    <option value="LEFT">Слева</option>
                    <option value="NONE">Скрыть</option>
                  </Select>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Ширина (px)</label>
                  <Input type="number" value={width} onChange={(e) => setWidth(Number(e.target.value))} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Высота (px)</label>
                  <Input type="number" value={height} onChange={(e) => setHeight(Number(e.target.value))} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Макс. серий</label>
                  <Input type="number" value={maxLegend} onChange={(e) => setMaxLegend(Number(e.target.value))} />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Сортировка легенды</label>
                  <Select value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
                    <option value="NONE">По имени</option>
                    <option value="MAX">По Max</option>
                    <option value="MEAN">По Mean</option>
                    <option value="LAST">По Last</option>
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Направление</label>
                  <Select value={sortDir} onChange={(e) => setSortDir(e.target.value)}>
                    <option value="DESC">По убыванию</option>
                    <option value="ASC">По возрастанию</option>
                  </Select>
                </div>
              </div>

              <div className="space-y-2 pt-1">
                <Switch
                  checked={stats}
                  onChange={setStats}
                  label="Показывать таблицу Min / Mean / Max / Last в легенде"
                />
                <Switch
                  checked={autoWidth}
                  onChange={setAutoWidth}
                  label="Авторасширение при длинных подписях серий"
                />
                <Switch
                  checked={absoluteTime}
                  onChange={setAbsoluteTime}
                  label="Абсолютное время ЧЧ:ММ на оси X (вместо минут от старта)"
                />
                <Switch
                  checked={skipWarmup}
                  onChange={setSkipWarmup}
                  label="Скрывать участок прогрева (warmup) на графиках"
                />
              </div>

              <div className="flex items-center justify-between pt-2 border-t border-slate-100">
                <Button
                  variant="outline"
                  size="sm"
                  loading={resetChartMut.isPending}
                  onClick={() => resetChartMut.mutate()}
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  По умолчанию
                </Button>
                <Button size="sm" loading={chartMut.isPending} onClick={() => chartMut.mutate()}>
                  <Save className="h-3.5 w-3.5" />
                  Сохранить стиль
                </Button>
              </div>
            </CardBody>
          </Card>

          <Card className="lg:col-span-7">
            <CardHeader>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-sm text-slate-900">Живой предпросмотр рендерера</span>
                {naturalDim && (
                  <Badge variant="neutral">
                    {naturalDim.w} × {naturalDim.h} px
                  </Badge>
                )}
              </div>
              <div className="flex items-center gap-2">
                <div className="inline-flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-xs">
                  {[4, 6, 12].map((cnt) => (
                    <button
                      key={cnt}
                      type="button"
                      onClick={() => setSeriesCount(cnt)}
                      className={`rounded-md px-2 py-0.5 font-medium cursor-pointer ${
                        seriesCount === cnt ? 'bg-indigo-600 text-white' : 'text-slate-600'
                      }`}
                    >
                      {cnt} сер.
                    </button>
                  ))}
                </div>
                <Button variant="ghost" size="xs" onClick={() => setZoomPreviewOpen(true)}>
                  <Maximize2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            </CardHeader>
            <CardBody className="flex items-center justify-center bg-slate-50/70">
              <img
                src={previewUrl}
                alt="Предпросмотр графика отчёта"
                onClick={() => setZoomPreviewOpen(true)}
                onLoad={(e) => {
                  const img = e.currentTarget;
                  if (img.naturalWidth && img.naturalHeight) {
                    setNaturalDim({ w: img.naturalWidth, h: img.naturalHeight });
                  }
                }}
                className="max-w-full rounded-lg border border-slate-200 shadow-xs cursor-zoom-in"
              />
            </CardBody>
          </Card>
        </div>
      )}

      <Modal
        open={zoomPreviewOpen}
        onClose={() => setZoomPreviewOpen(false)}
        title={`Предпросмотр рендерера (${naturalDim ? `${naturalDim.w} × ${naturalDim.h} px` : ''})`}
        maxWidth="max-w-6xl"
      >
        <img src={previewUrl} alt="Zoom preview" className="w-full h-auto rounded-lg border border-slate-200" />
      </Modal>

      {tab === 'limits' && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
              <Sliders className="h-4 w-4 text-indigo-600" />
              Хранение логов и фильтрация метрик k6
            </div>
          </CardHeader>
          <CardBody className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Лимит строк лога / сек с агента
                </label>
                <Input
                  type="number"
                  min={10}
                  value={logLinesPerSec}
                  onChange={(e) => setLogLinesPerSec(Number(e.target.value))}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Хранение логов запусков (дней)
                </label>
                <Input
                  type="number"
                  min={1}
                  value={retentionLogDays}
                  onChange={(e) => setRetentionLogDays(Number(e.target.value))}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Хранение снимков результатов (дней)
                </label>
                <Input
                  type="number"
                  min={1}
                  value={retentionMetricsDays}
                  onChange={(e) => setRetentionMetricsDays(Number(e.target.value))}
                />
              </div>
            </div>

            <div className="space-y-2">
              <label className="block text-xs font-medium text-slate-700">
                Белый список метрик для Remote Write (по одной маске на строку, пусто = отправлять все)
              </label>
              <Textarea
                rows={5}
                className="font-mono text-xs"
                value={allowMetrics}
                onChange={(e) => setAllowMetrics(e.target.value)}
                placeholder={'k6_http_req_*\nk6_vus*\ncustom_metric_*'}
              />
              {data?.limits?.essentialMetrics && data.limits.essentialMetrics.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-slate-500">
                  <span>Всегда отправляются автоматически (нужны админке):</span>
                  {data.limits.essentialMetrics.map((em) => (
                    <code key={em} className="rounded bg-slate-100 px-1.5 py-0.5 text-slate-700">
                      {em}
                    </code>
                  ))}
                </div>
              )}
            </div>

            <div className="space-y-2">
              <Switch
                checked={onlyNeededHistograms}
                onChange={setOnlyNeededHistograms}
                label="Отправлять бакеты гистограмм только для метрик, нужных отчёту и SLA"
              />
              <Switch
                checked={urlNameTag}
                onChange={setUrlNameTag}
                label="Сохранять тег name (URL запроса) на HTTP-метриках (отключите при динамических ID в пути)"
              />
            </div>

            <div className="flex justify-end">
              <Button size="sm" loading={limitsMut.isPending} onClick={() => limitsMut.mutate()}>
                <Save className="h-3.5 w-3.5" />
                Сохранить параметры
              </Button>
            </div>
          </CardBody>
        </Card>
      )}

      {tab === 'names' && (
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">
              Человекочитаемые названия метрик в отчётах (JSON)
            </div>
          </CardHeader>
          <CardBody className="space-y-3">
            <p className="text-xs text-slate-500">
              Переопределение отображаемых имён кастомных метрик k6 в итоговых таблицах отчёта (
              <code>{'"my_trend": "Время ответа шлюза, мс"'}</code>).
            </p>
            <Textarea
              rows={12}
              className="font-mono text-xs"
              value={metricNamesJson}
              onChange={(e) => setMetricNamesJson(e.target.value)}
            />
            <div className="flex justify-end">
              <Button size="sm" loading={namesMut.isPending} onClick={() => namesMut.mutate()}>
                <Save className="h-3.5 w-3.5" />
                Сохранить словарь
              </Button>
            </div>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
