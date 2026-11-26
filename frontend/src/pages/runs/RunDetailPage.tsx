import React, { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Line } from 'react-chartjs-2';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { fmtDateTime, fmtDurationSec, runStatusMeta } from '@/lib/utils';
import {
  Award,
  Download,
  FileText,
  OctagonX,
  RefreshCw,
  Save,
  Square,
  Terminal,
} from 'lucide-react';
import { toast } from 'sonner';

export function RunDetailPage() {
  const { id } = useParams<{ id: string }>();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [tab, setTab] = useState<'results' | 'metrics' | 'logs' | 'overview'>('results');
  const [logGenFilter, setLogGenFilter] = useState<string>('');
  const [logSearch, setLogSearch] = useState('');
  const [skipWarmup, setSkipWarmup] = useState(false);

  // Engineer conclusion state
  const [conclusion, setConclusion] = useState('');
  const [comment, setComment] = useState('');
  const [refreshingResults, setRefreshingResults] = useState(false);

  const { data: detail, isLoading } = useQuery({
    queryKey: ['run', id],
    queryFn: () => apiFetch(`/api/v1/runs/${id}`),
    refetchInterval: (q) => {
      const st = q.state.data?.run?.status;
      return st && ['QUEUED', 'PREPARING', 'RUNNING', 'STOPPING'].includes(st) ? 3000 : false;
    },
  });

  // Вкладка «Результаты» БЕЗ автообновления (обновляется только по кнопке, без дёрганья спиннера)
  const { data: resData, refetch: refetchResults } = useQuery({
    queryKey: ['run-results', id, skipWarmup],
    queryFn: () => apiFetch(`/api/v1/runs/${id}/results?skipWarmup=${skipWarmup}`),
  });

  useEffect(() => {
    if (resData) {
      setConclusion(resData.conclusion || '');
      setComment(resData.conclusionComment || '');
    }
  }, [resData]);

  // Живые метрики (история точек)
  const { data: metricsRaw } = useQuery({
    queryKey: ['run-metrics', id],
    queryFn: async () => {
      const res = await fetch(`/runs/${id}/metrics/history`, { credentials: 'same-origin' });
      if (!res.ok) return [];
      const arr = await res.json();
      return (arr || []).map((item: any) => (typeof item === 'string' ? JSON.parse(item) : item));
    },
    enabled: tab === 'metrics',
    refetchInterval: detail?.run?.live ? 4000 : false,
  });

  // Логи
  const { data: logsData, refetch: refetchLogs } = useQuery({
    queryKey: ['run-logs', id, logGenFilter],
    queryFn: () =>
      apiFetch(
        `/api/v1/runs/${id}/logs?limit=600${logGenFilter ? `&generatorId=${encodeURIComponent(logGenFilter)}` : ''}`
      ),
    enabled: tab === 'logs',
    refetchInterval: detail?.run?.live ? 2500 : false,
  });

  const stopMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/runs/${id}/stop`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Остановка запущена');
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
      const fresh = await apiFetch(`/api/v1/runs/${id}/results?refresh=true&skipWarmup=${skipWarmup}`);
      qc.setQueryData(['run-results', id, skipWarmup], fresh);
      toast.success('Итоги обновлены');
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setRefreshingResults(false);
    }
  };

  if (isLoading || !detail) return <Spinner label="Загрузка запуска…" />;

  const { run, snapshot, generators = [] } = detail;
  const st = runStatusMeta(run.status, run.thresholdsFailed);

  return (
    <div className="space-y-4">
      {/* Шапка запуска */}
      <Card className="p-4 space-y-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-bold text-slate-900">
                Запуск #{run.id} ·{' '}
                {run.scriptId ? (
                  <Link to={`/scripts/${run.scriptId}`} className="hover:text-indigo-600">
                    {run.scriptName}
                  </Link>
                ) : (
                  run.scriptName
                )}
              </h1>
              <Badge variant={st.variant}>{st.label}</Badge>
              {run.loadProfileName && <Badge variant="info">{run.loadProfileName}</Badge>}
            </div>
            <div className="text-xs text-slate-500 mt-1 flex flex-wrap gap-3">
              <span>Старт: {fmtDateTime(run.startedAt || run.queuedAt)}</span>
              {run.finishedAt && <span>Окончание: {fmtDateTime(run.finishedAt)}</span>}
              <span>Автор: {run.createdBy}</span>
              {run.stopReason && <span className="text-amber-700 font-medium">Причина: {run.stopReason}</span>}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <a href={`/runs/${run.id}/report.html`} target="_blank" rel="noreferrer">
              <Button variant="outline" size="sm">
                <FileText className="h-3.5 w-3.5" />
                HTML-отчёт
              </Button>
            </a>
            <a href={`/runs/${run.id}/report.pdf?download=true`}>
              <Button variant="outline" size="sm">
                <Download className="h-3.5 w-3.5" />
                PDF
              </Button>
            </a>
            {run.canStop && (
              <>
                <Button
                  variant="warning"
                  size="sm"
                  loading={stopMut.isPending}
                  onClick={async () => {
                    const ok = await confirm({
                      title: 'Остановить запуск?',
                      message: 'k6 корректно завершит текущие итерации.',
                      confirmText: 'Остановить',
                      variant: 'warning',
                    });
                    if (ok) stopMut.mutate();
                  }}
                >
                  <Square className="h-3.5 w-3.5 fill-current" />
                  Остановить
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  loading={abortMut.isPending}
                  onClick={async () => {
                    const ok = await confirm({
                      title: 'Принудительно прервать запуск?',
                      message: 'Убьёт процесс k6 на всех генераторах, метрики последних секунд могут потеряться.',
                      confirmText: 'Прервать',
                      variant: 'danger',
                    });
                    if (ok) abortMut.mutate();
                  }}
                >
                  <OctagonX className="h-3.5 w-3.5" />
                  Прервать
                </Button>
              </>
            )}
          </div>
        </div>

        {/* Прогресс-бар активного запуска */}
        {run.live && (
          <div className="flex items-center gap-3 pt-1">
            <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-slate-100">
              <div
                className="h-full rounded-full bg-indigo-600 transition-all"
                style={{ width: `${Math.min(100, Math.max(0, run.progressPct || 0))}%` }}
              />
            </div>
            <span className="text-xs font-mono text-slate-600">
              {run.currentStep ? `Ступень ${run.currentStep}/${run.totalSteps} (${run.currentPhase}) · ` : ''}
              {fmtDurationSec(run.elapsedSec)} / {fmtDurationSec(run.totalSec)} ({run.progressPct || 0}%)
            </span>
          </div>
        )}
      </Card>

      {/* Вкладки */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        {[
          { id: 'results', label: 'Результаты и SLA' },
          { id: 'metrics', label: 'Живые метрики' },
          { id: 'logs', label: 'Логи k6' },
          { id: 'overview', label: `Генераторы (${generators.length}) и конфиг` },
        ].map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id as any)}
            className={`border-b-2 px-4 py-2 text-sm font-medium cursor-pointer transition-colors ${
              tab === t.id
                ? 'border-indigo-600 text-indigo-600'
                : 'border-transparent text-slate-500 hover:text-slate-800'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Вкладка 1: Результаты и ступени */}
      {tab === 'results' && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-3">
              <Button
                variant="outline"
                size="sm"
                loading={refreshingResults}
                onClick={handleManualRefreshResults}
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Обновить результаты
              </Button>
              <Switch
                checked={skipWarmup}
                onChange={setSkipWarmup}
                label={<span className="text-xs text-slate-600">Графики без прогрева</span>}
              />
            </div>
            {resData?.data?.computedAt && (
              <span className="text-xs text-slate-400">Рассчитано: {fmtDateTime(resData.data.computedAt)}</span>
            )}
          </div>

          {/* Вывод инженера */}
          <Card>
            <CardHeader>
              <span className="font-semibold text-sm flex items-center gap-1.5">
                <Award className="h-4 w-4 text-indigo-600" />
                Вывод инженера
              </span>
            </CardHeader>
            <CardBody className="grid grid-cols-1 md:grid-cols-12 gap-3 items-start">
              <div className="md:col-span-4">
                <label className="block text-xs text-slate-500 mb-1">Итоговая оценка</label>
                <Select value={conclusion} onChange={(e) => setConclusion(e.target.value)}>
                  <option value="">— не указана —</option>
                  {(resData?.conclusions || []).map((c: string) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </Select>
              </div>
              <div className="md:col-span-6">
                <label className="block text-xs text-slate-500 mb-1">Комментарий</label>
                <Textarea
                  rows={2}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="Наблюдения, узкие места, ссылка на задачу…"
                />
              </div>
              <div className="md:col-span-2 md:pt-5">
                <Button
                  size="sm"
                  className="w-full"
                  loading={saveConclusionMut.isPending}
                  onClick={() => saveConclusionMut.mutate()}
                >
                  <Save className="h-3.5 w-3.5" />
                  Сохранить
                </Button>
              </div>
            </CardBody>
          </Card>

          {/* Баннер поиска максимума */}
          {resData?.maxSummary && (
            <div className="rounded-xl border border-amber-300 bg-amber-50/80 p-4 space-y-1">
              <div className="flex items-center gap-2 text-sm font-bold text-slate-900">
                <Badge variant="warning">Поиск максимума</Badge>
                <span>{resData.maxSummary.headline}</span>
              </div>
              {resData.maxSummary.detail && (
                <div className="text-xs text-slate-700">{resData.maxSummary.detail}</div>
              )}
            </div>
          )}

          {/* Таблица по ступеням */}
          {resData?.data?.steps?.length > 0 ? (
            <Card className="overflow-hidden">
              <CardHeader>
                <span className="font-semibold text-sm">Сводка по ступеням</span>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="bg-slate-50 text-slate-500 border-b border-slate-200">
                    <tr>
                      <th className="text-left px-4 py-2.5">Ступень</th>
                      <th className="text-right px-3 py-2.5">Уровень</th>
                      <th className="text-right px-3 py-2.5">План оп/с</th>
                      <th className="text-right px-3 py-2.5">Факт оп/с</th>
                      <th className="text-left px-3 py-2.5">Причины / Худший SLA</th>
                      <th className="text-right px-3 py-2.5">CPU ген.</th>
                      <th className="text-right px-4 py-2.5">Статус</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {resData.data.steps.map((s: any) => {
                      const isMax = resData?.maxSummary?.confirmedStepNo === s.stepNo;
                      const planRps = (s.ops || []).reduce((acc: number, o: any) => acc + (o.targetRate || 0), 0);
                      const actRps = (s.ops || []).reduce((acc: number, o: any) => acc + (o.actualRate || 0), 0);
                      return (
                        <tr
                          key={s.stepNo}
                          className={
                            isMax ? 'bg-emerald-50/70 font-medium' : s.status === 'FAILED' ? 'bg-rose-50/40' : ''
                          }
                        >
                          <td className="px-4 py-2.5 whitespace-nowrap">
                            <span className="font-semibold text-slate-900">{s.label || `Ступень ${s.stepNo}`}</span>
                            {isMax && (
                              <Badge variant="success" className="ml-1.5">
                                макс.
                              </Badge>
                            )}
                          </td>
                          <td className="px-3 py-2.5 text-right font-mono">{s.levelPct?.toFixed(1)}%</td>
                          <td className="px-3 py-2.5 text-right font-mono">{planRps.toFixed(1)}</td>
                          <td className="px-3 py-2.5 text-right font-mono">{actRps > 0 ? actRps.toFixed(1) : '—'}</td>
                          <td className="px-3 py-2.5 text-slate-600 max-w-md truncate">
                            {s.reasons?.length > 0 ? s.reasons.join(' · ') : 'В норме'}
                          </td>
                          <td className="px-3 py-2.5 text-right font-mono">
                            {s.genCpuMax != null ? `${Math.round(s.genCpuMax)}%` : '—'}
                          </td>
                          <td className="px-4 py-2.5 text-right">
                            <Badge
                              variant={
                                s.status === 'PASSED'
                                  ? 'success'
                                  : s.status === 'FAILED'
                                    ? 'danger'
                                    : 'default'
                              }
                            >
                              {s.status === 'PASSED' ? 'успешно' : s.status === 'FAILED' ? 'неуспешно' : s.status}
                            </Badge>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </Card>
          ) : (
            <Card className="p-8 text-center text-xs text-slate-500">
              {resData?.data?.error || 'Данные результатов появятся после накопления метрик Prometheus (нажмите «Обновить результаты»)'}
            </Card>
          )}

          {/* Кастомные графики отчёта */}
          {resData?.charts?.length > 0 && (
            <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
              {resData.charts.map((ch: any, idx: number) => (
                <Card key={idx} className="overflow-hidden">
                  <CardHeader>
                    <span className="font-semibold text-xs">{ch.title}</span>
                  </CardHeader>
                  <CardBody className="p-2">
                    <img src={ch.dataUri} alt={ch.title} className="w-full h-auto rounded-lg" />
                  </CardBody>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Вкладка 2: Живые метрики */}
      {tab === 'metrics' && (
        <Card className="p-4">
          {!metricsRaw || metricsRaw.length === 0 ? (
            <div className="py-12 text-center text-xs text-slate-400">Точек живых метрик пока нет</div>
          ) : (
            <div className="h-80">
              <Line
                data={{
                  labels: metricsRaw.map((p: any) =>
                    p.ts ? new Date(p.ts).toLocaleTimeString('ru-RU') : ''
                  ),
                  datasets: [
                    {
                      label: 'RPS / Итераций/с',
                      data: metricsRaw.map((p: any) => p.rps ?? p.rate ?? 0),
                      borderColor: '#4f46e5',
                      tension: 0.2,
                    },
                    {
                      label: 'p95, мс',
                      data: metricsRaw.map((p: any) => p.p95 ?? 0),
                      borderColor: '#f59e0b',
                      tension: 0.2,
                    },
                    {
                      label: 'VU',
                      data: metricsRaw.map((p: any) => p.vus ?? 0),
                      borderColor: '#10b981',
                      tension: 0.2,
                    },
                  ],
                }}
                options={{
                  responsive: true,
                  maintainAspectRatio: false,
                  plugins: { legend: { position: 'bottom' } },
                }}
              />
            </div>
          )}
        </Card>
      )}

      {/* Вкладка 3: Логи */}
      {tab === 'logs' && (
        <Card className="overflow-hidden">
          <CardHeader>
            <div className="flex flex-wrap items-center gap-2">
              <Select
                value={logGenFilter}
                onChange={(e) => setLogGenFilter(e.target.value)}
                className="h-8 w-52 text-xs"
              >
                <option value="">Все генераторы</option>
                {generators.map((g: any) => (
                  <option key={g.id} value={g.generatorId || ''}>
                    {g.generatorName}
                  </option>
                ))}
              </Select>
              <Input
                value={logSearch}
                onChange={(e) => setLogSearch(e.target.value)}
                placeholder="Фильтр строк…"
                className="h-8 w-56 text-xs"
              />
              <Button variant="outline" size="xs" onClick={() => refetchLogs()}>
                <RefreshCw className="h-3.5 w-3.5" />
              </Button>
            </div>
            <a href={`/runs/${run.id}/log.txt`} target="_blank" rel="noreferrer">
              <Button variant="outline" size="xs">
                <Terminal className="h-3.5 w-3.5" />
                Полный лог (.txt)
              </Button>
            </a>
          </CardHeader>
          <div className="bg-slate-950 text-slate-200 font-mono text-xs p-3 h-[55vh] overflow-y-auto space-y-0.5">
            {(logsData?.lines || [])
              .filter((l: any) => !logSearch || l.line.toLowerCase().includes(logSearch.toLowerCase()))
              .map((l: any, idx: number) => (
                <div
                  key={idx}
                  className={
                    l.level === 'ERROR' || l.line.includes('ERRO')
                      ? 'text-rose-400 whitespace-pre-wrap break-all'
                      : 'text-slate-200 whitespace-pre-wrap break-all'
                  }
                >
                  {l.line}
                </div>
              ))}
            {(!logsData?.lines || logsData.lines.length === 0) && (
              <div className="text-slate-500">Логи пусты</div>
            )}
          </div>
        </Card>
      )}

      {/* Вкладка 4: Генераторы и конфиг */}
      {tab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
          <Card className="lg:col-span-6">
            <CardHeader>
              <span className="font-semibold text-sm">Узлы генерации ({generators.length})</span>
            </CardHeader>
            <CardBody className="p-0">
              <table className="w-full text-xs">
                <thead className="bg-slate-50 text-slate-500 border-b border-slate-100">
                  <tr>
                    <th className="text-left px-4 py-2">Генератор</th>
                    <th className="text-left px-3 py-2">Статус</th>
                    <th className="text-right px-3 py-2">VU / Итер.</th>
                    <th className="text-right px-4 py-2">CPU / RAM</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {generators.map((g: any) => (
                    <tr key={g.id}>
                      <td className="px-4 py-2.5 font-medium">{g.generatorName}</td>
                      <td className="px-3 py-2.5">
                        <Badge variant={g.offline ? 'danger' : 'default'}>{g.status}</Badge>
                      </td>
                      <td className="px-3 py-2.5 text-right font-mono">
                        {g.vus ?? 0} / {g.iterations ?? 0}
                      </td>
                      <td className="px-4 py-2.5 text-right font-mono">
                        {g.cpu != null ? `${Math.round(g.cpu)}%` : '—'} /{' '}
                        {g.memPct != null ? `${Math.round(g.memPct)}%` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardBody>
          </Card>

          <Card className="lg:col-span-6">
            <CardHeader>
              <span className="font-semibold text-sm">Конфиг k6 этого запуска</span>
            </CardHeader>
            <CardBody className="p-0">
              <pre className="p-3 text-[11px] font-mono bg-slate-950 text-slate-200 max-h-96 overflow-auto rounded-b-xl">
                {JSON.stringify(detail.k6Config || snapshot, null, 2)}
              </pre>
            </CardBody>
          </Card>
        </div>
      )}
    </div>
  );
}
